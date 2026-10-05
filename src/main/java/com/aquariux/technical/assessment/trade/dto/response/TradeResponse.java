package com.aquariux.technical.assessment.trade.dto.response;

import com.aquariux.technical.assessment.trade.enums.TradeType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TradeResponse {
    private Long userId;
    private Long tradeId;
    private TradeType tradeType;
    private String cryptoPair;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal totalAmount;
}