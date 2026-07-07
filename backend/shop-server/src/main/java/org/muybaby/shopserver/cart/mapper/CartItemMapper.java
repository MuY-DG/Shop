package org.muybaby.shopserver.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.cart.entity.CartItem;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
}
