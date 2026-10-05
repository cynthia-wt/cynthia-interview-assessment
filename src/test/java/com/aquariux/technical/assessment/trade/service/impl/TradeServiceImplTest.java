package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPair;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.apache.coyote.BadRequestException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TradeServiceImplTest {

    @Mock
    private TradeMapper tradeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private CryptoPairMapper cryptoPairMapper;
    @Mock
    private CryptoPriceMapper cryptoPriceMapper;
    @Mock
    private UserWalletMapper userWalletMapper;

    @InjectMocks
    private TradeServiceImpl tradeService;

    private CryptoPair pair;
    private CryptoPair btcPair;
    private CryptoPrice btcPrice;
    private CryptoPrice ethPrice;
    private UserWalletDto usdtWallet;
    private UserWalletDto ethWallet;
    private UserWalletDto btcWallet;

    @BeforeEach
    void setUp() {
        pair = new CryptoPair();
        pair.setId(2L);
        pair.setBaseSymbolId(2L);
        pair.setQuoteSymbolId(3L);
        pair.setPairName("ETHUSDT");
        pair.setActive(true);

        btcPair = new CryptoPair();
        btcPair.setId(1L);
        btcPair.setBaseSymbolId(1L);
        btcPair.setQuoteSymbolId(3L);
        btcPair.setPairName("BTCUSDT");
        btcPair.setActive(true);

        btcPrice = new CryptoPrice();
        btcPrice.setId(100L);
        btcPrice.setCryptoPairId(1L);
        btcPrice.setBidPrice(new BigDecimal("50000.00"));
        btcPrice.setAskPrice(new BigDecimal("50100.00"));

        ethPrice = new CryptoPrice();
        ethPrice.setId(101L);
        ethPrice.setCryptoPairId(2L);
        ethPrice.setBidPrice(new BigDecimal("3000.00"));
        ethPrice.setAskPrice(new BigDecimal("3050.00"));

        usdtWallet = new UserWalletDto();
        usdtWallet.setSymbolId(3L);
        usdtWallet.setBalance(new BigDecimal("10000.00"));
        ethWallet = new UserWalletDto();
        ethWallet.setSymbolId(2L);
        ethWallet.setBalance(new BigDecimal("1.00"));

        btcWallet = new UserWalletDto();
        btcWallet.setSymbolId(1L);
        btcWallet.setBalance(new BigDecimal("1.00"));
    }

    @Test
    void executeTrade_WhenBuyingEthUsdt_ShouldRecordTradeAndUpdateBalances() throws Exception {
        setUpDB(pair, ethWallet);

        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT");

        // When
        TradeResponse result = tradeService.executeTrade(request);

        // Then
        assertThat(result.getTradeId()).isEqualTo(42L);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getTradeType()).isEqualTo(TradeType.BUY);
        assertThat(result.getCryptoPair()).isEqualTo("ETHUSDT");
        assertThat(result.getQuantity()).isEqualByComparingTo("2.00");
        assertThat(result.getPrice()).isEqualByComparingTo("3050.00");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("6100.00");

        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeMapper).insertTrade(tradeCaptor.capture());
        Trade savedTrade = tradeCaptor.getValue();
        assertThat(savedTrade.getUserId()).isEqualTo(1L);
        assertThat(savedTrade.getCryptoPairId()).isEqualTo(2L);
        assertThat(savedTrade.getTradeType()).isEqualTo("BUY");
        assertThat(savedTrade.getQuantity()).isEqualByComparingTo("2.00");
        assertThat(savedTrade.getPrice()).isEqualByComparingTo("3050.00");
        assertThat(savedTrade.getTotalAmount()).isEqualByComparingTo("6100.00");

        ArgumentCaptor<BigDecimal> quoteBalanceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userWalletMapper).upsertBalance(eq(1L), eq(3L), quoteBalanceCaptor.capture());
        assertThat(quoteBalanceCaptor.getValue()).isEqualByComparingTo("3900.00");

        ArgumentCaptor<BigDecimal> baseBalanceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userWalletMapper).upsertBalance(eq(1L), eq(2L), baseBalanceCaptor.capture());
        assertThat(baseBalanceCaptor.getValue()).isEqualByComparingTo("3.00");
    }

    @Test
    void executeTrade_WhenSellingBtcUsdt_ShouldRecordTradeAndUpdateBalances() throws Exception {
        // Given
        setUpDB(btcPair, btcWallet);
        TradeRequest request = new TradeRequest(1L, TradeType.SELL, new BigDecimal("0.25"), "BTCUSDT");

        // When
        TradeResponse result = tradeService.executeTrade(request);

        // Then
        assertThat(result.getTradeId()).isEqualTo(42L);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getTradeType()).isEqualTo(TradeType.SELL);
        assertThat(result.getCryptoPair()).isEqualTo("BTCUSDT");
        assertThat(result.getQuantity()).isEqualByComparingTo("0.25");
        assertThat(result.getPrice()).isEqualByComparingTo("50000.00");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("12500.00");

        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeMapper).insertTrade(tradeCaptor.capture());
        Trade savedTrade = tradeCaptor.getValue();
        assertThat(savedTrade.getUserId()).isEqualTo(1L);
        assertThat(savedTrade.getCryptoPairId()).isEqualTo(1L);
        assertThat(savedTrade.getTradeType()).isEqualTo("SELL");
        assertThat(savedTrade.getQuantity()).isEqualByComparingTo("0.25");
        assertThat(savedTrade.getPrice()).isEqualByComparingTo("50000.00");
        assertThat(savedTrade.getTotalAmount()).isEqualByComparingTo("12500.00");

        ArgumentCaptor<BigDecimal> quoteBalanceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userWalletMapper).upsertBalance(eq(1L), eq(3L), quoteBalanceCaptor.capture());
        assertThat(quoteBalanceCaptor.getValue()).isEqualByComparingTo("22500.00");

        ArgumentCaptor<BigDecimal> baseBalanceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userWalletMapper).upsertBalance(eq(1L), eq(1L), baseBalanceCaptor.capture());
        assertThat(baseBalanceCaptor.getValue()).isEqualByComparingTo("0.75");
    }

    @Test
    void executeTrade_WhenUserDoesNotExist_ShouldThrowWithMessage() {
        // Given
        TradeRequest request = new TradeRequest(999L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT");
        when(userMapper.existsByUserId(999L)).thenReturn(false);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("User does not exist. userId=999");
        verifyNoInteractions(cryptoPairMapper, cryptoPriceMapper, userWalletMapper, tradeMapper);
    }

    @Test
    void executeTrade_WhenCryptoPairNotFound_ShouldThrowWithMessage() {
        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "UNKNOWN");
        when(userMapper.existsByUserId(1L)).thenReturn(true);
        when(cryptoPairMapper.findByPairName("UNKNOWN")).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Crypto pair not found. cryptoPair=UNKNOWN");
        verifyNoInteractions(cryptoPriceMapper, userWalletMapper, tradeMapper);
    }

    @Test
    void executeTrade_WhenCryptoPairInactive_ShouldThrowWithMessage() {
        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT");
        pair.setActive(false);
        when(userMapper.existsByUserId(1L)).thenReturn(true);
        when(cryptoPairMapper.findByPairName("ETHUSDT")).thenReturn(pair);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Crypto pair is not active. cryptoPair=ETHUSDT");
        verifyNoInteractions(cryptoPriceMapper, userWalletMapper, tradeMapper);
    }

    @Test
    void executeTrade_WhenRequestedPairPriceMissing_ShouldThrowWithMessage() {
        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT");
        when(userMapper.existsByUserId(1L)).thenReturn(true);
        when(cryptoPairMapper.findByPairName("ETHUSDT")).thenReturn(pair);
        when(cryptoPriceMapper.findLatestPrices()).thenReturn(List.of(btcPrice));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InternalError.class)
                .hasMessage("Crypto price not found for crypto pair. cryptoPairId=2 cryptoPair=ETHUSDT");
        verifyNoInteractions(userWalletMapper, tradeMapper);
    }

    @Test
    void executeTrade_WhenBuyBalanceInsufficient_ShouldThrowWithMessage() {
        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT");
        usdtWallet.setBalance(new BigDecimal("6000.00"));
        mockTradeValidationDependencies(pair, ethWallet);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Insufficient quote balance for trade. quoteBalance=6000.00 quantity2.00");
        verifyNoInteractions(tradeMapper);
        verify(userWalletMapper, never()).upsertBalance(any(), any(), any());
    }

    @Test
    void executeTrade_WhenSellBalanceInsufficient_ShouldThrowWithMessage() {
        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.SELL, new BigDecimal("1.25"), "BTCUSDT");
        mockTradeValidationDependencies(btcPair, btcWallet);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Insufficient base balance for trade. baseBalance=1.00 quantity=1.25");
        verifyNoInteractions(tradeMapper);
        verify(userWalletMapper, never()).upsertBalance(any(), any(), any());
    }

    @Test
    void executeTrade_WhenTradeInsertFails_ShouldPropagateExceptionAndNotUpdateWallets() {
        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT");
        mockTradeValidationDependencies(pair, ethWallet);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Trade insert failed");
        doThrow(failure).when(tradeMapper).insertTrade(any(Trade.class));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isSameAs(failure)
                .hasMessage("Trade insert failed");
        verify(tradeMapper).insertTrade(any(Trade.class));
        verify(userWalletMapper, never()).upsertBalance(any(), any(), any());
    }

    @Test
    void executeTrade_WhenBuyingWithoutBaseWallet_ShouldUpsertNewWalletBalance() throws Exception {
        // Given
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT");
        when(userMapper.existsByUserId(1L)).thenReturn(true);
        when(cryptoPairMapper.findByPairName("ETHUSDT")).thenReturn(pair);
        when(cryptoPriceMapper.findLatestPrices()).thenReturn(List.of(btcPrice, ethPrice));
        // No ETH wallet exists yet; only the USDT wallet is returned.
        when(userWalletMapper.findByUserId(1L)).thenReturn(List.of(usdtWallet));
        doAnswer(invocation -> {
            Trade trade = invocation.getArgument(0);
            trade.setId(42L);
            return null;
        }).when(tradeMapper).insertTrade(any(Trade.class));

        // When
        TradeResponse result = tradeService.executeTrade(request);

        // Then
        assertThat(result.getTradeId()).isEqualTo(42L);
        assertThat(result.getTradeType()).isEqualTo(TradeType.BUY);
        assertThat(result.getCryptoPair()).isEqualTo("ETHUSDT");
        assertThat(result.getQuantity()).isEqualByComparingTo("2.00");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("6100.00");
        verify(tradeMapper).insertTrade(any(Trade.class));

        ArgumentCaptor<BigDecimal> baseBalanceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userWalletMapper).upsertBalance(eq(1L), eq(2L), baseBalanceCaptor.capture());
        assertThat(baseBalanceCaptor.getValue()).isEqualByComparingTo("2.00");

        ArgumentCaptor<BigDecimal> quoteBalanceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userWalletMapper).upsertBalance(eq(1L), eq(3L), quoteBalanceCaptor.capture());
        assertThat(quoteBalanceCaptor.getValue()).isEqualByComparingTo("3900.00");
    }

    @Test
    void executeTrade_WhenBuyBalanceExactlySufficient_ShouldSpendAllUsdt() throws Exception {
        usdtWallet.setBalance(new BigDecimal("6100.00"));
        setUpDB(pair, ethWallet);
        TradeResponse result = tradeService.executeTrade(
                new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT"));
        assertThat(result.getTotalAmount()).isEqualByComparingTo("6100.00");
        assertWalletBalance(3L, "0");
        assertWalletBalance(2L, "3");
    }

    @Test
    void executeTrade_WhenSellBalanceExactlySufficient_ShouldSellAllBtc() throws Exception {
        setUpDB(btcPair, btcWallet);
        TradeResponse result = tradeService.executeTrade(
                new TradeRequest(1L, TradeType.SELL, new BigDecimal("1.00"), "BTCUSDT"));
        assertThat(result.getTotalAmount()).isEqualByComparingTo("50000.00");
        assertWalletBalance(1L, "0");
        assertWalletBalance(3L, "60000.00");
    }

    @Test
    void executeTrade_WhenBuySpendingWalletMissing_ShouldRejectWithoutWrites() {
        mockTradeValidationDependencies(pair, ethWallet);
        when(userWalletMapper.findByUserId(1L)).thenReturn(List.of(ethWallet));
        assertThatThrownBy(() -> tradeService.executeTrade(
                new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Insufficient quote balance for trade. quoteBalance=0 quantity2.00");
        assertNoTradeWrites();
    }

    @Test
    void executeTrade_WhenSellSpendingWalletMissing_ShouldRejectWithoutWrites() {
        mockTradeValidationDependencies(btcPair, btcWallet);
        when(userWalletMapper.findByUserId(1L)).thenReturn(List.of(usdtWallet));
        assertThatThrownBy(() -> tradeService.executeTrade(
                new TradeRequest(1L, TradeType.SELL, new BigDecimal("0.25"), "BTCUSDT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Insufficient base balance for trade. baseBalance=0 quantity=0.25");
        assertNoTradeWrites();
    }

    @Test
    void tradeType_WhenMalformed_ShouldRejectDeserialization() {
        assertThatThrownBy(() -> new ObjectMapper().readValue("\"HOLD\"", TradeType.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("HOLD");
        verifyNoInteractions(userMapper, cryptoPairMapper, cryptoPriceMapper, userWalletMapper, tradeMapper);
    }

    @ParameterizedTest
    @ValueSource(longs = {3L, 2L})
    void executeTrade_WhenWalletUpdateFails_ShouldPropagateException(long failingSymbolId) {
        setUpDB(pair, ethWallet);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Wallet update failed");
        if (failingSymbolId == 2L) {
            doNothing().when(userWalletMapper).upsertBalance(eq(1L), eq(3L), any());
        }
        doThrow(failure).when(userWalletMapper).upsertBalance(eq(1L), eq(failingSymbolId), any());
        assertThatThrownBy(() -> tradeService.executeTrade(
                new TradeRequest(1L, TradeType.BUY, new BigDecimal("2.00"), "ETHUSDT")))
                .isSameAs(failure).hasMessage("Wallet update failed");
        verify(tradeMapper).insertTrade(any(Trade.class));
        verify(userWalletMapper).upsertBalance(eq(1L), eq(failingSymbolId), any());
        if (failingSymbolId == 3L) {
            verify(userWalletMapper, never()).upsertBalance(eq(1L), eq(2L), any());
        } else {
            verify(userWalletMapper).upsertBalance(eq(1L), eq(3L), any());
        }
    }

    @Test
    void executeTrade_WhenQuantityFractional_ShouldPreserveDecimalAmounts() throws Exception {
        setUpDB(pair, ethWallet);
        TradeResponse result = tradeService.executeTrade(
                new TradeRequest(1L, TradeType.BUY, new BigDecimal("0.12345678"), "ETHUSDT"));
        assertThat(result.getQuantity()).isEqualByComparingTo("0.12345678");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("376.54317900");
        ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeMapper).insertTrade(captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo("376.54317900");
        assertWalletBalance(3L, "9623.45682100");
        assertWalletBalance(2L, "1.12345678");
    }

    @Test
    void executeTrade_WhenPriceListEmpty_ShouldRejectWithoutWrites() {
        when(userMapper.existsByUserId(1L)).thenReturn(true);
        when(cryptoPairMapper.findByPairName("ETHUSDT")).thenReturn(pair);
        when(cryptoPriceMapper.findLatestPrices()).thenReturn(List.of());
        assertThatThrownBy(() -> tradeService.executeTrade(
                new TradeRequest(1L, TradeType.BUY, BigDecimal.ONE, "ETHUSDT")))
                .isInstanceOf(InternalError.class)
                .hasMessage("Crypto price not found for crypto pair. cryptoPairId=2 cryptoPair=ETHUSDT");
        verifyNoInteractions(userWalletMapper, tradeMapper);
    }

    private void assertWalletBalance(Long symbolId, String expected) {
        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userWalletMapper).upsertBalance(eq(1L), eq(symbolId), captor.capture());
        assertThat(captor.getValue()).isEqualByComparingTo(expected);
    }

    private void assertNoTradeWrites() {
        verifyNoInteractions(tradeMapper);
        verify(userWalletMapper, never()).upsertBalance(any(), any(), any());
    }

    private void setUpDB(CryptoPair cryptoPair, UserWalletDto baseWallet) {
        mockTradeValidationDependencies(cryptoPair, baseWallet);
        doAnswer(invocation -> {
            Trade trade = invocation.getArgument(0);
            trade.setId(42L);
            return null;
        }).when(tradeMapper).insertTrade(any(Trade.class));
    }

    private void mockTradeValidationDependencies(CryptoPair cryptoPair, UserWalletDto baseWallet) {
        when(userMapper.existsByUserId(1L)).thenReturn(true);
        when(cryptoPairMapper.findByPairName(cryptoPair.getPairName())).thenReturn(cryptoPair);
        when(cryptoPriceMapper.findLatestPrices()).thenReturn(List.of(btcPrice, ethPrice));
        when(userWalletMapper.findByUserId(1L)).thenReturn(List.of(usdtWallet, baseWallet));
    }
}
