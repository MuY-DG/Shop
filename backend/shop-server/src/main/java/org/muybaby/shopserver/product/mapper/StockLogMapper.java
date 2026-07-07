package org.muybaby.shopserver.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.product.entity.StockLog;

@Mapper
public interface StockLogMapper extends BaseMapper<StockLog> {
}
