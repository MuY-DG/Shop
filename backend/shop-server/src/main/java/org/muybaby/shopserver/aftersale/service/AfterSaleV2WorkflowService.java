package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.AfterSaleType;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleAuditRequest;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleItemApprovalRequest;
import org.muybaby.shopserver.aftersale.dto.AdminReturnInspectionItemRequest;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AfterSaleV2WorkflowService {

    private static final Duration DEFAULT_RETURN_WINDOW = Duration.ofDays(7);

    private final JdbcClient jdbcClient;
    private final MerchantReturnAddressService returnAddressService;
    private final AfterSaleStatusLogService statusLogService;
    private final OrderStatusLogService orderStatusLogService;

    public AfterSaleV2WorkflowService(
            JdbcClient jdbcClient,
            MerchantReturnAddressService returnAddressService,
            AfterSaleStatusLogService statusLogService,
            OrderStatusLogService orderStatusLogService
    ) {
        this.jdbcClient = jdbcClient;
        this.returnAddressService = returnAddressService;
        this.statusLogService = statusLogService;
        this.orderStatusLogService = orderStatusLogService;
    }

    public String type(long afterSaleId) {
        return jdbcClient.sql("select after_sale_type from after_sale_request where id = :id")
                .param("id", afterSaleId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    public ApprovalPlan previewApproval(long afterSaleId, AdminAfterSaleAuditRequest request) {
        return approvalPlan(afterSaleId, request, false);
    }

    @Transactional
    public void approveReturn(
            long adminUserId,
            long afterSaleId,
            AdminAfterSaleAuditRequest request
    ) {
        Route route = route(afterSaleId);
        OrderState order = lockOrder(route.orderId());
        AfterSaleState afterSale = lockAfterSale(afterSaleId);
        if (!AfterSaleType.RETURN_REFUND.name().equals(afterSale.type())
                || !AfterSaleStatus.REQUESTED.name().equals(afterSale.status())
                || request == null || request.returnAddressId() == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        ApprovalPlan plan = approvalPlan(afterSaleId, request, true);
        MerchantReturnAddressService.AddressSnapshot address =
                returnAddressService.requireEnabled(request.returnAddressId());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime deadline = now.plus(DEFAULT_RETURN_WINDOW);
        updateApprovedItems(afterSaleId, plan, false, now);
        jdbcClient.sql("""
                        insert into after_sale_return (
                            after_sale_id, return_address_id, contact_name, contact_phone,
                            province, city, district, detail_address, created_at, updated_at
                        ) values (
                            :afterSaleId, :addressId, :contactName, :contactPhone,
                            :province, :city, :district, :detailAddress, :now, :now
                        )
                        """)
                .param("afterSaleId", afterSaleId)
                .param("addressId", address.id())
                .param("contactName", address.contactName())
                .param("contactPhone", address.contactPhone())
                .param("province", address.province())
                .param("city", address.city())
                .param("district", address.district())
                .param("detailAddress", address.detailAddress())
                .param("now", now)
                .update();
        int updated = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            approved_amount_cent = :approvedAmount,
                            audit_note = :auditNote,
                            reviewed_by = :reviewedBy,
                            reviewed_at = :now,
                            return_deadline_at = :deadline,
                            version = version + 1,
                            updated_at = :now
                        where id = :id and status = :expected
                        """)
                .param("status", AfterSaleStatus.WAITING_RETURN.name())
                .param("approvedAmount", plan.approvedAmountCent())
                .param("auditNote", note(request.auditNote(), false))
                .param("reviewedBy", adminUserId)
                .param("now", now)
                .param("deadline", deadline)
                .param("id", afterSaleId)
                .param("expected", AfterSaleStatus.REQUESTED.name())
                .update();
        requireOne(updated);
        record(order, afterSaleId, AfterSaleStatus.REQUESTED.name(),
                AfterSaleStatus.WAITING_RETURN.name(), "RETURN_AUTHORIZED",
                adminUserId, "审核通过，等待用户寄回商品", now);
    }

    public ApprovalPlan applyRefundOnlyApprovalLocked(
            long afterSaleId,
            AdminAfterSaleAuditRequest request,
            boolean restockRequired,
            LocalDateTime now
    ) {
        ApprovalPlan plan = approvalPlan(afterSaleId, request, true);
        updateApprovedItems(afterSaleId, plan, restockRequired, now);
        return plan;
    }

    public ApprovalPlan applyInspectionAcceptanceLocked(
            long afterSaleId,
            List<AdminReturnInspectionItemRequest> inspectionItems,
            String note,
            long adminUserId,
            LocalDateTime now
    ) {
        List<ApprovalItem> approvedItems = approvalItems(afterSaleId, true);
        Map<Long, Integer> requestedRestocks = new LinkedHashMap<>();
        if (inspectionItems != null) {
            for (AdminReturnInspectionItemRequest item : inspectionItems) {
                if (item == null || item.orderItemId() == null || item.restockQuantity() == null
                        || item.restockQuantity() < 0
                        || requestedRestocks.putIfAbsent(item.orderItemId(), item.restockQuantity()) != null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
            }
        }
        boolean explicitRestocks = !requestedRestocks.isEmpty();
        long amount = 0;
        for (ApprovalItem item : approvedItems) {
            int restock = explicitRestocks
                    ? requestedRestocks.getOrDefault(item.orderItemId(), 0)
                    : item.approvedQuantity();
            if (restock > item.approvedQuantity()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            requireOne(jdbcClient.sql("""
                            update after_sale_item
                            set restock_quantity = :restockQuantity, updated_at = :now
                            where id = :id
                            """)
                    .param("restockQuantity", restock)
                    .param("now", now)
                    .param("id", item.id())
                    .update());
            amount = Math.addExact(amount, item.approvedAmountCent());
            requestedRestocks.remove(item.orderItemId());
        }
        if (!requestedRestocks.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int returnRows = jdbcClient.sql("""
                        update after_sale_return
                        set inspection_result = 'ACCEPTED', inspection_note = :note,
                            inspected_by = :adminId, inspected_at = :now,
                            version = version + 1, updated_at = :now
                        where after_sale_id = :afterSaleId
                          and merchant_received_at is not null
                          and inspection_result = ''
                        """)
                .param("note", note(note, false))
                .param("adminId", adminUserId)
                .param("now", now)
                .param("afterSaleId", afterSaleId)
                .update();
        requireOne(returnRows);
        return new ApprovalPlan(amount, approvedItems.stream()
                .map(item -> new PlannedItem(
                        item.id(), item.orderItemId(), item.approvedQuantity(), item.approvedAmountCent()))
                .toList());
    }

    @Transactional
    public void receiveReturn(long adminUserId, long afterSaleId, String note) {
        Route route = route(afterSaleId);
        OrderState order = lockOrder(route.orderId());
        AfterSaleState afterSale = lockAfterSale(afterSaleId);
        if (!AfterSaleStatus.RETURNING.name().equals(afterSale.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        requireOne(jdbcClient.sql("""
                        update after_sale_return
                        set merchant_received_at = :now, version = version + 1, updated_at = :now
                        where after_sale_id = :id and user_shipped_at is not null
                          and merchant_received_at is null
                        """)
                .param("now", now)
                .param("id", afterSaleId)
                .update());
        requireOne(jdbcClient.sql("""
                        update after_sale_request
                        set status = :status, version = version + 1, updated_at = :now
                        where id = :id and status = :expected
                        """)
                .param("status", AfterSaleStatus.WAITING_INSPECTION.name())
                .param("now", now)
                .param("id", afterSaleId)
                .param("expected", AfterSaleStatus.RETURNING.name())
                .update());
        record(order, afterSaleId, afterSale.status(), AfterSaleStatus.WAITING_INSPECTION.name(),
                "RETURN_RECEIVED", adminUserId,
                StringUtils.hasText(note) ? "商家确认收到退货：" + note.trim() : "商家确认收到退货", now);
    }

    @Transactional
    public void rejectInspection(long adminUserId, long afterSaleId, String note) {
        Route route = route(afterSaleId);
        OrderState order = lockOrder(route.orderId());
        AfterSaleState afterSale = lockAfterSale(afterSaleId);
        if (!AfterSaleStatus.WAITING_INSPECTION.name().equals(afterSale.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        String rejection = note(note, true);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        requireOne(jdbcClient.sql("""
                        update after_sale_return
                        set inspection_result = 'REJECTED', inspection_note = :note,
                            inspected_by = :adminId, inspected_at = :now,
                            version = version + 1, updated_at = :now
                        where after_sale_id = :id and inspection_result = ''
                        """)
                .param("note", rejection)
                .param("adminId", adminUserId)
                .param("now", now)
                .param("id", afterSaleId)
                .update());
        requireOne(jdbcClient.sql("""
                        update after_sale_request
                        set status = :status, audit_note = :note,
                            version = version + 1, updated_at = :now
                        where id = :id and status = :expected
                        """)
                .param("status", AfterSaleStatus.RETURN_REJECTED.name())
                .param("note", rejection)
                .param("now", now)
                .param("id", afterSaleId)
                .param("expected", AfterSaleStatus.WAITING_INSPECTION.name())
                .update());
        record(order, afterSaleId, afterSale.status(), AfterSaleStatus.RETURN_REJECTED.name(),
                "RETURN_INSPECTION_REJECTED", adminUserId, "退货验收拒绝：" + rejection, now);
    }

    private ApprovalPlan approvalPlan(
            long afterSaleId,
            AdminAfterSaleAuditRequest request,
            boolean forUpdate
    ) {
        List<ApprovalItem> items = approvalItems(afterSaleId, forUpdate);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        List<AdminAfterSaleItemApprovalRequest> requests =
                request == null ? null : request.items();
        if (requests != null && !requests.isEmpty()) {
            for (AdminAfterSaleItemApprovalRequest itemRequest : requests) {
                if (itemRequest == null || itemRequest.orderItemId() == null
                        || itemRequest.approvedQuantity() == null
                        || itemRequest.approvedQuantity() < 0
                        || quantities.putIfAbsent(
                        itemRequest.orderItemId(), itemRequest.approvedQuantity()) != null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
            }
        }
        boolean explicitQuantities = !quantities.isEmpty();
        List<PlannedItem> result = new java.util.ArrayList<>();
        long amount = 0;
        for (ApprovalItem item : items) {
            if (item.currentRefundedQuantity() != item.refundedQuantityBefore()) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            int approved = explicitQuantities
                    ? quantities.getOrDefault(item.orderItemId(), 0)
                    : item.requestedQuantity();
            if (approved < 0 || approved > item.requestedQuantity()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            quantities.remove(item.orderItemId());
            if (approved == 0) {
                continue;
            }
            long allocatedAmount = AfterSaleAmountAllocator.tranche(
                    item.paidAmountBasisCent(), item.orderQuantitySnapshot(),
                    item.refundedQuantityBefore(), approved);
            if (item.requestedAmountCent() <= 0) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            long approvedAmount = Math.min(allocatedAmount, item.requestedAmountCent());
            amount = Math.addExact(amount, approvedAmount);
            result.add(new PlannedItem(item.id(), item.orderItemId(), approved, approvedAmount));
        }
        if (!quantities.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (amount <= 0 || result.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request != null && request.approvedAmountCent() != null
                && request.approvedAmountCent() != amount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new ApprovalPlan(amount, List.copyOf(result));
    }

    private List<ApprovalItem> approvalItems(long afterSaleId, boolean forUpdate) {
        String lock = forUpdate ? " for update" : "";
        return jdbcClient.sql("""
                        select asi.id, asi.order_item_id, asi.order_quantity_snapshot,
                               asi.paid_amount_basis_cent, asi.refunded_quantity_before,
                               asi.requested_quantity, asi.requested_amount_cent,
                               asi.approved_quantity,
                               asi.approved_amount_cent, oi.refunded_quantity
                        from after_sale_item asi
                        join order_item oi on oi.id = asi.order_item_id
                        where asi.after_sale_id = :afterSaleId
                        order by asi.id
                        """ + lock)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new ApprovalItem(
                        rs.getLong("id"), rs.getLong("order_item_id"),
                        rs.getInt("order_quantity_snapshot"),
                        rs.getLong("paid_amount_basis_cent"),
                        rs.getInt("refunded_quantity_before"),
                        rs.getInt("requested_quantity"),
                        rs.getLong("requested_amount_cent"),
                        rs.getObject("approved_quantity", Integer.class),
                        rs.getObject("approved_amount_cent", Long.class),
                        rs.getInt("refunded_quantity")))
                .list();
    }

    private void updateApprovedItems(
            long afterSaleId,
            ApprovalPlan plan,
            boolean restockRequired,
            LocalDateTime now
    ) {
        Map<Long, PlannedItem> byId = new LinkedHashMap<>();
        for (PlannedItem item : plan.items()) {
            byId.put(item.afterSaleItemId(), item);
        }
        for (ApprovalItem current : approvalItems(afterSaleId, true)) {
            PlannedItem approved = byId.get(current.id());
            int approvedQuantity = approved == null ? 0 : approved.approvedQuantity();
            Long approvedAmount = approved == null ? 0L : approved.approvedAmountCent();
            requireOne(jdbcClient.sql("""
                            update after_sale_item
                            set approved_quantity = :approvedQuantity,
                                approved_amount_cent = :approvedAmount,
                                restock_quantity = :restockQuantity,
                                updated_at = :now
                            where id = :id
                            """)
                    .param("approvedQuantity", approvedQuantity)
                    .param("approvedAmount", approvedAmount)
                    .param("restockQuantity", restockRequired ? approvedQuantity : 0)
                    .param("now", now)
                    .param("id", current.id())
                    .update());
        }
    }

    private Route route(long afterSaleId) {
        return jdbcClient.sql("select id, order_id from after_sale_request where id = :id")
                .param("id", afterSaleId)
                .query((rs, rowNum) -> new Route(rs.getLong("id"), rs.getLong("order_id")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private OrderState lockOrder(long orderId) {
        return jdbcClient.sql("""
                        select id, status from shop_order where id = :id for update
                        """)
                .param("id", orderId)
                .query((rs, rowNum) -> new OrderState(rs.getLong("id"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private AfterSaleState lockAfterSale(long afterSaleId) {
        return jdbcClient.sql("""
                        select id, order_id, after_sale_type, status
                        from after_sale_request where id = :id for update
                        """)
                .param("id", afterSaleId)
                .query((rs, rowNum) -> new AfterSaleState(
                        rs.getLong("id"), rs.getLong("order_id"),
                        rs.getString("after_sale_type"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private void record(
            OrderState order,
            long afterSaleId,
            String from,
            String to,
            String event,
            long adminId,
            String description,
            LocalDateTime now
    ) {
        statusLogService.record(afterSaleId, from, to, event, "ADMIN", adminId, description, now);
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), order.status(),
                event, "ADMIN", adminId, description, now);
    }

    private String note(String value, boolean required) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 255 || required && !StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private void requireOne(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    public record ApprovalPlan(long approvedAmountCent, List<PlannedItem> items) {
    }

    public record PlannedItem(
            long afterSaleItemId,
            long orderItemId,
            int approvedQuantity,
            long approvedAmountCent
    ) {
    }

    private record ApprovalItem(
            long id,
            long orderItemId,
            int orderQuantitySnapshot,
            long paidAmountBasisCent,
            int refundedQuantityBefore,
            int requestedQuantity,
            long requestedAmountCent,
            Integer approvedQuantity,
            Long approvedAmountCent,
            int currentRefundedQuantity
    ) {
    }

    private record Route(long afterSaleId, long orderId) {
    }

    private record OrderState(long orderId, String status) {
    }

    private record AfterSaleState(
            long afterSaleId,
            long orderId,
            String type,
            String status
    ) {
    }
}
