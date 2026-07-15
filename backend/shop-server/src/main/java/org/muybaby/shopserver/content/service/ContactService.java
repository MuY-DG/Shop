package org.muybaby.shopserver.content.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.content.dto.ContactResponse;
import org.muybaby.shopserver.content.dto.ContactUpdateRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
public class ContactService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("[0-9+()\\-\\s]{5,32}");

    private final JdbcClient jdbcClient;
    private final ApplicationEventPublisher eventPublisher;

    public ContactService(JdbcClient jdbcClient, ApplicationEventPublisher eventPublisher) {
        this.jdbcClient = jdbcClient;
        this.eventPublisher = eventPublisher;
    }

    public ContactResponse current() {
        return jdbcClient.sql("""
                        select phone_number, updated_at
                        from app_contact_setting
                        where id = 1
                        """)
                .query((rs, rowNum) -> new ContactResponse(
                        rs.getString("phone_number"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .single();
    }

    @Transactional
    public ContactResponse update(ContactUpdateRequest request) {
        String phone = request == null || request.phone() == null ? "" : request.phone().trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int updated = jdbcClient.sql("""
                        update app_contact_setting
                        set phone_number = :phone,
                            updated_at = current_timestamp
                        where id = 1
                        """)
                .param("phone", phone)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        eventPublisher.publishEvent(PublicContentChangedEvent.contact());
        return current();
    }
}
