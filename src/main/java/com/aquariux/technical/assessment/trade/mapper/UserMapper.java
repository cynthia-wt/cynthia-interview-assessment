package com.aquariux.technical.assessment.trade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    // Hold this lock within the trade transaction before reading wallet balances.
    @Select("""
            SELECT id FROM users WHERE id = #{userId} FOR UPDATE
            """)
    Long findUserIdForUpdate(@Param("userId") Long userId);
}
