package org.muybaby.shopserver.user.address.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.muybaby.shopserver.user.address.entity.UserAddress;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddress> {

    @Select("""
            SELECT id, user_id, receiver_name, receiver_phone, province, city, district,
                   detail_address, location_name, doorplate, is_default, created_at, updated_at
            FROM user_address
            WHERE user_id = #{userId}
            ORDER BY is_default DESC, created_at DESC, id DESC
            """)
    List<UserAddress> selectByUserId(@Param("userId") long userId);

    @Select("""
            SELECT id, user_id, receiver_name, receiver_phone, province, city, district,
                   detail_address, location_name, doorplate, is_default, created_at, updated_at
            FROM user_address
            WHERE id = #{addressId}
              AND user_id = #{userId}
            """)
    UserAddress selectOwned(
            @Param("userId") long userId,
            @Param("addressId") long addressId
    );

    @Select("""
            SELECT id, user_id, receiver_name, receiver_phone, province, city, district,
                   detail_address, location_name, doorplate, is_default, created_at, updated_at
            FROM user_address
            WHERE user_id = #{userId}
            ORDER BY created_at ASC, id ASC
            FOR UPDATE
            """)
    List<UserAddress> selectByUserIdForUpdate(@Param("userId") long userId);

    @Update("""
            UPDATE user_address
            SET is_default = FALSE,
                updated_at = #{updatedAt}
            WHERE user_id = #{userId}
              AND is_default = TRUE
            """)
    int clearDefaults(
            @Param("userId") long userId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Delete("""
            DELETE FROM user_address
            WHERE id = #{addressId}
              AND user_id = #{userId}
            """)
    int deleteOwned(
            @Param("userId") long userId,
            @Param("addressId") long addressId
    );
}
