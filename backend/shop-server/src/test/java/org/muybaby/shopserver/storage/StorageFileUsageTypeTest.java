package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class StorageFileUsageTypeTest {

    @Test
    void storageFileUsageTypeKeepsRichTextImageAlongsideDetailHtml() {
        assertThat(Arrays.stream(StorageFileUsageType.values()).map(Enum::name))
                .contains("PRODUCT_DETAIL_HTML", "RICH_TEXT_IMAGE");
    }

    @Test
    void productV2MediaUsageAndOwnerTypesAreAvailable() {
        assertThat(Arrays.stream(StorageFileUsageType.values()).map(Enum::name))
                .contains("PRODUCT_SPU_VIDEO", "PRODUCT_SPEC_VALUE_IMAGE", "GUARANTEE_SERVICE_ICON");
        assertThat(Arrays.stream(StorageUsageOwnerType.values()).map(Enum::name))
                .contains("PRODUCT_SPEC_VALUE", "GUARANTEE_SERVICE");
    }
}
