package org.muybaby.shopserver.support;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MigrationTestSupport {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V(\\d+)__.+\\.sql");

    private MigrationTestSupport() {
    }

    public static String latestMigrationVersion() {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        try (var migrations = Files.list(migrationDir)) {
            int latestVersion = migrations
                    .map(path -> path.getFileName().toString())
                    .map(VERSIONED_MIGRATION::matcher)
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElseThrow(() -> new IllegalStateException("No versioned Flyway migrations found"));
            return Integer.toString(latestVersion);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static Flyway migrateToLatest(String jdbcUrl, String username, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .load();
        flyway.migrate();
        return flyway;
    }
}
