package org.muybaby.shopserver.customerservice.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceThumbnailEncoderTest {

    @Test
    void fallsBackToJpegWhenWebpIsDisabled() throws Exception {
        BufferedImage source = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(new Color(40, 120, 220, 160));
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            graphics.dispose();
        }

        CustomerServiceThumbnailEncoder.EncodedThumbnail encoded =
                new CustomerServiceThumbnailEncoder(false).encode(source);

        assertThat(encoded.contentType()).isEqualTo("image/jpeg");
        assertThat(encoded.extension()).isEqualTo("jpg");
        assertThat(encoded.width()).isEqualTo(80);
        assertThat(encoded.height()).isEqualTo(40);
        assertThat(encoded.bytes()).isNotEmpty();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(encoded.bytes()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(80);
        assertThat(decoded.getHeight()).isEqualTo(40);
    }
}
