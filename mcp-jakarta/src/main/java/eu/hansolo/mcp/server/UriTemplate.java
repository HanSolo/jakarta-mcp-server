package eu.hansolo.mcp.server;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subset of RFC 6570 that resource templates need, matched in the reverse direction (URI to variables): {@code {name}}
 * matches one segment (no {@code / ? #}), {@code {+name}} matches anything. Two variables must be separated by literal text.
 */
public final class UriTemplate {

    private static final Pattern EXPRESSION = Pattern.compile("\\{(\\+?)([A-Za-z_][A-Za-z0-9_]*)}");

    private final String       template;
    private final Pattern      pattern;
    private final List<String> variables;


    private UriTemplate(String template, Pattern pattern, List<String> variables) {
        this.template  = template;
        this.pattern   = pattern;
        this.variables = variables;
    }


    /** @throws IllegalArgumentException for templates outside the supported subset */
    public static UriTemplate compile(String template) {
        StringBuilder regex = new StringBuilder("^");
        List<String> names = new ArrayList<>();
        Matcher matcher = EXPRESSION.matcher(template);
        int last = 0;
        boolean previousWasVariable = false;
        while (matcher.find()) {
            String literal = template.substring(last, matcher.start());
            checkLiteral(template, literal);
            if (literal.isEmpty() && previousWasVariable) {
                throw new IllegalArgumentException("Variables must be separated by literal text: " + template);
            }
            if (names.contains(matcher.group(2))) {
                throw new IllegalArgumentException("Variable '" + matcher.group(2) + "' appears twice: " + template);
            }
            regex.append(Pattern.quote(literal)).append(matcher.group(1).isEmpty() ? "([^/?#]+)" : "(.+)");
            names.add(matcher.group(2));
            previousWasVariable = true;
            last = matcher.end();
        }
        String tail = template.substring(last);
        checkLiteral(template, tail);
        regex.append(Pattern.quote(tail)).append('$');
        int colon = template.indexOf(':');
        if (colon <= 0 || template.substring(0, colon).contains("{")) {
            throw new IllegalArgumentException("A URI template must start with a literal scheme: " + template);
        }
        return new UriTemplate(template, Pattern.compile(regex.toString(), Pattern.DOTALL), List.copyOf(names));
    }

    private static void checkLiteral(String template, String literal) {
        if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) {
            throw new IllegalArgumentException("Unsupported expression in URI template (only {name} and {+name} are supported): " + template);
        }
    }

    public String template() {
        return template;
    }

    public List<String> variables() {
        return variables;
    }

    /** The decoded variable values if the URI matches, empty otherwise (including for malformed percent-encoding). */
    public Optional<Map<String, String>> match(String uri) {
        Matcher matcher = pattern.matcher(uri);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (int i = 0; i < variables.size(); i++) {
                values.put(variables.get(i), decode(matcher.group(i + 1)));
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(values);
    }

    /** Percent-decoding as in RFC 3986: a plus sign stays a plus sign. */
    private static String decode(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
