package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StoragePurpose;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

public class StorageObjectKeyGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public String nextKey(StoragePurpose purpose, String extension, LocalDate date) {
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        String visibility = purpose.visibility() == FileVisibility.PUBLIC ? "public" : "private";
        return visibility
                + "/"
                + purpose.keySegment()
                + "/"
                + DATE_FORMATTER.format(date)
                + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "."
                + normalizedExtension;
    }
}
