package org.muybaby.shopserver.analytics;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.muybaby.shopserver.analytics.dto.AnalyticsEventBatchRequest;
import org.muybaby.shopserver.analytics.dto.AnalyticsEventBatchResponse;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/analytics")
public class AppAnalyticsController {

    private final AnalyticsEventService analyticsEventService;
    private final AnalyticsRateLimiter analyticsRateLimiter;

    public AppAnalyticsController(
            AnalyticsEventService analyticsEventService,
            AnalyticsRateLimiter analyticsRateLimiter
    ) {
        this.analyticsEventService = analyticsEventService;
        this.analyticsRateLimiter = analyticsRateLimiter;
    }

    @PostMapping("/events/batch")
    public ApiResponse<AnalyticsEventBatchResponse> accept(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AnalyticsEventBatchRequest request,
            HttpServletRequest servletRequest
    ) {
        analyticsRateLimiter.check(servletRequest, request.visitorId(), request.events().size());
        return ApiResponse.success(analyticsEventService.accept(principal, request));
    }
}
