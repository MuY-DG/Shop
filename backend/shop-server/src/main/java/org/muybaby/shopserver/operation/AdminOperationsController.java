package org.muybaby.shopserver.operation;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.MarketingStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.OverviewReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ProductStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ReportQuery;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ServiceStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TradeStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TrafficStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.UserStatisticsReport;
import org.muybaby.shopserver.operation.service.OperationsStatisticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/operations")
public class AdminOperationsController {

    private final OperationsStatisticsService statisticsService;

    public AdminOperationsController(OperationsStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('operation:overview:read')")
    public ApiResponse<OverviewReport> overview(ReportQuery query) {
        return ApiResponse.success(statisticsService.overview(query));
    }

    @GetMapping("/trade-statistics")
    @PreAuthorize("hasAuthority('operation:trade:read')")
    public ApiResponse<TradeStatisticsReport> tradeStatistics(ReportQuery query) {
        return ApiResponse.success(statisticsService.tradeStatistics(query));
    }

    @GetMapping("/product-statistics")
    @PreAuthorize("hasAuthority('operation:product:read')")
    public ApiResponse<ProductStatisticsReport> productStatistics(ReportQuery query) {
        return ApiResponse.success(statisticsService.productStatistics(query));
    }

    @GetMapping("/user-statistics")
    @PreAuthorize("hasAuthority('operation:user:read')")
    public ApiResponse<UserStatisticsReport> userStatistics(ReportQuery query) {
        return ApiResponse.success(statisticsService.userStatistics(query));
    }

    @GetMapping("/traffic-statistics")
    @PreAuthorize("hasAuthority('operation:traffic:read')")
    public ApiResponse<TrafficStatisticsReport> trafficStatistics(ReportQuery query) {
        return ApiResponse.success(statisticsService.trafficStatistics(query));
    }

    @GetMapping("/marketing-statistics")
    @PreAuthorize("hasAuthority('operation:marketing:read')")
    public ApiResponse<MarketingStatisticsReport> marketingStatistics(ReportQuery query) {
        return ApiResponse.success(statisticsService.marketingStatistics(query));
    }

    @GetMapping("/service-statistics")
    @PreAuthorize("hasAuthority('operation:service:read')")
    public ApiResponse<ServiceStatisticsReport> serviceStatistics(ReportQuery query) {
        return ApiResponse.success(statisticsService.serviceStatistics(query));
    }
}
