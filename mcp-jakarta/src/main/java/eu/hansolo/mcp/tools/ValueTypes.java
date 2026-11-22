package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Date;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The value types that travel as one JSON string: {@code java.time} dates, times and durations, {@code UUID}, {@code URI},
 * {@code Locale}, {@code Currency} and {@code java.util.Date}. Parameters are parsed from the string, results are written as the
 * string, and the schema says {@code "type": "string"} with a {@code format} where JSON Schema has one that fits, and an example
 * where it has none.
 *
 * <p>The text is always the ISO-8601 (or RFC 3339) form, never the default {@code toString()} of the type: {@code LocalTime}
 * prints {@code 10:30} there, and the schema format {@code date-time} needs the seconds. Types without an offset
 * ({@code LocalDateTime}, {@code LocalTime}) have no schema format, because the JSON Schema formats {@code date-time} and
 * {@code time} require one. A {@code java.util.Date} is written as the instant it denotes.
 *
 * <p><b>Internal.</b> Not part of the stable API: used by {@link SchemaGenerator}, {@link JsonConverter}, {@link JsonMapper} and
 * {@link ResultMapper}, and it can change in any release.
 */
final class ValueTypes {

    /** What the schema says about one type: the JSON Schema format (or null), an example, and how the text is described to the caller. */
    private record Kind(String format, String example, String expected) {
    }

    private static final Map<Class<?>, Kind> KINDS = new LinkedHashMap<>();
    private static final Pattern             UUID_TEXT = Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");

    static {
        KINDS.put(Instant.class, new Kind("date-time", "2026-11-24T09:30:00Z", "an ISO-8601 instant such as 2026-11-24T09:30:00Z"));
        KINDS.put(Date.class, new Kind("date-time", "2026-11-24T09:30:00Z", "an ISO-8601 instant such as 2026-11-24T09:30:00Z"));
        KINDS.put(OffsetDateTime.class, new Kind("date-time", "2026-11-24T10:30:00+01:00", "an ISO-8601 date-time with offset such as 2026-11-24T10:30:00+01:00"));
        KINDS.put(ZonedDateTime.class, new Kind(null, "2026-11-24T10:30:00+01:00[Europe/Paris]", "an ISO-8601 date-time with offset and optional zone such as 2026-11-24T10:30:00+01:00[Europe/Paris]"));
        KINDS.put(LocalDateTime.class, new Kind(null, "2026-11-24T10:30:00", "an ISO-8601 date-time without offset such as 2026-11-24T10:30:00"));
        KINDS.put(LocalDate.class, new Kind("date", "2026-11-24", "an ISO-8601 date such as 2026-11-24"));
        KINDS.put(LocalTime.class, new Kind(null, "10:30:00", "an ISO-8601 time without offset such as 10:30:00"));
        KINDS.put(OffsetTime.class, new Kind("time", "10:30:00+01:00", "an ISO-8601 time with offset such as 10:30:00+01:00"));
        KINDS.put(YearMonth.class, new Kind(null, "2026-11", "a year and month such as 2026-11"));
        KINDS.put(Duration.class, new Kind("duration", "PT1H30M", "an ISO-8601 duration such as PT1H30M"));
        KINDS.put(Period.class, new Kind("duration", "P1Y2M3D", "an ISO-8601 period such as P1Y2M3D"));
        KINDS.put(ZoneId.class, new Kind(null, "Europe/Paris", "a time zone id such as Europe/Paris or +01:00"));
        KINDS.put(UUID.class, new Kind("uuid", "123e4567-e89b-12d3-a456-426614174000", "a UUID such as 123e4567-e89b-12d3-a456-426614174000"));
        KINDS.put(URI.class, new Kind("uri-reference", "https://example.com/a", "a URI such as https://example.com/a"));
        KINDS.put(Locale.class, new Kind(null, "de-CH", "a BCP 47 language tag such as de-CH"));
        KINDS.put(Currency.class, new Kind(null, "EUR", "an ISO 4217 currency code such as EUR"));
    }


    private ValueTypes() {
    }


    /** True if a parameter or result declared with exactly this type travels as a string. */
    static boolean supports(Class<?> declared) {
        return KINDS.containsKey(declared);
    }

