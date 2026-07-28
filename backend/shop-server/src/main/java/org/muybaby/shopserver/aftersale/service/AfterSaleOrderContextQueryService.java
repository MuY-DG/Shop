package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.dto.AfterSaleOrderContextResponse;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.dto.OrderItemResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class AfterSaleOrderContextQueryService {

    private final JdbcClient jdbcClient;

    public AfterSaleOrderContextQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AfterSaleOrderContextResponse requireContext(Long orderId) {
        OrderContextHeader header = jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               receiver_name,
                               receiver_phone,
                               receiver_address,
                               product_amount_cent,
                               paid_amount_cent
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderContextHeader(
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getString("receiver_name"),
                        rs.getString("receiver_phone"),
                        rs.getString("receiver_address"),
                        rs.getLong("product_amount_cent"),
                        rs.getLong("paid_amount_cent")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        List<OrderItemResponse> items = jdbcClient.sql("""
                        select id as order_item_id,
                               sku_id,
                               spu_id,
                               product_title,
                               product_subtitle,
                               main_image,
                               main_image_file_id,
                               sku_image,
                               sku_image_file_id,
                               display_image,
                               display_image_file_id,
                               sku_code,
                               spec_text,
                               original_price_cent,
                               unit_price_cent,
                               retail_unit_price_cent,
                               wholesale_tier_min_quantity,
                               quantity,
                               line_original_amount_cent,
                               line_amount_cent
                        from order_item
                        where order_id = :orderId
                        order by id asc
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderItem)
                .list();

        return new AfterSaleOrderContextResponse(
                header.orderId(),
                header.orderNo(),
                header.receiverName(),
                header.receiverPhone(),
                header.receiverAddress(),
                header.productAmountCent(),
                header.paidAmountCent(),
                items.stream().mapToInt(OrderItemResponse::quantity).sum(),
                items
        );
    }

    private OrderItemResponse mapOrderItem(ResultSet rs, int rowNum) throws SQLException {
        return new OrderItemResponse(
                rs.getLong("order_item_id"),
                rs.getLong("sku_id"),
                rs.getLong("spu_id"),
                rs.getString("product_title"),
                rs.getString("product_subtitle"),
                rs.getString("main_image"),
                rs.getObject("main_image_file_id", Long.class),
                rs.getString("sku_image"),
                rs.getObject("sku_image_file_id", Long.class),
                rs.getString("display_image"),
                rs.getObject("display_image_file_id", Long.class),
                rs.getString("sku_code"),
                rs.getString("spec_text"),
                rs.getLong("original_price_cent"),
                rs.getLong("unit_price_cent"),
                rs.getLong("retail_unit_price_cent"),
                rs.getObject("wholesale_tier_min_quantity", Integer.class),
                rs.getInt("quantity"),
                rs.getLong("line_original_amount_cent"),
                rs.getLong("line_amount_cent")
        );
    }

    private record OrderContextHeader(
            Long orderId,
            String orderNo,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            Long productAmountCent,
            Long paidAmountCent
    ) {
    }
}
