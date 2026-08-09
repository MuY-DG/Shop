package org.muybaby.shopserver.logistics.waybill;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillAttemptResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillContextResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillCreateRequest;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillSandboxEventRequest;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/admin/orders/{orderId}/waybills")
public class AdminElectronicWaybillController {

    private static final MediaType LABEL_HTML = new MediaType(
            "text", "html", StandardCharsets.UTF_8
    );

    private final ElectronicWaybillService waybillService;

    public AdminElectronicWaybillController(ElectronicWaybillService waybillService) {
        this.waybillService = waybillService;
    }

    @GetMapping("/context")
    @PreAuthorize("hasAuthority('order:waybill:manage')")
    public ApiResponse<ElectronicWaybillContextResponse> context(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(waybillService.context(principal, orderId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('order:waybill:manage')")
    public ApiResponse<List<ElectronicWaybillAttemptResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(waybillService.list(principal, orderId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('order:waybill:manage')")
    public ApiResponse<ElectronicWaybillAttemptResponse> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody ElectronicWaybillCreateRequest request
    ) {
        return ApiResponse.success(waybillService.create(principal, orderId, request));
    }

    @PostMapping("/{waybillRecordId}/refresh")
    @PreAuthorize("hasAuthority('order:waybill:manage')")
    public ApiResponse<ElectronicWaybillAttemptResponse> refresh(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @PathVariable Long waybillRecordId
    ) {
        return ApiResponse.success(waybillService.refresh(principal, orderId, waybillRecordId));
    }

    @PostMapping("/{waybillRecordId}/cancel")
    @PreAuthorize("hasAuthority('order:waybill:manage')")
    public ApiResponse<ElectronicWaybillAttemptResponse> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @PathVariable Long waybillRecordId
    ) {
        return ApiResponse.success(waybillService.cancel(principal, orderId, waybillRecordId));
    }

    @GetMapping("/{waybillRecordId}/print")
    @PreAuthorize("hasAuthority('order:waybill:print')")
    public ResponseEntity<byte[]> print(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @PathVariable Long waybillRecordId,
            @RequestParam Integer printType
    ) {
        byte[] html = waybillService.print(
                principal, orderId, waybillRecordId, printType
        ).html();
        return ResponseEntity.ok()
                .contentType(LABEL_HTML)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(html);
    }

    @PostMapping("/{waybillRecordId}/sandbox-events")
    @PreAuthorize("hasAuthority('order:waybill:test')")
    public ApiResponse<ElectronicWaybillAttemptResponse> simulate(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @PathVariable Long waybillRecordId,
            @Valid @RequestBody ElectronicWaybillSandboxEventRequest request
    ) {
        return ApiResponse.success(waybillService.simulate(
                principal, orderId, waybillRecordId, request
        ));
    }
}
