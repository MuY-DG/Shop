package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentProperties;
import org.muybaby.shopserver.payment.dto.PaymentConfigSourceResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

@Service
public class PaymentConfigSourceSettingService {

    private static final long SETTING_ID = 1L;

    private final PaymentProperties properties;
    private final JdbcClient jdbcClient;

    public PaymentConfigSourceSettingService(PaymentProperties properties, JdbcClient jdbcClient) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
    }

    public PaymentConfigSource currentSource() {
        return persistedSource().orElse(defaultSource());
    }

    public PaymentConfigSourceResponse current() {
        Optional<PaymentConfigSource> persisted = persistedSource();
        return new PaymentConfigSourceResponse(
                persisted.orElse(defaultSource()).name(),
                persisted.isPresent(),
                defaultSource().name()
        );
    }

    @Transactional
    public void update(PaymentConfigSource source) {
        if (source == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int updatedRows = jdbcClient.sql("""
                        update payment_runtime_setting
                        set config_source = :source,
                            updated_at = current_timestamp
                        where id = :id
                        """)
                .param("source", source.name())
                .param("id", SETTING_ID)
                .update();
        if (updatedRows == 0) {
            jdbcClient.sql("""
                            insert into payment_runtime_setting
                                (id, config_source)
                            values
                                (:id, :source)
                            """)
                    .param("id", SETTING_ID)
                    .param("source", source.name())
                    .update();
        }
    }

    public PaymentConfigSource parse(String source) {
        if (!StringUtils.hasText(source)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return PaymentConfigSource.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Optional<PaymentConfigSource> persistedSource() {
        return jdbcClient.sql("select config_source from payment_runtime_setting where id = :id")
                .param("id", SETTING_ID)
                .query(String.class)
                .optional()
                .map(this::parse);
    }

    private PaymentConfigSource defaultSource() {
        return properties.configSource() == null ? PaymentConfigSource.AUTO : properties.configSource();
    }
}
