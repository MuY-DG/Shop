package org.muybaby.shopserver.content;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.ContactResponse;
import org.muybaby.shopserver.content.dto.ContactUpdateRequest;
import org.muybaby.shopserver.content.service.ContactService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/contact")
public class AdminContactController {

    private final ContactService contactService;

    public AdminContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('content:contact:read', 'content:contact:write')")
    public ApiResponse<ContactResponse> current() {
        return ApiResponse.success(contactService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('content:contact:write')")
    public ApiResponse<ContactResponse> update(@Valid @RequestBody ContactUpdateRequest request) {
        return ApiResponse.success(contactService.update(request));
    }
}
