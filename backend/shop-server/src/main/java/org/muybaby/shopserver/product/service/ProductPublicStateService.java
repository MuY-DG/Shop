package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.product.ProductSaleState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ProductPublicStateService {

    private final JdbcClient jdbcClient;

    public ProductPublicStateService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Map<Long, ProductSaleState> saleStates(List<Long> spuIds) {
        List<Long> normalizedIds = spuIds == null
                ? List.of()
                : spuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, ProductSaleState> states = new LinkedHashMap<>();
        normalizedIds.forEach(spuId -> states.put(spuId, ProductSaleState.SOLD_OUT));
        jdbcClient.sql("""
                        select s.id,
                               case when s.status = 'ON_SALE'
                                      and s.deleted_at is null
                                      and s.purged_at is null
                                      and c.status = 'ENABLED'
                                      and exists (
                                          select 1
                                          from product_sku k
                                          where k.spu_id = s.id
                                            and k.status = 'ENABLED'
                                            and k.deleted_at is null
                                            and k.stock_available > 0
                                      )
                                    then 1 else 0 end as is_available
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        where s.id in (:spuIds)
                        """)
                .param("spuIds", normalizedIds)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("id"),
                        rs.getInt("is_available") == 1
                                ? ProductSaleState.AVAILABLE
                                : ProductSaleState.SOLD_OUT
                ))
                .list()
                .forEach(entry -> states.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(states);
    }
}
