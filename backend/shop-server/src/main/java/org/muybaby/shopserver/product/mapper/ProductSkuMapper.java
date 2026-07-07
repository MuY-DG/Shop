package org.muybaby.shopserver.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.product.entity.ProductSku;

@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {
}
