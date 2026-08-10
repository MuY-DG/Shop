package org.muybaby.shopserver.compliance;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.compliance.dto.LegalDocumentResponse;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationResponse;
import org.muybaby.shopserver.compliance.service.LegalDocumentService;
import org.muybaby.shopserver.compliance.service.MerchantComplianceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/compliance")
public class AppComplianceController {

    private final MerchantComplianceService merchantComplianceService;
    private final LegalDocumentService legalDocumentService;

    public AppComplianceController(
            MerchantComplianceService merchantComplianceService,
            LegalDocumentService legalDocumentService
    ) {
        this.merchantComplianceService = merchantComplianceService;
        this.legalDocumentService = legalDocumentService;
    }

    @GetMapping("/merchant")
    public ApiResponse<MerchantPublicationResponse> merchant() {
        return ApiResponse.success(merchantComplianceService.currentPublished());
    }

    @GetMapping("/documents/{type}/current")
    public ApiResponse<LegalDocumentResponse> currentDocument(@PathVariable LegalDocumentType type) {
        return ApiResponse.success(legalDocumentService.current(type));
    }
}
