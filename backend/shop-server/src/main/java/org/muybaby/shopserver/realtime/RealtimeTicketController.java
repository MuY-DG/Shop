package org.muybaby.shopserver.realtime;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RealtimeTicketController {

    private final RealtimeTicketService realtimeTicketService;

    public RealtimeTicketController(RealtimeTicketService realtimeTicketService) {
        this.realtimeTicketService = realtimeTicketService;
    }

    @PostMapping({"/admin/realtime/tickets", "/app/realtime/tickets"})
    public ApiResponse<RealtimeTicketResponse> issue(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(realtimeTicketService.issue(principal));
    }
}
