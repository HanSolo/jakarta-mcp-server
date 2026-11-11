package eu.hansolo.mcp.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The string formats of JSON Schema 2020-12 that {@link SchemaValidator} can assert when {@code mcp.validation.formats} is on:
 * {@code date-time}, {@code date}, {@code time}, {@code duration}, {@code email}, {@code hostname}, {@code ipv4}, {@code ipv6},
 * {@code uri}, {@code uri-reference}, {@code uuid}, {@code json-pointer} and {@code regex}. Any other format name is accepted.
 * Nothing here ever resolves a name or opens a connection.
 */
final class Formats {

    private static final Pattern DATE_TIME  = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})");
    private static final Pattern DATE       = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME       = Pattern.compile("\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})");
    private static final Pattern DURATION   = Pattern.compile("P(?:\\d+W|(?:\\d+D|\\d+M(?:\\d+D)?|\\d+Y(?:\\d+M)?(?:\\d+D)?)(?:T(?:\\d+H(?:\\d+M)?(?:\\d+S)?|\\d+M(?:\\d+S)?|\\d+S))?|T(?:\\d+H(?:\\d+M)?(?:\\d+S)?|\\d+M(?:\\d+S)?|\\d+S))");
    private static final Pattern LABEL      = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern LOCAL_PART = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    private static final Pattern IPV4       = Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");
    private static final Pattern UUID       = Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern POINTER    = Pattern.compile("(?:/(?:[^~/]|~[01])*)*");


    private Formats() {
    }


    /** False if {@code text} is not a valid value of the named format; true for a valid value and for any format not listed above. */
    static boolean valid(String format, String text) {
        return switch (format) {
            case "date-time"     -> dateTime(text);
            case "date"          -> date(text);
            case "time"          -> time(text);
            case "duration"      -> DURATION.matcher(text).matches();
            case "email"         -> email(text);
            case "hostname"      -> hostname(text);
            case "ipv4"          -> IPV4.matcher(text).matches();
            case "ipv6"          -> ipv6(text);
            case "uri"           -> uri(text, true);
            case "uri-reference" -> uri(text, false);
            case "uuid"          -> UUID.matcher(text).matches();
            case "json-pointer"  -> POINTER.matcher(text).matches();
            case "regex"         -> regex(text);
            default              -> true;
        };
    }

    private static boolean dateTime(String text) {
        try {
            return DATE_TIME.matcher(text).matches() && OffsetDateTime.parse(text.toUpperCase(Locale.ROOT)) != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean date(String text) {
        try {
            return DATE.matcher(text).matches() && LocalDate.parse(text) != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean time(String text) {
        try {
            return TIME.matcher(text).matches() && OffsetTime.parse(text.toUpperCase(Locale.ROOT)) != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean email(String text) {
        int at = text.lastIndexOf('@');
        if (at < 1 || at > 64) {
            return false;
        }
        String domain = text.substring(at + 1);
        boolean literal = domain.length() > 2 && domain.startsWith("[") && domain.endsWith("]");
        return LOCAL_PART.matcher(text.substring(0, at)).matches() && (literal ? literalAddress(domain.substring(1, domain.length() - 1)) : hostname(domain));
    }

    private static boolean literalAddress(String text) {
        return text.regionMatches(true, 0, "IPv6:", 0, 5) ? ipv6(text.substring(5)) : IPV4.matcher(text).matches();
    }

    private static boolean hostname(String text) {
        if (text.isEmpty() || text.length() > 253) {
            return false;
        }
        for (String label : text.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean ipv6(String text) {
        if (text.indexOf(':') < 0) {
            return false;
        }
        int compress = text.indexOf("::");
        if (compress >= 0 && text.indexOf("::", compress + 1) >= 0) {
            return false;
        }
        String head = compress < 0 ? text : text.substring(0, compress);
        String tail = compress < 0 ? "" : text.substring(compress + 2);
        List<String> pieces = new ArrayList<>();
        if (!head.isEmpty()) {
            pieces.addAll(List.of(head.split(":", -1)));
        }
        if (!tail.isEmpty()) {
            pieces.addAll(List.of(tail.split(":", -1)));
        }
        int groups = 0;
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            boolean last = i == pieces.size() - 1 && (compress < 0 || !tail.isEmpty());
            if (last && piece.indexOf('.') >= 0) {
                if (!IPV4.matcher(piece).matches()) {
                    return false;
                }
                groups += 2;
            } else if (IPV6_GROUP.matcher(piece).matches()) {
                groups++;
            } else {
                return false;
            }
        }
        return compress < 0 ? groups == 8 : groups <= 7;
    }

    /** Pure ASCII, and a syntax {@code java.net.URI} accepts; a {@code uri} must also have a scheme. */
    private static boolean uri(String text, boolean absolute) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x21 || text.charAt(i) > 0x7E) {
                return false;
            }
        }
        try {
            URI parsed = new URI(text);
            return !absolute || parsed.isAbsolute();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean regex(String text) {
        try {
            Pattern.compile(text);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
