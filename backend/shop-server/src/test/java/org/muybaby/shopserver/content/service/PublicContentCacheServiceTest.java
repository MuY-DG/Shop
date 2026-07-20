package org.muybaby.shopserver.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.content.ContentProperties;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.content.dto.AppHomeResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductPriceResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductResponse;
import org.muybaby.shopserver.content.dto.AppHomeProductSectionResponse;
import org.muybaby.shopserver.content.dto.ContactResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicContentCacheServiceTest {

    @Test
    void homeCacheHitSkipsDatabaseAssembly() throws Exception {
        Fixture fixture = fixture();
        AppHomeResponse cached = emptyHome();
        when(fixture.values().get(PublicContentCacheService.HOME_CACHE_KEY))
                .thenReturn(fixture.objectMapper().writeValueAsString(cached));

        assertThat(fixture.service().homePage()).isEqualTo(cached);

        verify(fixture.homeQuery(), never()).load();
    }

    @Test
    void homeCacheMissBuildsAndWritesWithTransitionBoundedTtl() {
        Fixture fixture = fixture();
        AppHomeResponse assembled = emptyHome();
        when(fixture.values().get(PublicContentCacheService.HOME_CACHE_KEY)).thenReturn(null);
        when(fixture.homeQuery().load()).thenReturn(new HomePageQueryService.HomePageLoad(
                assembled,
                LocalDateTime.now().plusMinutes(10)
        ));

        assertThat(fixture.service().homePage()).isEqualTo(assembled);

        verify(fixture.values()).set(
                eq(PublicContentCacheService.HOME_CACHE_KEY),
                anyString(),
                any(Duration.class)
        );
        assertThat(fixture.service().homeTtl(LocalDateTime.now().plusMinutes(10)))
                .isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10));
    }

    @Test
    void cachedHomeWithoutOriginalPriceIsRebuiltUnderTheSameV2Key() throws Exception {
        Fixture fixture = fixture();
        AppHomeResponse stale = new AppHomeResponse(
                2,
                List.of(),
                List.of(),
                List.of(new AppHomeProductSectionResponse(
                        "HOT",
                        "FEATURED",
                        List.of(new AppHomeProductResponse(
                                1L,
                                1L,
                                "商品",
                                "",
                                "",
                                new AppHomeProductPriceResponse(1990L, 2590L, null),
                                null,
                                List.of(),
                                List.of(),
                                null,
                                0L,
                                "/pages/product/detail/detail?id=1"
                        ))
                ))
        );
        AppHomeResponse assembled = emptyHome();
        when(fixture.values().get(PublicContentCacheService.HOME_CACHE_KEY))
                .thenReturn(fixture.objectMapper().writeValueAsString(stale));
        when(fixture.homeQuery().load()).thenReturn(new HomePageQueryService.HomePageLoad(assembled, null));

        assertThat(fixture.service().homePage()).isEqualTo(assembled);

        verify(fixture.redis()).delete(PublicContentCacheService.HOME_CACHE_KEY);
        verify(fixture.values()).set(
                eq(PublicContentCacheService.HOME_CACHE_KEY),
                eq(fixture.objectMapper().writeValueAsString(assembled)),
                any(Duration.class)
        );
    }

    @Test
    void redisReadFailureFallsBackToDatabaseAndEventsEvictOnlyTheirRegion() {
        Fixture fixture = fixture();
        AppHomeResponse assembled = emptyHome();
        when(fixture.values().get(PublicContentCacheService.HOME_CACHE_KEY))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(fixture.homeQuery().load()).thenReturn(new HomePageQueryService.HomePageLoad(assembled, null));

        assertThat(fixture.service().homePage()).isEqualTo(assembled);

        clearInvocations(fixture.redis());
        fixture.service().onContentChanged(PublicContentChangedEvent.home());
        fixture.service().onContentChanged(PublicContentChangedEvent.contact());
        verify(fixture.redis()).delete(PublicContentCacheService.HOME_CACHE_KEY);
        verify(fixture.redis()).delete(PublicContentCacheService.CONTACT_CACHE_KEY);
    }

    @Test
    void contactCacheUsesIndependentKeyAndTtl() throws Exception {
        Fixture fixture = fixture();
        ContactResponse contact = new ContactResponse("400-800-1234", LocalDateTime.now());
        when(fixture.values().get(PublicContentCacheService.CONTACT_CACHE_KEY)).thenReturn(null);
        when(fixture.contactService().current()).thenReturn(contact);

        assertThat(fixture.service().contact()).isEqualTo(contact);

        verify(fixture.values()).set(
                eq(PublicContentCacheService.CONTACT_CACHE_KEY),
                eq(fixture.objectMapper().writeValueAsString(contact)),
                eq(Duration.ofHours(24))
        );
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        HomePageQueryService homeQuery = mock(HomePageQueryService.class);
        ContactService contactService = mock(ContactService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ContentProperties properties = new ContentProperties(true, Duration.ofHours(6), Duration.ofHours(24));
        PublicContentCacheService service = new PublicContentCacheService(
                redis,
                objectMapper,
                homeQuery,
                contactService,
                properties
        );
        return new Fixture(service, redis, values, homeQuery, contactService, objectMapper);
    }

    private AppHomeResponse emptyHome() {
        return new AppHomeResponse(2, List.of(), List.of(), List.of());
    }

    private record Fixture(
            PublicContentCacheService service,
            StringRedisTemplate redis,
            ValueOperations<String, String> values,
            HomePageQueryService homeQuery,
            ContactService contactService,
            ObjectMapper objectMapper
    ) {
    }
}
