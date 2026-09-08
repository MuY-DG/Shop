package org.muybaby.shopserver.customerservice;

import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleDetailResponse;
import org.muybaby.shopserver.aftersale.service.AdminAfterSaleService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.order.dto.OrderDetailResponse;
import org.muybaby.shopserver.order.service.AdminOrderService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only resource details scoped to an accessible conversation, including its message history. */
@RestController
@RequestMapping("/admin/customer-service/conversations/{conversationId}")
@PreAuthorize("hasAuthority('customer-service:conversation:read')")
public class CustomerServiceResourceController {
    private final CustomerServiceService customerService;
    private final AdminOrderService orders;
    private final AdminAfterSaleService afterSales;
    private final ProductReadMapper products;

    public CustomerServiceResourceController(CustomerServiceService customerService, AdminOrderService orders,
                                             AdminAfterSaleService afterSales, ProductReadMapper products) {
        this.customerService = customerService;
        this.orders = orders;
        this.afterSales = afterSales;
        this.products = products;
    }

    @GetMapping("/orders/{resourceId}/detail")
    public ApiResponse<OrderDetailResponse> order(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                 @PathVariable Long conversationId, @PathVariable Long resourceId) {
        customerService.requireResourceAccessForAdmin(principal, conversationId, "ORDER", resourceId);
        return ApiResponse.success(orders.detail(principal, resourceId));
    }

    @GetMapping("/after-sales/{resourceId}/detail")
    public ApiResponse<AdminAfterSaleDetailResponse> afterSale(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                             @PathVariable Long conversationId, @PathVariable Long resourceId) {
        customerService.requireResourceAccessForAdmin(principal, conversationId, "AFTER_SALE", resourceId);
        return ApiResponse.success(afterSales.detail(principal, resourceId));
    }

    @GetMapping("/after-sales/{resourceId}/evidence/{fileId}")
    public ResponseEntity<InputStreamResource> evidence(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                        @PathVariable Long conversationId, @PathVariable Long resourceId,
                                                        @PathVariable Long fileId) {
        customerService.requireResourceAccessForAdmin(principal, conversationId, "AFTER_SALE", resourceId);
        return afterSales.evidence(principal, resourceId, fileId);
    }

    @GetMapping("/products/{resourceId}/detail")
    public ApiResponse<ProductDetail> product(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                              @PathVariable Long conversationId, @PathVariable Long resourceId) {
        customerService.requireResourceAccessForAdmin(principal, conversationId, "PRODUCT", resourceId);
        var product = products.adminSpuDetail(resourceId);
        return ApiResponse.success(new ProductDetail(product.id(), product.title(), product.subtitle(),
                product.categoryName(), product.mainImage(), product.status(), product.sellingPoints(), product.detailHtml(),
                product.images().stream().map(image -> image.url()).toList(),
                product.skus().stream().map(sku -> new ProductVariant(sku.id(), sku.skuCode(), sku.specText(),
                        sku.image(), sku.priceCent(), sku.stockAvailable(), sku.status())).toList()));
    }

    public record ProductDetail(Long id, String title, String subtitle, String categoryName, String mainImage,
                                String status, String sellingPoints, String detailHtml, List<String> images,
                                List<ProductVariant> skus) {}

    public record ProductVariant(Long id, String skuCode, String specText, String image, Long priceCent,
                                 Integer stockAvailable, String status) {}
}
