package org.muybaby.shopserver.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.content.ContentProperties;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.content.dto.AppHomeResponse;
import org.muybaby.shopserver.content.dto.ContactResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class PublicContentCacheService {

    public static final String HOME_CACHE_KEY = "shop:public:home:v1";
    public static final String CONTACT_CACHE_KEY = "shop:public:contact:v1";

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicContentCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HomePageQueryService homePageQueryService;
    private final ContactService contactService;
    private final ContentProperties properties;

    public PublicContentCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            HomePageQueryService homePageQueryService,
            ContactService contactService,
            ContentProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.homePageQueryService = homePageQueryService;
        this.contactService = contactService;
        this.properties = properties;
    }

    public AppHomeResponse homePage() {
        if (Boolean.TRUE.equals(properties.cacheEnabled())) {
            AppHomeResponse cached = read(HOME_CACHE_KEY, AppHomeResponse.class);
            if (cached != null) {
                return cached;
            }
        }

        HomePageQueryService.HomePageLoad loaded = homePageQueryService.load();
        if (Boolean.TRUE.equals(properties.cacheEnabled())) {
            write(HOME_CACHE_KEY, loaded.response(), homeTtl(loaded.nextTransitionAt()));
        }
        return loaded.response();
    }

    public ContactResponse contact() {
        if (Boolean.TRUE.equals(properties.cacheEnabled())) {
            ContactResponse cached = read(CONTACT_CACHE_KEY, ContactResponse.class);
            if (cached != null) {
                return cached;
            }
        }
        ContactResponse response = contactService.current();
        if (Boolean.TRUE.equals(properties.cacheEnabled())) {
            write(CONTACT_CACHE_KEY, response, properties.contactCacheTtl());
        }
        return response;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onContentChanged(PublicContentChangedEvent event) {
        if (event == null || !Boolean.TRUE.equals(properties.cacheEnabled())) {
            return;
        }
        if (event.region() == PublicContentChangedEvent.Region.HOME) {
            delete(HOME_CACHE_KEY);
        } else if (event.region() == PublicContentChangedEvent.Region.CONTACT) {
            delete(CONTACT_CACHE_KEY);
        }
    }

    Duration homeTtl(LocalDateTime nextTransitionAt) {
        Duration configured = properties.homeCacheTtl();
        if (nextTransitionAt == null) {
            return configured;
        }
        long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), nextTransitionAt);
        if (seconds <= 0) {
            return Duration.ofSeconds(1);
        }
        Duration transitionTtl = Duration.ofSeconds(seconds);
        return transitionTtl.compareTo(configured) < 0 ? transitionTtl : configured;
    }

    private <T> T read(String key, Class<T> responseType) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            return objectMapper.readValue(value, responseType);
        } catch (RuntimeException | JsonProcessingException ex) {
            LOGGER.warn("Failed to read public content cache key {}", key, ex);
            delete(key);
            return null;
        }
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException | JsonProcessingException ex) {
            LOGGER.warn("Failed to write public content cache key {}", key, ex);
        }
    }

    private void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to delete public content cache key {}", key, ex);
        }
    }
}
