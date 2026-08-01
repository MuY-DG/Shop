package org.muybaby.shopserver.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.content.ContentProperties;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.content.dto.AppHomeResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductSectionResponse;
import org.muybaby.shopserver.content.dto.ContactResponse;
import org.muybaby.shopserver.product.ProductSaleState;
import org.muybaby.shopserver.product.service.ProductPublicStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class PublicContentCacheService {

    public static final String HOME_CACHE_KEY = "shop:public:home:content:v3";
    public static final String CONTACT_CACHE_KEY = "shop:public:contact:v1";

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicContentCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HomePageQueryService homePageQueryService;
    private final ProductPublicStateService productPublicStateService;
    private final ContactService contactService;
    private final ContentProperties properties;

    public PublicContentCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            HomePageQueryService homePageQueryService,
            ProductPublicStateService productPublicStateService,
            ContactService contactService,
            ContentProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.homePageQueryService = homePageQueryService;
        this.productPublicStateService = productPublicStateService;
        this.contactService = contactService;
        this.properties = properties;
    }

    public AppHomeResponse homePage() {
        AppHomeResponse snapshot = null;
        if (Boolean.TRUE.equals(properties.cacheEnabled())) {
            AppHomeResponse cached = read(HOME_CACHE_KEY, AppHomeResponse.class);
            if (cached != null && hasCurrentHomePriceShape(cached)) {
                snapshot = cached;
            }
            if (cached != null && snapshot == null) {
                delete(HOME_CACHE_KEY);
            }
        }

        if (snapshot == null) {
            HomePageQueryService.HomePageLoad loaded = homePageQueryService.load();
            snapshot = loaded.response();
            if (Boolean.TRUE.equals(properties.cacheEnabled())) {
                write(HOME_CACHE_KEY, snapshot, homeTtl(loaded.nextTransitionAt()));
            }
        }
        return withCurrentSaleStates(snapshot);
    }

    private AppHomeResponse withCurrentSaleStates(AppHomeResponse snapshot) {
        if (snapshot == null || snapshot.productSections() == null) {
            return snapshot;
        }
        List<Long> spuIds = snapshot.productSections().stream()
                .filter(Objects::nonNull)
                .flatMap(section -> section.products() == null ? Stream.empty() : section.products().stream())
                .filter(Objects::nonNull)
                .map(AppHomeProductResponse::spuId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ProductSaleState> saleStates = productPublicStateService.saleStates(spuIds);
        List<AppHomeProductSectionResponse> sections = snapshot.productSections().stream()
                .map(section -> section == null ? null : new AppHomeProductSectionResponse(
                        section.code(),
                        section.presentation(),
                        section.products() == null
                                ? List.of()
                                : section.products().stream()
                                        .map(product -> withSaleState(product, saleStates))
                                        .toList()
                ))
                .toList();
        return new AppHomeResponse(
                snapshot.schemaVersion(),
                snapshot.banners(),
                snapshot.categories(),
                sections
        );
    }

    private AppHomeProductResponse withSaleState(
            AppHomeProductResponse product,
            Map<Long, ProductSaleState> saleStates
    ) {
        if (product == null) {
            return null;
        }
        return new AppHomeProductResponse(
                product.placementId(),
                product.spuId(),
                product.title(),
                product.subtitle(),
                product.imageUrl(),
                product.price(),
                product.badge(),
                product.highlights(),
                product.metaFacts(),
                product.wholesaleSummary(),
                product.displaySales(),
                saleStates.getOrDefault(product.spuId(), ProductSaleState.SOLD_OUT),
                product.path()
        );
    }

    private boolean hasCurrentHomePriceShape(AppHomeResponse response) {
        if (response.productSections() == null) {
            return false;
        }
        for (var section : response.productSections()) {
            if (section == null || section.products() == null) {
                continue;
            }
            for (var product : section.products()) {
                if (product == null || product.price() == null) {
                    continue;
                }
                boolean hasCurrentPrice = product.price().minPriceCent() != null
                        || product.price().maxPriceCent() != null;
                if (hasCurrentPrice && product.price().originalPriceCent() == null) {
                    return false;
                }
            }
        }
        return true;
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
        long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(java.time.ZoneOffset.UTC), nextTransitionAt);
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
