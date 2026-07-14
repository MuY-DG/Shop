package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.dto.AppOrderPreviewRequest;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutRequestDigestTest {

    @Test
    void factoriesDefaultMissingSourceAndDefensivelyNormalizeCartIds() {
        List<Long> mutableIds = new ArrayList<>(List.of(9L, 2L, 9L, 7L));
        CheckoutRequest preview = CheckoutRequest.from(new AppOrderPreviewRequest(
                null, mutableIds, null, null, 4L, null
        ));
        mutableIds.clear();

        CheckoutRequest submit = CheckoutRequest.from(new AppOrderSubmitRequest(
                null, List.of(7L, 2L, 7L), null, null, 4L, null, "digest-submit"
        ));

        assertThat(preview.source()).isEqualTo(CheckoutSource.CART);
        assertThat(preview.cartItemIds()).containsExactly(9L, 2L, 7L);
        assertThat(submit.source()).isEqualTo(CheckoutSource.CART);
        assertThat(submit.cartItemIds()).containsExactly(7L, 2L);
    }

    @Test
    void cartDigestSortsAndDeduplicatesIdsAndUsesExplicitAutomaticCouponMarker() {
        CheckoutRequest request = new CheckoutRequest(
                CheckoutSource.CART,
                List.of(9L, 2L, 7L, 2L),
                null,
                null,
                4L,
                null
        );

        assertThat(CheckoutRequestDigest.digest(request))
                .isEqualTo("fb5ebb2d83d646eb227e7a82a5ef8a17d4333a024dd05fcbad10d436a462d363");
    }

    @Test
    void directDigestContainsOnlyStableRequestFieldsAndChangesWithEveryIdempotencyInput() {
        CheckoutRequest base = new CheckoutRequest(CheckoutSource.DIRECT, List.of(), 18L, 2, 4L, null);

        assertThat(CheckoutRequestDigest.digest(base))
                .isEqualTo("2f86e83c1a08b0980e8ed4a0feece057457262a22d32e67509ea7dc2dea5ba9a");
        assertThat(CheckoutRequestDigest.digest(new CheckoutRequest(CheckoutSource.CART, List.of(18L), null, null, 4L, null)))
                .isNotEqualTo(CheckoutRequestDigest.digest(base));
        assertThat(CheckoutRequestDigest.digest(new CheckoutRequest(CheckoutSource.DIRECT, List.of(), 19L, 2, 4L, null)))
                .isNotEqualTo(CheckoutRequestDigest.digest(base));
        assertThat(CheckoutRequestDigest.digest(new CheckoutRequest(CheckoutSource.DIRECT, List.of(), 18L, 3, 4L, null)))
                .isNotEqualTo(CheckoutRequestDigest.digest(base));
        assertThat(CheckoutRequestDigest.digest(new CheckoutRequest(CheckoutSource.DIRECT, List.of(), 18L, 2, 5L, null)))
                .isNotEqualTo(CheckoutRequestDigest.digest(base));
        assertThat(CheckoutRequestDigest.digest(new CheckoutRequest(CheckoutSource.DIRECT, List.of(), 18L, 2, 4L, 6L)))
                .isNotEqualTo(CheckoutRequestDigest.digest(base));
    }

    @Test
    void freightAwareDigestChangesWithCalculatedFreightAndKeepsLegacyDigestStable() {
        CheckoutRequest request = new CheckoutRequest(CheckoutSource.DIRECT, List.of(), 18L, 2, 4L, null);

        assertThat(CheckoutRequestDigest.digest(request, 0L))
                .isNotEqualTo(CheckoutRequestDigest.digest(request));
        assertThat(CheckoutRequestDigest.digest(request, 800L))
                .isNotEqualTo(CheckoutRequestDigest.digest(request, 1_200L));
        assertThat(CheckoutRequestDigest.digest(request, 800L)).hasSize(64);
    }
}
