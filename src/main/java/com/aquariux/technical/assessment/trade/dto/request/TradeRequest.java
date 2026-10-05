package com.aquariux.technical.assessment.trade.dto.request;

import com.aquariux.technical.assessment.trade.enums.TradeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeRequest {

    @NotNull
    private Long userId;

    @NotNull
    private TradeType tradeType;

    @Positive
    @NotNull
    private BigDecimal quantity;

    @NotBlank
    @NotNull
    private String cryptoPair;
}
