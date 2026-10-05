package com.aquariux.technical.assessment.trade;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.coyote.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:trade-integration;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
class TradeApplicationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TradeServiceInterface tradeService;

    @BeforeEach
    void setUp() {
        // Dedicated test database; committed fixtures let worker transactions see them.
        jdbc.update("DELETE FROM trades");
        jdbc.update("DELETE FROM user_wallets");
        jdbc.update("DELETE FROM crypto_prices");
        jdbc.update("UPDATE crypto_pairs SET active = TRUE");
        jdbc.update("INSERT INTO user_wallets (user_id, symbol_id, balance) VALUES (1, 3, 10000)");
        jdbc.update("""
                INSERT INTO crypto_prices (crypto_pair_id, bid_price, ask_price, bid_source, ask_source)
                VALUES (1, 50000, 50100, 'BINANCE', 'HUOBI'),
                       (2, 3000, 3050, 'HUOBI', 'BINANCE')
                """);
    }

    @Test
    void buyEth_ShouldCreateWalletAndPersistTradeAndBalances() throws Exception {
        TradeResponse response = executeApi(TradeType.BUY, "2.00", "ETHUSDT");

        assertThat(response.getTradeId()).isNotNull();
        assertThat(response.getPrice()).isEqualByComparingTo("3050");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("6100");
        assertBalance(3L, "3900");
        assertBalance(2L, "2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_wallets WHERE user_id = 1 AND symbol_id = 2", Integer.class))
                .isEqualTo(1);
        assertSavedTrade(response, 2L, "BUY", "2", "3050", "6100");
    }

    @Test
    void sellBtc_ShouldPersistTradeAndUpdateBothWallets() throws Exception {
        jdbc.update("INSERT INTO user_wallets (user_id, symbol_id, balance) VALUES (1, 1, 1)");

        TradeResponse response = executeApi(TradeType.SELL, "0.25", "BTCUSDT");

        assertThat(response.getPrice()).isEqualByComparingTo("50000");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("12500");
        assertBalance(1L, "0.75");
        assertBalance(3L, "22500");
        assertSavedTrade(response, 1L, "SELL", "0.25", "50000", "12500");
    }

    @Test
    void rejectedTrade_ShouldLeaveDatabaseUnchanged() {
        assertThatThrownBy(() -> tradeService.executeTrade(
                new TradeRequest(1L, TradeType.BUY, new BigDecimal("4"), "ETHUSDT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Insufficient quote balance");

        assertBalance(3L, "10000");
        assertThat(tradeCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_wallets WHERE user_id = 1", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void secondWalletUpdateFailure_ShouldRollBackTradeAndFirstWalletUpdate() {
        jdbc.update("INSERT INTO user_wallets (user_id, symbol_id, balance) VALUES (1, 2, 1)");
        // Force the ETH credit to fail after the trade insert and USDT debit succeed.
        jdbc.execute("""
                ALTER TABLE user_wallets ADD CONSTRAINT test_eth_balance_limit
                CHECK (symbol_id <> 2 OR balance <= 1)
                """);
        try {
            assertThatThrownBy(() -> tradeService.executeTrade(
                    new TradeRequest(1L, TradeType.BUY, new BigDecimal("2"), "ETHUSDT")))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(tradeCount()).isZero();
            assertBalance(3L, "10000");
            assertBalance(2L, "1");
        } finally {
            jdbc.execute("ALTER TABLE user_wallets DROP CONSTRAINT test_eth_balance_limit");
        }
    }

    @Test
    void concurrentBuys_ShouldAllowOnlyOneTradeWhenCombinedCostExceedsBalance() throws Exception {
        // Each trade costs 6100 USDT. Both fit individually, but not together.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> executeConcurrentBuy(ready, start));
            Future<Boolean> second = executor.submit(() -> executeConcurrentBuy(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            boolean firstSucceeded = first.get(15, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(15, TimeUnit.SECONDS);
            assertThat(firstSucceeded ^ secondSucceeded).isTrue();
            assertThat(tradeCount()).isEqualTo(1);
            assertBalance(3L, "3900");
            assertBalance(2L, "2");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_wallets WHERE user_id = 1", Integer.class))
                    .isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "tradeType", "quantity", "cryptoPair", "zeroQuantity", "negativeQuantity", "blankPair"})
    void invalidRequest_ShouldReturn400WithoutDatabaseChanges(String invalidField) throws Exception {
        TradeRequest request = new TradeRequest(1L, TradeType.BUY, new BigDecimal("2"), "ETHUSDT");
        switch (invalidField) {
            case "userId" -> request.setUserId(null);
            case "tradeType" -> request.setTradeType(null);
            case "quantity" -> request.setQuantity(null);
            case "cryptoPair" -> request.setCryptoPair(null);
            case "zeroQuantity" -> request.setQuantity(BigDecimal.ZERO);
            case "negativeQuantity" -> request.setQuantity(new BigDecimal("-1"));
            case "blankPair" -> request.setCryptoPair("   ");
            default -> throw new IllegalArgumentException(invalidField);
        }
        mockMvc.perform(post("/api/trades/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertThat(tradeCount()).isZero();
        assertBalance(3L, "10000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_wallets WHERE user_id = 1", Integer.class))
                .isEqualTo(1);
    }

    private boolean executeConcurrentBuy(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent trade start timed out");
        }
        try {
            tradeService.executeTrade(new TradeRequest(1L, TradeType.BUY, new BigDecimal("2"), "ETHUSDT"));
            return true;
        } catch (BadRequestException exception) {
            assertThat(exception).hasMessageContaining("Insufficient quote balance");
            return false;
        }
    }

    private TradeResponse executeApi(TradeType type, String quantity, String pair) throws Exception {
        String json = mockMvc.perform(post("/api/trades/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TradeRequest(1L, type, new BigDecimal(quantity), pair))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, TradeResponse.class);
    }

    private void assertBalance(Long symbolId, String expected) {
        BigDecimal balance = jdbc.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = 1 AND symbol_id = ?",
                BigDecimal.class, symbolId);
        assertThat(balance).isEqualByComparingTo(expected);
    }

    private int tradeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE user_id = 1", Integer.class);
    }

    private void assertSavedTrade(TradeResponse response, Long pairId, String type,
                                  String quantity, String price, String total) {
        var saved = jdbc.queryForMap("SELECT * FROM trades WHERE id = ?", response.getTradeId());
        assertThat(saved.get("USER_ID")).isEqualTo(1L);
        assertThat(saved.get("CRYPTO_PAIR_ID")).isEqualTo(pairId);
        assertThat(saved.get("TRADE_TYPE")).isEqualTo(type);
        assertThat((BigDecimal) saved.get("QUANTITY")).isEqualByComparingTo(quantity);
        assertThat((BigDecimal) saved.get("PRICE")).isEqualByComparingTo(price);
        assertThat((BigDecimal) saved.get("TOTAL_AMOUNT")).isEqualByComparingTo(total);
        assertThat(saved.get("TRADE_TIME")).isNotNull();
        assertThat(tradeCount()).isEqualTo(1);
    }
}
