package org.muybaby.shopserver.common.time;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * JSON contract for persistence records that use LocalDateTime as a UTC carrier.
 * The application and database are pinned to UTC, and the public contract is offset-aware.
 */
@JsonComponent
public final class UtcLocalDateTimeJson {

    private static final DateTimeFormatter OUTPUT_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendOffsetId()
            .toFormatter();

    private UtcLocalDateTimeJson() {
    }

    public static final class Serializer extends JsonSerializer<LocalDateTime> {

        @Override
        public void serialize(
                LocalDateTime value,
                JsonGenerator generator,
                SerializerProvider serializers
        ) throws IOException {
            generator.writeString(value.atOffset(ZoneOffset.UTC).format(OUTPUT_FORMATTER));
        }
    }

    public static final class Deserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(
                JsonParser parser,
                DeserializationContext context
        ) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (LocalDateTime) context.handleUnexpectedToken(
                        LocalDateTime.class, parser);
            }
            String value = parser.getText().trim();
            if (value.isEmpty()) {
                return (LocalDateTime) getNullValue(context);
            }
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
            } catch (DateTimeException exception) {
                throw JsonMappingException.from(parser,
                        "Expected an ISO-8601 date-time with Z or a numeric offset",
                        exception);
            }
        }
    }
}
