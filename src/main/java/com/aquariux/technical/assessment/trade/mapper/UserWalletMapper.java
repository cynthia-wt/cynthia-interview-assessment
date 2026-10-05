package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface UserWalletMapper {
    
    @Select("""
            SELECT s.symbol, s.name, uw.balance, uw.symbol_id 
            FROM symbols s 
            INNER JOIN user_wallets uw ON s.id = uw.symbol_id AND uw.user_id = #{userId} 
            ORDER BY s.symbol
            """)
    List<UserWalletDto> findByUserId(Long userId);

    @Update("""
            MERGE INTO user_wallets (user_id, symbol_id, balance, updated_at)
            KEY (user_id, symbol_id)
            VALUES (#{userId}, #{symbolId}, #{newBalance}, CURRENT_TIMESTAMP)
            """)
    void upsertBalance(Long userId, Long symbolId, BigDecimal newBalance);
}