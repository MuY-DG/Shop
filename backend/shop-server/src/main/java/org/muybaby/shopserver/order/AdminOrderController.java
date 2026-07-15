package org.muybaby.shopserver.order;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.order.dto.AdminOrderQueryRequest;
import org.muybaby.shopserver.order.dto.AdminOrderSummaryResponse;
import org.muybaby.shopserver.order.dto.AdminOrderStatusCountsResponse;
import org.muybaby.shopserver.order.dto.OrderDetailResponse;
import org.muybaby.shopserver.order.dto.OrderStatusLogResponse;
import org.muybaby.shopserver.order.service.AdminOrderService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminOrderSummaryResponse>> page(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            AdminOrderQueryRequest query
    ) {
        return ApiResponse.success(adminOrderService.page(principal, query));
    }

    @GetMapping("/status-counts")
    public ApiResponse<AdminOrderStatusCountsResponse> statusCounts(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            AdminOrderQueryRequest query
    ) {
        return ApiResponse.success(adminOrderService.statusCounts(principal, query));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> detail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(adminOrderService.detail(principal, orderId));
    }

    @GetMapping("/{orderId}/status-logs")
    public ApiResponse<List<OrderStatusLogResponse>> statusLogs(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(adminOrderService.statusLogs(principal, orderId));
    }

    @PostMapping("/{orderId}/close")
    public ApiResponse<Map<String, Object>> close(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        adminOrderService.closeCreatedOrder(principal, orderId);
        return ApiResponse.success(Map.of());
    }
}
