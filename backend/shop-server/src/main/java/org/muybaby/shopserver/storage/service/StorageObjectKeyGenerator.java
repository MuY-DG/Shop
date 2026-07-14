package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.storage.StorageAssetScope;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUploadProfile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public class StorageObjectKeyGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public String nextKey(StorageUploadProfile profile, String extension, LocalDate date) {
        Objects.requireNonNull(profile, "profile");
        return nextKey(profile.scope(), profile.mediaKind(), extension, date);
    }

    public String nextKey(
            StorageAssetScope scope,
            StorageMediaKind mediaKind,
            String extension,
            LocalDate date
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(mediaKind, "mediaKind");
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(date, "date");
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        String visibility = scope == StorageAssetScope.LIBRARY ? "public" : "private";
        return visibility
                + "/"
                + scope.name().toLowerCase(Locale.ROOT)
                + "/"
                + mediaKind.name().toLowerCase(Locale.ROOT)
                + "/"
                + DATE_FORMATTER.format(date)
                + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "."
                + normalizedExtension;
    }
}
