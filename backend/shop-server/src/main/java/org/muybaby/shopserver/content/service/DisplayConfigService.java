package org.muybaby.shopserver.content.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.content.DisplayNameProvider;
import org.muybaby.shopserver.content.dto.AdminDisplayConfigResponse;
import org.muybaby.shopserver.content.dto.DisplayConfigUpdateRequest;
import org.muybaby.shopserver.content.repository.DisplayConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class DisplayConfigService implements DisplayNameProvider {
    private final DisplayConfigRepository repository;
    private final Clock clock;

    public DisplayConfigService(DisplayConfigRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminDisplayConfigResponse current() {
        var row = repository.current();
        return new AdminDisplayConfigResponse(row.displayName(), row.version(), row.updatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public String displayName() {
        return repository.current().displayName();
    }

    @Transactional
    public AdminDisplayConfigResponse update(DisplayConfigUpdateRequest request, Long adminId) {
        String name = request == null || request.displayName() == null ? "" : request.displayName().strip();
        if (name.isBlank() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)
                || request.version() == null || request.version() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!repository.update(name, request.version(), adminId, LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.DISPLAY_CONFIG_CONFLICT);
        }
        return current();
    }
}
