package org.muybaby.shopserver.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void successUsesArtDesignProEnvelope() {
        ApiResponse<String> response = ApiResponse.success("ok");

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.msg()).isEqualTo("success");
        assertThat(response.data()).isEqualTo("ok");
    }

    @Test
    void pageResultUsesAdminTableFields() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 2, 1, 10);

        assertThat(page.records()).containsExactly("a", "b");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.current()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
    }
}
