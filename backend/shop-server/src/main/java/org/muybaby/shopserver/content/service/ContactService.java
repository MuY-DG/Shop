package org.muybaby.shopserver.content.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.content.dto.ContactResponse;
import org.muybaby.shopserver.content.dto.ContactUpdateRequest;
import org.muybaby.shopserver.content.entity.ContactSetting;
import org.muybaby.shopserver.content.mapper.ContactSettingMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
public class ContactService {

    private static final int CONTACT_SETTING_ID = 1;
    private static final Pattern PHONE_PATTERN = Pattern.compile("[0-9+()\\-\\s]{5,32}");

    private final ContactSettingMapper contactSettingMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ContactService(ContactSettingMapper contactSettingMapper, ApplicationEventPublisher eventPublisher) {
        this.contactSettingMapper = contactSettingMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public ContactResponse current() {
        return toResponse(requireSetting());
    }

    @Transactional
    public ContactResponse update(ContactUpdateRequest request) {
        String phone = request == null || request.phone() == null ? "" : request.phone().trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ContactSetting updatedSetting = new ContactSetting(CONTACT_SETTING_ID, phone, LocalDateTime.now(java.time.ZoneOffset.UTC));
        int updated = contactSettingMapper.updateById(updatedSetting);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        eventPublisher.publishEvent(PublicContentChangedEvent.contact());
        return toResponse(requireSetting());
    }

    private ContactSetting requireSetting() {
        ContactSetting setting = contactSettingMapper.selectById(CONTACT_SETTING_ID);
        if (setting == null) {
            throw new IllegalStateException("Contact setting row is missing");
        }
        return setting;
    }

    private ContactResponse toResponse(ContactSetting setting) {
        return new ContactResponse(setting.phoneNumber(), setting.updatedAt());
    }
}