    /** True if this value is one of the supported types (or a subclass, such as {@code java.sql.Timestamp} or a zone region). */
    static boolean isValue(Object value) {
        if (value == null) {
            return false;
        }
        for (Class<?> type : KINDS.keySet()) {
            if (type.isInstance(value)) {
                return true;
            }
        }
        return false;
    }

    /** The schema of the type, or null if it is not a value type. */
    static JsonObject schema(Class<?> declared) {
        Kind kind = KINDS.get(declared);
        if (kind == null) {
            return null;
        }
        JsonObjectBuilder schema = Json.createObjectBuilder().add("type", "string");
        if (kind.format() != null) {
            schema.add("format", kind.format());
        }
        JsonArrayBuilder examples = Json.createArrayBuilder().add(kind.example());
        return schema.add("examples", examples).build();
    }

    /** How the caller is told to write the text, for an error message: {@code "an ISO-8601 date such as 2026-11-24"}. */
    static String expected(Class<?> declared) {
        return KINDS.get(declared).expected();
    }

    /** The text of a value; the argument must satisfy {@link #isValue(Object)}. */
    static String format(Object value) {
        if (value instanceof Instant v) {
            return v.toString();
        }
        if (value instanceof Date v) {
            return Instant.ofEpochMilli(v.getTime()).toString();   // not Date.toInstant(): java.sql.Date refuses it
        }
        if (value instanceof OffsetDateTime v) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(v);
        }
        if (value instanceof ZonedDateTime v) {
            return DateTimeFormatter.ISO_ZONED_DATE_TIME.format(v);
        }
        if (value instanceof LocalDateTime v) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(v);
        }
        if (value instanceof LocalDate v) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(v);
        }
        if (value instanceof LocalTime v) {
            return DateTimeFormatter.ISO_LOCAL_TIME.format(v);
        }
        if (value instanceof OffsetTime v) {
            return DateTimeFormatter.ISO_OFFSET_TIME.format(v);
        }
        if (value instanceof YearMonth || value instanceof Duration || value instanceof Period || value instanceof UUID || value instanceof URI) {
            return value.toString();
        }
        if (value instanceof ZoneId v) {
            return v.getId();
        }
        if (value instanceof Locale v) {
            return v.toLanguageTag();
        }
        if (value instanceof Currency v) {
            return v.getCurrencyCode();
        }
        throw new IllegalArgumentException("Not a value type: " + value.getClass().getName());
    }

    /**
     * Reads the text as the declared type.
     *
     * @throws IllegalArgumentException if the text is not valid for the type; the message is not meant for the caller (use {@link #expected})
     */
    static Object parse(String text, Class<?> declared) {
        if (!KINDS.containsKey(declared)) {
            throw new IllegalArgumentException("Not a value type: " + declared.getName());
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("empty");
        }
        try {
            if (declared == Instant.class) {
                return Instant.parse(text);
            }
            if (declared == Date.class) {
                return Date.from(Instant.parse(text));
            }
            if (declared == OffsetDateTime.class) {
                return OffsetDateTime.parse(text);
            }
            if (declared == ZonedDateTime.class) {
                return ZonedDateTime.parse(text);
            }
            if (declared == LocalDateTime.class) {
                return LocalDateTime.parse(text);
            }
            if (declared == LocalDate.class) {
                return LocalDate.parse(text);
            }
            if (declared == LocalTime.class) {
                return LocalTime.parse(text);
            }
            if (declared == OffsetTime.class) {
                return OffsetTime.parse(text);
            }
            if (declared == YearMonth.class) {
                return YearMonth.parse(text);
            }
            if (declared == Duration.class) {
                return Duration.parse(text);
            }
            if (declared == Period.class) {
                return Period.parse(text);
            }
            if (declared == ZoneId.class) {
                return ZoneId.of(text);
            }
            if (declared == UUID.class) {
                if (!UUID_TEXT.matcher(text).matches()) {
                    throw new IllegalArgumentException("not a UUID");   // UUID.fromString would accept "1-1-1-1-1"
                }
                return UUID.fromString(text);
            }
            if (declared == URI.class) {
                return new URI(text);
            }
            if (declared == Locale.class) {
                return new Locale.Builder().setLanguageTag(text).build();
            }
            return Currency.getInstance(text);
        } catch (DateTimeException | URISyntaxException | IllformedLocaleException | ArithmeticException e) {
            throw new IllegalArgumentException(e.getClass().getSimpleName(), e);
        }
    }
}
