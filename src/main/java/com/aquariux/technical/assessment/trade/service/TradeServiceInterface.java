package com.aquariux.technical.assessment.trade.service;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import org.apache.coyote.BadRequestException;

public interface TradeServiceInterface {
    TradeResponse executeTrade(TradeRequest tradeRequest) throws BadRequestException;
}