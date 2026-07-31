package org.muybaby.shopserver.customerservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

@Component
public class CustomerServiceThumbnailEncoder {

    private static final Logger log =
            LoggerFactory.getLogger(CustomerServiceThumbnailEncoder.class);
    private static final float JPEG_QUALITY = 0.82f;

    private volatile boolean webpAvailable;

    public CustomerServiceThumbnailEncoder(
            @Value("${shop.storage.customer-service-thumbnail.webp-enabled:true}")
            boolean webpEnabled
    ) {
        this.webpAvailable = webpEnabled;
    }

    public EncodedThumbnail encode(BufferedImage image) {
        if (webpAvailable) {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (ImageIO.write(image, "webp", output)) {
                    return new EncodedThumbnail(
                            output.toByteArray(),
                            "image/webp",
                            "webp",
                            image.getWidth(),
                            image.getHeight()
                    );
                }
                disableWebp("writer unavailable");
            } catch (IOException | RuntimeException | LinkageError ex) {
                disableWebp(ex.getClass().getSimpleName());
            }
        }
        return encodeJpeg(image);
    }

    private EncodedThumbnail encodeJpeg(BufferedImage source) {
        BufferedImage rgbImage = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = rgbImage.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgbImage.getWidth(), rgbImage.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new ThumbnailEncodingException("JPEG thumbnail writer is unavailable");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(rgbImage, null, null), params);
            imageOutput.flush();
            return new EncodedThumbnail(
                    output.toByteArray(),
                    "image/jpeg",
                    "jpg",
                    rgbImage.getWidth(),
                    rgbImage.getHeight()
            );
        } catch (IOException | RuntimeException ex) {
            throw new ThumbnailEncodingException("JPEG thumbnail encoding failed", ex);
        } finally {
            writer.dispose();
        }
    }

    private void disableWebp(String reason) {
        if (!webpAvailable) {
            return;
        }
        webpAvailable = false;
        log.warn(
                "WebP thumbnail encoding is unavailable; falling back to local JPEG thumbnails: reason={}",
                reason
        );
    }

    public record EncodedThumbnail(
            byte[] bytes,
            String contentType,
            String extension,
            int width,
            int height
    ) {
    }

    private static final class ThumbnailEncodingException extends RuntimeException {
        private ThumbnailEncodingException(String message) {
            super(message);
        }

        private ThumbnailEncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
