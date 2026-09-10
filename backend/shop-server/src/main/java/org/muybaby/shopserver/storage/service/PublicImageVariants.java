package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.storage.provider.ProcessedImage;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider.ImageProcessOutput;

import java.util.List;

/** Persisted derivatives belonging to the same asset as the original display image. */
public final class PublicImageVariants {
    public static final String THUMBNAIL_SUFFIX = ".thumb-480.webp";
    public static final String DISPLAY_SUFFIX = ".display-1080.webp";

    private PublicImageVariants() {}

    public record Urls(String thumbnailUrl, String displayUrl) {}

    public static Urls urls(String originalUrl) {
        return new Urls(withSuffix(originalUrl, THUMBNAIL_SUFFIX), withSuffix(originalUrl, DISPLAY_SUFFIX));
    }

    /** Expressions are trusted SQL identifiers supplied by repository code, never request values. */
    public static String thumbnailSql(String url, String fileId) {
        return "case when exists (select 1 from storage_asset image_asset where image_asset.id = " + fileId
                + " and image_asset.public_url = " + url
                + " and image_asset.scope = 'LIBRARY' and image_asset.media_kind = 'IMAGE'"
                + " and image_asset.visibility = 'PUBLIC' and image_asset.uploaded_by_type = 'ADMIN'"
                + " and image_asset.provider = 'TENCENT_COS'"
                + " and image_asset.content_type in ('image/webp', 'image/jpeg', 'image/png', 'image/gif')"
                + " and image_asset.status = 'ACTIVE'"
                + " and image_asset.public_image_variants_ready = true) then concat(" + url + ", '"
                + THUMBNAIL_SUFFIX + "') else " + url + " end";
    }

    private static String withSuffix(String url, String suffix) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int end = Math.min(query < 0 ? url.length() : query, fragment < 0 ? url.length() : fragment);
        return url.substring(0, end) + suffix + url.substring(end);
    }

    public static List<ImageProcessOutput> outputs(String originalKey) {
        return List.of(
                new ImageProcessOutput(originalKey + THUMBNAIL_SUFFIX, 480, 78, true),
                new ImageProcessOutput(originalKey + DISPLAY_SUFFIX, 1080, 82, true));
    }

    public static List<StorageObjectLocation> locations(StorageObjectLocation original) {
        return outputs(original.objectKey()).stream().map(output -> new StorageObjectLocation(
                original.provider(), original.container(), original.region(), output.objectKey())).toList();
    }

    public static void validate(String originalKey, List<ProcessedImage> processed) {
        for (ImageProcessOutput expected : outputs(originalKey)) {
            List<ProcessedImage> matches = processed.stream()
                    .filter(image -> image != null && expected.objectKey().equals(image.objectKey())).toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("Missing public image variant");
            }
            ProcessedImage image = matches.getFirst();
            if (!"image/webp".equals(image.contentType()) || !"webp".equalsIgnoreCase(image.format())
                    || image.sizeBytes() <= 0 || image.width() <= 0 || image.height() <= 0
                    || image.width() > expected.maxDimension() || image.height() > expected.maxDimension()) {
                throw new IllegalStateException("Invalid public image variant");
            }
        }
    }
}
