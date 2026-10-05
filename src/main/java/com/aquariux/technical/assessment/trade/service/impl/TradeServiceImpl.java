package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPair;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.mapper.*;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeServiceInterface {

    private final TradeMapper tradeMapper;
    private final UserMapper userMapper;
    private final CryptoPairMapper cryptoPairMapper;
    private final CryptoPriceMapper cryptoPriceMapper;
    private final UserWalletMapper userWalletMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeResponse executeTrade(TradeRequest tradeRequest) throws BadRequestException {
        // Ensure that we process trades for the same user one at a time, while validating the userId
        lockAndValidateUser(tradeRequest);

        // Get latest best crypto price for given crypto pair
        CryptoPair cryptoPair = cryptoPairMapper.findByPairName(tradeRequest.getCryptoPair());
        if (cryptoPair == null) {
            log.error("Crypto pair not found. cryptoPair={}", tradeRequest.getCryptoPair());
            throw new BadRequestException("Crypto pair not found. cryptoPair=" + tradeRequest.getCryptoPair());
        }
        if (!cryptoPair.getActive()) {
            log.error("Crypto pair is not active. cryptoPair={}", tradeRequest.getCryptoPair());
            throw new BadRequestException("Crypto pair is not active. cryptoPair=" + tradeRequest.getCryptoPair());
        }

        Optional<CryptoPrice> cryptoPriceOptional = cryptoPriceMapper.findLatestPrices().stream().filter(price -> Objects.equals(price.getCryptoPairId(), cryptoPair.getId())).findFirst();
        if (cryptoPriceOptional.isEmpty()) {
            log.error("Crypto price not found for crypto pair. cryptoPairId={} cryptoPair={}", cryptoPair.getId(), tradeRequest.getCryptoPair());
            throw new InternalError("Crypto price not found for crypto pair. cryptoPairId=" + cryptoPair.getId() + " cryptoPair=" + tradeRequest.getCryptoPair());
        }
        CryptoPrice cryptoPrice = cryptoPriceOptional.get();

        // Get base and quote balance
        List<UserWalletDto> userWalletBalances = userWalletMapper.findByUserId(tradeRequest.getUserId());
        BigDecimal quoteBalance = getBalanceBySymbolId(userWalletBalances, cryptoPair.getQuoteSymbolId());
        BigDecimal baseBalance = getBalanceBySymbolId(userWalletBalances, cryptoPair.getBaseSymbolId());

        // Validate wallet balance
        validateSufficientWalletBalance(tradeRequest, cryptoPrice, baseBalance, quoteBalance);

        // Execute trade
        // a. Create trade entry
        Trade trade = new Trade();
        trade.setUserId(tradeRequest.getUserId());
        trade.setCryptoPairId(cryptoPair.getId());
        trade.setTradeType(tradeRequest.getTradeType().name());
        trade.setQuantity(tradeRequest.getQuantity());
        trade.setPrice(tradeRequest.getTradeType().equals(TradeType.BUY) ? cryptoPrice.getAskPrice() : cryptoPrice.getBidPrice());
        trade.setTotalAmount(tradeRequest.getTradeType().equals(TradeType.BUY) ? calculateTotalBuyPrice(tradeRequest, cryptoPrice) : calculateTotalSellPrice(tradeRequest, cryptoPrice));
        tradeMapper.insertTrade(trade);

        // b. Update the wallet balances in DB
        updateWalletBalances(tradeRequest, cryptoPrice, quoteBalance, baseBalance, cryptoPair);

        TradeResponse tradeResponse = new TradeResponse();
        tradeResponse.setUserId(tradeRequest.getUserId());
        tradeResponse.setTradeId(trade.getId());
        tradeResponse.setTradeType(tradeRequest.getTradeType());
        tradeResponse.setQuantity(tradeRequest.getQuantity());
        tradeResponse.setCryptoPair(tradeRequest.getCryptoPair());
        tradeResponse.setPrice(trade.getPrice());
        tradeResponse.setTotalAmount(trade.getTotalAmount());
        tradeResponse.setCreatedAtDatetime(trade.getTradeTime());
        return tradeResponse;
    }

    private void updateWalletBalances(TradeRequest tradeRequest, CryptoPrice cryptoPrice, BigDecimal quoteBalance, BigDecimal baseBalance, CryptoPair cryptoPair) {
        if (tradeRequest.getTradeType().equals(TradeType.BUY)) {
            // Buying the base asset here
            BigDecimal totalPrice = calculateTotalBuyPrice(tradeRequest, cryptoPrice);
            BigDecimal newQuoteBalance = quoteBalance.subtract(totalPrice);
            BigDecimal newBaseBalance = baseBalance.add(tradeRequest.getQuantity());

            userWalletMapper.upsertBalance(tradeRequest.getUserId(), cryptoPair.getQuoteSymbolId(), newQuoteBalance);
            userWalletMapper.upsertBalance(tradeRequest.getUserId(), cryptoPair.getBaseSymbolId(), newBaseBalance);
        } else {
            // Selling the base asset here
            BigDecimal totalPrice = calculateTotalSellPrice(tradeRequest, cryptoPrice);
            BigDecimal newQuoteBalance = quoteBalance.add(totalPrice);
            BigDecimal newBaseBalance = baseBalance.subtract(tradeRequest.getQuantity());

            userWalletMapper.upsertBalance(tradeRequest.getUserId(), cryptoPair.getQuoteSymbolId(), newQuoteBalance);
            userWalletMapper.upsertBalance(tradeRequest.getUserId(), cryptoPair.getBaseSymbolId(), newBaseBalance);
        }
    }

    private static void validateSufficientWalletBalance(TradeRequest tradeRequest, CryptoPrice cryptoPrice, BigDecimal baseBalance, BigDecimal quoteBalance) throws BadRequestException {
        // if tradeType is BUY, validate for sufficient quote balance
        if (tradeRequest.getTradeType().equals(TradeType.BUY)) {
            // check for total quote currency needed
            BigDecimal totalPrice = calculateTotalBuyPrice(tradeRequest, cryptoPrice);

            // check if enough quote balance
            if (quoteBalance.compareTo(totalPrice) < 0) {
                throw new BadRequestException("Insufficient quote balance for trade. quoteBalance=" + quoteBalance + " quantity" + tradeRequest.getQuantity());
            }
        // if tradeType is SELL, validate for sufficient base balance
        } else {
            // check if enough base balance
            if (baseBalance.compareTo(tradeRequest.getQuantity()) < 0) {
                throw new BadRequestException("Insufficient base balance for trade. baseBalance=" + baseBalance + " quantity=" + tradeRequest.getQuantity());
            }
        }
    }

    private void lockAndValidateUser(TradeRequest tradeRequest) throws BadRequestException {
        Long userId = tradeRequest.getUserId();

        if (userMapper.findUserIdForUpdate(userId) == null) {
            log.error("User does not exist. userId={}", userId);
            throw new BadRequestException("User does not exist. userId=" + userId);
        }
    }

    private static @NonNull BigDecimal calculateTotalBuyPrice(TradeRequest tradeRequest, CryptoPrice cryptoPrice) {
        BigDecimal currentAskPrice = cryptoPrice.getAskPrice();
        return currentAskPrice.multiply(tradeRequest.getQuantity());
    }

    private static @NonNull BigDecimal calculateTotalSellPrice(TradeRequest tradeRequest, CryptoPrice cryptoPrice) {
        BigDecimal currentBidPrice = cryptoPrice.getBidPrice();
        return currentBidPrice.multiply(tradeRequest.getQuantity());
    }

    private static @NonNull BigDecimal getBalanceBySymbolId(List<UserWalletDto> userWalletBalances, Long cryptoPair) {
        return userWalletBalances.stream()
                .filter(b -> Objects.equals(b.getSymbolId(), cryptoPair))
                .findFirst()
                .map(UserWalletDto::getBalance)
                .orElse(BigDecimal.ZERO);
    }
}
