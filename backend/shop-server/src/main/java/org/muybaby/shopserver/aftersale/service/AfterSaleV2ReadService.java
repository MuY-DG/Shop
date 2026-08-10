package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.dto.AfterSaleItemResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleReturnResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AfterSaleV2ReadService {

    private final JdbcClient jdbcClient;

    public AfterSaleV2ReadService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean isV2(long afterSaleId) {
        Integer version = jdbcClient.sql("select flow_version from after_sale_request where id = :id")
                .param("id", afterSaleId)
                .query(Integer.class)
                .optional()
                .orElse(1);
        return version >= 2;
    }

    public AfterSaleResponse decorate(AfterSaleResponse base) {
        return decorateAll(List.of(base)).getFirst();
    }

    public List<AfterSaleResponse> decorateAll(List<AfterSaleResponse> bases) {
        if (bases.isEmpty()) {
            return List.of();
        }
        List<Long> afterSaleIds = bases.stream()
                .map(AfterSaleResponse::id)
                .toList();
        Map<Long, Integer> flowVersions = flowVersions(afterSaleIds);
        List<Long> v2AfterSaleIds = bases.stream()
                .filter(base -> flowVersions.getOrDefault(base.id(), 1) >= 2)
                .map(AfterSaleResponse::id)
                .toList();
        List<Long> legacyOrderIds = bases.stream()
                .filter(base -> flowVersions.getOrDefault(base.id(), 1) < 2)
                .map(AfterSaleResponse::orderId)
                .distinct()
                .toList();
        Map<Long, List<AfterSaleItemResponse>> v2Items = v2Items(v2AfterSaleIds);
        Map<Long, List<LegacyItem>> legacyItems = legacyItems(legacyOrderIds);
        Map<Long, AfterSaleReturnResponse> returns = returnInfos(afterSaleIds);

        return bases.stream()
                .map(base -> {
                    int flowVersion = flowVersions.getOrDefault(base.id(), 1);
                    List<AfterSaleItemResponse> items = flowVersion >= 2
                            ? v2Items.getOrDefault(base.id(), List.of())
                            : legacyItems(
                                    legacyItems.getOrDefault(base.orderId(), List.of()),
                                    base.requestedAmountCent());
                    return new AfterSaleResponse(
                            base.id(), base.afterSaleNo(), base.orderId(), base.orderNo(),
                            base.userId(), base.userNickname(), base.afterSaleType(), base.status(),
                            base.reason(), base.description(), base.requestedAmountCent(),
                            base.approvedAmountCent(), base.auditNote(), base.reviewedBy(),
                            base.reviewedAt(), base.createdAt(), base.evidenceFileIds(),
                            base.evidenceFiles(), base.refundOrder(), flowVersion, flowVersion < 2,
                            items, returns.get(base.id()), allowedActions(base.status()));
                })
                .toList();
    }

    private Map<Long, Integer> flowVersions(List<Long> afterSaleIds) {
        Map<Long, Integer> versions = new HashMap<>();
        jdbcClient.sql("""
                        select id, flow_version
                        from after_sale_request
                        where id in (:afterSaleIds)
                        """)
                .param("afterSaleIds", afterSaleIds)
                .query((rs, rowNum) -> new FlowVersionRow(
                        rs.getLong("id"), rs.getInt("flow_version")))
                .list()
                .forEach(row -> versions.put(row.afterSaleId(), row.flowVersion()));
        return versions;
    }

    private Map<Long, List<AfterSaleItemResponse>> v2Items(List<Long> afterSaleIds) {
        if (afterSaleIds.isEmpty()) {
            return Map.of();
        }
        List<V2ItemRow> rows = jdbcClient.sql("""
                        select asi.after_sale_id, asi.id, asi.order_item_id, asi.sku_id,
                               oi.product_title, oi.spec_text,
                               case when oi.display_image <> '' then oi.display_image
                                    when oi.sku_image <> '' then oi.sku_image
                                    else oi.main_image end as image,
                               asi.requested_quantity, asi.approved_quantity,
                               asi.requested_amount_cent, asi.approved_amount_cent,
                               asi.restock_quantity
                        from after_sale_item asi
                        join order_item oi on oi.id = asi.order_item_id
                        where asi.after_sale_id in (:afterSaleIds)
                        order by asi.after_sale_id, asi.id
                        """)
                .param("afterSaleIds", afterSaleIds)
                .query((rs, rowNum) -> new V2ItemRow(
                        rs.getLong("after_sale_id"), mapItem(rs, rowNum)))
                .list();
        Map<Long, List<AfterSaleItemResponse>> items = new HashMap<>();
        for (V2ItemRow row : rows) {
            items.computeIfAbsent(row.afterSaleId(), ignored -> new ArrayList<>())
                    .add(row.item());
        }
        return items;
    }

    private Map<Long, List<LegacyItem>> legacyItems(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<LegacyItemRow> rows = jdbcClient.sql("""
                        select order_id, id, sku_id, product_title, spec_text,
                               case when display_image <> '' then display_image
                                    when sku_image <> '' then sku_image
                                    else main_image end as image,
                               quantity,
                               coalesce(paid_amount_allocated_cent, line_amount_cent) as amount_cent
                        from order_item
                        where order_id in (:orderIds)
                        order by order_id, id
                        """)
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> new LegacyItemRow(
                        rs.getLong("order_id"),
                        new LegacyItem(
                                rs.getLong("id"), rs.getLong("sku_id"),
                                rs.getString("product_title"), rs.getString("spec_text"),
                                rs.getString("image"), rs.getInt("quantity"),
                                rs.getLong("amount_cent"))))
                .list();
        Map<Long, List<LegacyItem>> items = new HashMap<>();
        for (LegacyItemRow row : rows) {
            items.computeIfAbsent(row.orderId(), ignored -> new ArrayList<>())
                    .add(row.item());
        }
        return items;
    }

    private List<AfterSaleItemResponse> legacyItems(
            List<LegacyItem> rows,
            long requestedAmountCent
    ) {
        if (rows.isEmpty()) {
            return List.of();
        }
        long basisTotal = rows.stream().mapToLong(LegacyItem::amountCent).sum();
        List<AfterSaleItemResponse> result = new ArrayList<>(rows.size());
        long allocated = 0;
        for (int index = 0; index < rows.size(); index++) {
            LegacyItem row = rows.get(index);
            long amount = index == rows.size() - 1
                    ? Math.max(0, requestedAmountCent - allocated)
                    : basisTotal == 0 ? 0 : requestedAmountCent * row.amountCent() / basisTotal;
            allocated += amount;
            result.add(new AfterSaleItemResponse(
                    null, row.orderItemId(), row.skuId(), row.title(), row.specText(), row.image(),
                    row.quantity(), row.quantity(), amount, amount, row.quantity()));
        }
        return List.copyOf(result);
    }

    private Map<Long, AfterSaleReturnResponse> returnInfos(List<Long> afterSaleIds) {
        List<ReturnRow> rows = jdbcClient.sql("""
                        select r.after_sale_id, r.return_address_id, r.contact_name, r.contact_phone,
                               r.province, r.city, r.district, r.detail_address,
                               r.delivery_company_code, r.delivery_company_name, r.tracking_no,
                               a.return_deadline_at, r.user_shipped_at, r.merchant_received_at,
                               r.inspection_result, r.inspection_note, r.inspected_at
                        from after_sale_return r
                        join after_sale_request a on a.id = r.after_sale_id
                        where r.after_sale_id in (:afterSaleIds)
                        """)
                .param("afterSaleIds", afterSaleIds)
                .query((rs, rowNum) -> new ReturnRow(
                        rs.getLong("after_sale_id"),
                        new AfterSaleReturnResponse(
                                rs.getLong("return_address_id"), rs.getString("contact_name"),
                                rs.getString("contact_phone"), rs.getString("province"),
                                rs.getString("city"), rs.getString("district"),
                                rs.getString("detail_address"), rs.getString("delivery_company_code"),
                                rs.getString("delivery_company_name"), rs.getString("tracking_no"),
                                rs.getObject("return_deadline_at", LocalDateTime.class),
                                rs.getObject("user_shipped_at", LocalDateTime.class),
                                rs.getObject("merchant_received_at", LocalDateTime.class),
                                rs.getString("inspection_result"), rs.getString("inspection_note"),
                                rs.getObject("inspected_at", LocalDateTime.class))))
                .list();
        Map<Long, AfterSaleReturnResponse> returns = new HashMap<>();
        rows.forEach(row -> returns.put(row.afterSaleId(), row.returnInfo()));
        return returns;
    }

    private List<String> allowedActions(String status) {
        if (AfterSaleStatus.REQUESTED.name().equals(status)) {
            return List.of("CANCEL");
        }
        if (AfterSaleStatus.WAITING_RETURN.name().equals(status)) {
            return List.of("CANCEL", "SUBMIT_RETURN_SHIPMENT");
        }
        if (AfterSaleStatus.RETURNING.name().equals(status)) {
            return List.of("UPDATE_RETURN_SHIPMENT");
        }
        return List.of();
    }

    private AfterSaleItemResponse mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new AfterSaleItemResponse(
                rs.getLong("id"), rs.getLong("order_item_id"), rs.getLong("sku_id"),
                rs.getString("product_title"), rs.getString("spec_text"), rs.getString("image"),
                rs.getInt("requested_quantity"), rs.getObject("approved_quantity", Integer.class),
                rs.getLong("requested_amount_cent"),
                rs.getObject("approved_amount_cent", Long.class), rs.getInt("restock_quantity"));
    }

    private record LegacyItem(
            Long orderItemId,
            Long skuId,
            String title,
            String specText,
            String image,
            int quantity,
            long amountCent
    ) {
    }

    private record FlowVersionRow(long afterSaleId, int flowVersion) {
    }

    private record V2ItemRow(long afterSaleId, AfterSaleItemResponse item) {
    }

    private record LegacyItemRow(long orderId, LegacyItem item) {
    }

    private record ReturnRow(long afterSaleId, AfterSaleReturnResponse returnInfo) {
    }
}
