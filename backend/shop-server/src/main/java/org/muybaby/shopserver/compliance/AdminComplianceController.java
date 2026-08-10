package org.muybaby.shopserver.compliance;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.compliance.dto.LegalDocumentDraftRequest;
import org.muybaby.shopserver.compliance.dto.LegalDocumentResponse;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationDraftRequest;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationResponse;
import org.muybaby.shopserver.compliance.service.LegalDocumentService;
import org.muybaby.shopserver.compliance.service.MerchantComplianceService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/compliance")
public class AdminComplianceController {

    private final MerchantComplianceService merchantComplianceService;
    private final LegalDocumentService legalDocumentService;

    public AdminComplianceController(
            MerchantComplianceService merchantComplianceService,
            LegalDocumentService legalDocumentService
    ) {
        this.merchantComplianceService = merchantComplianceService;
        this.legalDocumentService = legalDocumentService;
    }

    @GetMapping("/merchant")
    @PreAuthorize("hasAuthority('compliance:merchant:read')")
    public ApiResponse<List<MerchantPublicationResponse>> merchantHistory() {
        return ApiResponse.success(merchantComplianceService.history());
    }

    @PostMapping("/merchant/drafts")
    @PreAuthorize("hasAuthority('compliance:merchant:write')")
    public ApiResponse<MerchantPublicationResponse> createMerchantDraft(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody MerchantPublicationDraftRequest request
    ) {
        return ApiResponse.success(merchantComplianceService.createDraft(request, principal.subjectId()));
    }

    @PostMapping("/merchant/{id}/publish")
    @PreAuthorize("hasAuthority('compliance:merchant:write')")
    public ApiResponse<MerchantPublicationResponse> publishMerchant(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long id
    ) {
        return ApiResponse.success(merchantComplianceService.publish(id, principal.subjectId()));
    }

    @GetMapping("/documents/{type}")
    @PreAuthorize("hasAuthority('compliance:document:read')")
    public ApiResponse<List<LegalDocumentResponse>> documentHistory(@PathVariable LegalDocumentType type) {
        return ApiResponse.success(legalDocumentService.history(type));
    }

    @PostMapping("/documents/{type}/drafts")
    @PreAuthorize("hasAuthority('compliance:document:write')")
    public ApiResponse<LegalDocumentResponse> createDocumentDraft(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable LegalDocumentType type,
            @Valid @RequestBody LegalDocumentDraftRequest request
    ) {
        return ApiResponse.success(legalDocumentService.createDraft(type, request, principal.subjectId()));
    }

    @PostMapping("/documents/{id}/publish")
    @PreAuthorize("hasAuthority('compliance:document:write')")
    public ApiResponse<LegalDocumentResponse> publishDocument(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long id
    ) {
        return ApiResponse.success(legalDocumentService.publish(id, principal.subjectId()));
    }
}
