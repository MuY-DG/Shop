package org.muybaby.shopserver.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.muybaby.shopserver.user.entity.AppUser;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    @Select("""
            SELECT id, openid, unionid, phone_number, phone_country_code, phone_authorized,
                   status, last_login_at, created_at, updated_at
            FROM app_user
            WHERE id = #{userId}
            FOR UPDATE
            """)
    AppUser selectByIdForUpdate(@Param("userId") long userId);
}
