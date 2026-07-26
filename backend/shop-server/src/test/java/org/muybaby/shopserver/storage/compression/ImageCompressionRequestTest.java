package org.muybaby.shopserver.storage.compression;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ImageCompressionRequestTest {

    @Test
    void acceptsOnlyJpegPngAndWebpMediaTypes() {
        assertThat(new ImageCompressionRequest(new byte[]{1}, "image/jpeg", 10).contentType())
                .isEqualTo("image/jpeg");
        assertThat(new ImageCompressionRequest(new byte[]{1}, " IMAGE/PNG ", 10).contentType())
                .isEqualTo("image/png");
        assertThat(new ImageCompressionRequest(
                new byte[]{1},
                "image/webp; charset=binary",
                10
        ).contentType()).isEqualTo("image/webp");

        assertThatIllegalArgumentException().isThrownBy(() ->
                new ImageCompressionRequest(new byte[]{1}, "image/gif", 10));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ImageCompressionRequest(new byte[]{1}, "image/avif", 10));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ImageCompressionRequest(new byte[]{1}, "image/svg+xml", 10));
    }

    @Test
    void inputAndOutputRecordsDefensivelyCopyImageBytes() {
        byte[] input = new byte[]{1, 2, 3};
        ImageCompressionRequest request =
                new ImageCompressionRequest(input, "image/png", 10);
        input[0] = 9;
        byte[] requestBytes = request.content();
        requestBytes[1] = 9;

        byte[] output = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        ImageCompressionResult result = new ImageCompressionResult(
                output,
                "image/webp",
                1,
                1,
                OptionalLong.empty()
        );
        output[0] = 0;
        byte[] resultBytes = result.content();
        resultBytes[1] = 0;

        assertThat(request.content()).containsExactly(1, 2, 3);
        assertThat(result.content()).startsWith('R', 'I');
    }
}
