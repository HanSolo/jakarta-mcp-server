package eu.hansolo.mcp.api;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Asking the user for input during a request (elicitation), in the Multi Round-Trip form of the 2026-07-28 specification: the tool
 * returns {@code ToolResult.ask(...)} with {@link InputRequests}, the client asks the user and retries the call with
 * {@code inputResponses}, and the tool reads the answer with {@link #answer}.
 *
 * <pre>{@code
 * public ToolResult call(ToolCall call) {
 *     Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "contact");
 *     if (answer.isEmpty()) {
 *         if (!call.clientSupportsElicitation(Elicitation.Mode.FORM)) {
 *             return ToolResult.error("Pass the email address as an argument; this client cannot ask the user.");
 *         }
 *         Elicitation.Form form = Elicitation.form("Where can we reach you?").string("email", p -> p.format("email").required());
 *         return ToolResult.ask(InputRequests.of("contact", form), null);
 *     }
 *     if (!answer.get().accepted()) {
 *         return ToolResult.text("No problem, I will not ask again.");
 *     }
 *     return ToolResult.text("Sending to " + answer.get().string("email").orElseThrow());
 * }
 * }</pre>
 *
 * <p><b>Form mode</b> collects a flat object of primitive properties (string, number, integer, boolean, single and multiple choice).
 * The data passes through the client, so form mode must not ask for passwords, API keys, tokens or payment data; {@link #form} refuses
 * property names and texts that look like that (a heuristic, not a guarantee). Use <b>URL mode</b> ({@link #url}) for anything
 * sensitive: the user is sent to a page of yours and the data never reaches the client. The client's answer to a URL request only says
 * the user agreed to open the page, not that the work there is done; keep a signed {@code requestState}
 * ({@link eu.hansolo.mcp.server.McpRequestStates}) and check on the retry whether it is.
 *
 * <p>The answer is client input: {@link Form#problems} checks it against the form, and a missing or invalid answer should lead to a new
 * request, not an error. Ask only for modes the client declared ({@code call.clientSupportsElicitation(mode)}).
 */
public final class Elicitation {

    /** The two ways of asking. */
    public enum Mode { FORM, URL }

    /** What the user did. {@code CANCEL} is also what an unknown action is read as. */
    public enum Action { ACCEPT, DECLINE, CANCEL }

    private static final Pattern SENSITIVE = Pattern.compile("(?i)pass(word|wd|phrase|code)|secret|api[-_ ]?key|access[-_ ]?token|auth[-_ ]?token|bearer|credential|card[-_ ]?number|credit[-_ ]?card|cvv|cvc|private[-_ ]?key");
    private static final Set<String>  FORMATS   = Set.of("email", "uri", "date", "date-time");

    private Elicitation() {
    }


    /** A form request. Add properties, then pass it to {@link InputRequests}. */
    public static Form form(String message) {
        return new Form(message);
    }

    /**
     * A URL request: the user is asked to open the URL, and nothing they do there reaches the client.
     *
     * @param message why the user is sent there
     * @param url     absolute {@code https} URL (or {@code http} for local development) without user info. It must not carry personal data or a pre-authenticated link,
     *                and the page must check that whoever opens it is the user the request was made for
     * @throws IllegalArgumentException if the URL is not an absolute http(s) URL, or carries user info
     */
    public static Url url(String message, String url) {
        return new Url(message, url);
    }

    /**
     * True if the client declared this mode. An empty {@code elicitation} object means form mode only, as the specification says.
     *
     * @param clientCapabilities the capabilities of the request
     */
    public static boolean supports(JsonObject clientCapabilities, Mode mode) {
        if (clientCapabilities == null || !(clientCapabilities.get("elicitation") instanceof JsonObject elicitation)) {
            return false;
        }
        if (elicitation.isEmpty()) {
            return mode == Mode.FORM;
        }
        return elicitation.containsKey(mode == Mode.FORM ? "form" : "url");
    }

    /**
     * The user's answer to the request with this key.
     *
     * @param inputResponses {@code call.inputResponses()}, may be null (first attempt)
     * @return the answer, or empty if there is none or it is malformed (no object, or no {@code action}), in which case ask again
     */
    public static Optional<Answer> answer(JsonObject inputResponses, String key) {
        if (inputResponses == null || !(inputResponses.get(key) instanceof JsonObject json) || !(json.get("action") instanceof JsonString action)) {
            return Optional.empty();
        }
        Action parsed = switch (action.getString()) {
            case "accept"  -> Action.ACCEPT;
            case "decline" -> Action.DECLINE;
            default        -> Action.CANCEL;
        };
        JsonObject content = parsed == Action.ACCEPT && json.get("content") instanceof JsonObject c ? c : Json.createObjectBuilder().build();
        return Optional.of(new Answer(parsed, content));
    }


    /** A request that can go into {@link InputRequests}. */
    public sealed interface Request permits Form, Url {

        /** The request object: {@code method} and {@code params}. */
        JsonObject toJson();
    }


    /** The user's answer. {@code content} is empty unless the user accepted a form. */
    public record Answer(Action action, JsonObject content) {

        public boolean accepted() {
            return action == Action.ACCEPT;
        }

        public boolean declined() {
            return action == Action.DECLINE;
        }

        public boolean cancelled() {
            return action == Action.CANCEL;
        }

        public Optional<String> string(String name) {
            return content.get(name) instanceof JsonString s ? Optional.of(s.getString()) : Optional.empty();
        }

        /** An integer value; empty if absent, not a number, or not whole. */
        public Optional<Long> integer(String name) {
            return content.get(name) instanceof JsonNumber n && n.isIntegral() ? Optional.of(n.longValue()) : Optional.empty();
        }

        public Optional<Double> number(String name) {
            return content.get(name) instanceof JsonNumber n ? Optional.of(n.doubleValue()) : Optional.empty();
        }

        public Optional<Boolean> bool(String name) {
            return content.get(name) == JsonValue.TRUE ? Optional.of(true) : content.get(name) == JsonValue.FALSE ? Optional.of(false) : Optional.empty();
        }

        /** The strings of a multiple choice; empty if absent. Entries that are not strings are skipped. */
        public List<String> strings(String name) {
            List<String> values = new ArrayList<>();
            if (content.get(name) instanceof JsonArray array) {
                array.forEach(v -> {
                    if (v instanceof JsonString s) {
                        values.add(s.getString());
                    }
                });
            }
            return values;
        }
    }


    /** A request for the user to open a page. Create with {@link Elicitation#url}. */
    public static final class Url implements Request {

        private final String message;
        private final String url;


        private Url(String message, String url) {
            requireMessage(message);
            URI uri;
            try {
                uri = new URI(url == null ? "" : url);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Elicitation URL is not a valid URL");
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) || uri.getHost() == null) {
                throw new IllegalArgumentException("Elicitation URL must be an absolute https (or http) URL");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Elicitation URL must not contain user info (credentials)");
            }
            this.message = message;
            this.url     = url;
        }


        @Override
        public JsonObject toJson() {
            return request(Json.createObjectBuilder().add("mode", "url").add("message", message).add("url", url));
        }
    }


    /**
     * A form request. Create with {@link Elicitation#form}. Without any property it asks for a plain confirmation (the message and
     * accept/decline buttons), which is what a tool needs before it does something that cannot be undone.
     */
    public static final class Form implements Request {

        private final String                    message;
        private final Map<String, Property>     properties = new LinkedHashMap<>();


        private Form(String message) {
            requireMessage(message);
            checkSensitive("message", message);
            this.message = message;
        }


        /** A text property. */
        public Form string(String name, Consumer<Property> settings) {
            return add(name, new Property(Kind.STRING, null), settings);
        }

        public Form string(String name) {
            return string(name, p -> { });
        }

        /** A decimal number property. */
        public Form number(String name, Consumer<Property> settings) {
            return add(name, new Property(Kind.NUMBER, null), settings);
        }

        /** A whole number property. */
        public Form integer(String name, Consumer<Property> settings) {
            return add(name, new Property(Kind.INTEGER, null), settings);
        }

        /** A yes/no property. */
        public Form bool(String name, Consumer<Property> settings) {
            return add(name, new Property(Kind.BOOLEAN, null), settings);
        }

        /** One of these values. */
        public Form choice(String name, List<String> values, Consumer<Property> settings) {
            Map<String, String> plain = new LinkedHashMap<>();
            values.forEach(v -> plain.put(v, null));
            return choice(name, plain, settings);
        }

        /** One of these values; the map's values are the titles shown to the user (null for none), the keys are what comes back. */
        public Form choice(String name, Map<String, String> valuesWithTitles, Consumer<Property> settings) {
            return add(name, new Property(Kind.CHOICE, copyChoices(name, valuesWithTitles)), settings);
        }

        /** Any number of these values ({@code minItems}/{@code maxItems} limit it). */
        public Form multiChoice(String name, List<String> values, Consumer<Property> settings) {
            Map<String, String> plain = new LinkedHashMap<>();
            values.forEach(v -> plain.put(v, null));
            return multiChoice(name, plain, settings);
        }

        public Form multiChoice(String name, Map<String, String> valuesWithTitles, Consumer<Property> settings) {
            return add(name, new Property(Kind.MULTI, copyChoices(name, valuesWithTitles)), settings);
        }

        /** The {@code requestedSchema}. A form without properties is a plain confirmation: the user can only accept or decline. */
        public JsonObject schema() {
            JsonObjectBuilder props    = Json.createObjectBuilder();
            JsonArrayBuilder  required = Json.createArrayBuilder();
            properties.forEach((name, property) -> {
                props.add(name, property.toJson());
                if (property.required) {
                    required.add(name);
                }
            });
            JsonObjectBuilder schema = Json.createObjectBuilder().add("type", "object").add("properties", props);
            JsonArray requiredNames = required.build();
            if (!requiredNames.isEmpty()) {
                schema.add("required", requiredNames);
            }
            return schema.build();
        }

        @Override
        public JsonObject toJson() {
            return request(Json.createObjectBuilder().add("mode", "form").add("message", message).add("requestedSchema", schema()));
        }

        /**
         * Checks what the user sent against this form: required properties, type, length, range, format, allowed values and item counts.
         * The client should have done this already, but the data is client input.
         *
         * @return the problems, empty if the content is fine. Properties that are not in the form are a problem too
         */
        public List<String> problems(Answer answer) {
            List<String> problems = new ArrayList<>();
            JsonObject content = answer.content();
            content.keySet().stream().filter(k -> !properties.containsKey(k)).forEach(k -> problems.add("unexpected property '" + k + "'"));
            properties.forEach((name, property) -> {
                JsonValue value = content.get(name);
                if (value == null || value == JsonValue.NULL) {
                    if (property.required) {
                        problems.add("'" + name + "' is required");
                    }
                } else {
                    property.check(name, value, problems);
                }
            });
            return problems;
        }


        private Form add(String name, Property property, Consumer<Property> settings) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A form property needs a name");
            }
            if (properties.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate form property '" + name + "'");
            }
            checkSensitive("property '" + name + "'", name);
            settings.accept(property);
            checkSensitive("property '" + name + "'", property.title);
            checkSensitive("property '" + name + "'", property.description);
            property.checkDefault(name);
            properties.put(name, property);
            return this;
        }

        private static Map<String, String> copyChoices(String name, Map<String, String> choices) {
            if (choices == null || choices.isEmpty()) {
                throw new IllegalArgumentException("Choice '" + name + "' needs at least one value");
            }
            return new LinkedHashMap<>(choices);
        }
    }


    private enum Kind { STRING, NUMBER, INTEGER, BOOLEAN, CHOICE, MULTI }


    /** The settings of one form property. Which ones apply depends on the type; the others throw {@link IllegalStateException}. */
    public static final class Property {

        private final Kind                kind;
        private final Map<String, String> choices;
        private boolean                   required;
        private String                    title;
        private String                    description;
        private Integer                   minLength;
        private Integer                   maxLength;
        private String                    format;
        private Double                    minimum;
        private Double                    maximum;
        private Integer                   minItems;
        private Integer                   maxItems;
        private JsonValue                 defaultValue;


        private Property(Kind kind, Map<String, String> choices) {
            this.kind    = kind;
            this.choices = choices;
        }


        /** The user must answer this property. */
        public Property required() {
            this.required = true;
            return this;
        }

        public Property title(String title) {
            this.title = title;
            return this;
        }

        public Property description(String description) {
            this.description = description;
            return this;
        }

        /** Text only. */
        public Property minLength(int minLength) {
            only(Kind.STRING, "minLength");
            this.minLength = minLength;
            return this;
        }

        /** Text only. */
        public Property maxLength(int maxLength) {
            only(Kind.STRING, "maxLength");
            this.maxLength = maxLength;
            return this;
        }

        /** Text only: {@code email}, {@code uri}, {@code date} or {@code date-time}. */
        public Property format(String format) {
            only(Kind.STRING, "format");
            if (!FORMATS.contains(format)) {
                throw new IllegalArgumentException("format must be one of " + new java.util.TreeSet<>(FORMATS));
            }
            this.format = format;
            return this;
        }

        /** Numbers and integers only. */
        public Property minimum(double minimum) {
            numeric("minimum");
            this.minimum = minimum;
            return this;
        }

        /** Numbers and integers only. */
        public Property maximum(double maximum) {
            numeric("maximum");
            this.maximum = maximum;
            return this;
        }

        /** Multiple choice only. */
        public Property minItems(int minItems) {
            only(Kind.MULTI, "minItems");
            this.minItems = minItems;
            return this;
        }

        /** Multiple choice only. */
        public Property maxItems(int maxItems) {
            only(Kind.MULTI, "maxItems");
            this.maxItems = maxItems;
            return this;
        }

        /** Pre-fills a text or single choice property. */
        public Property defaultValue(String value) {
            if (kind != Kind.STRING && kind != Kind.CHOICE) {
                throw new IllegalStateException("A string default does not fit a " + kind.name().toLowerCase() + " property");
            }
            this.defaultValue = Json.createValue(value);
            return this;
        }

        /** Pre-fills a number or integer property. */
        public Property defaultValue(double value) {
            numeric("defaultValue");
            this.defaultValue = kind == Kind.INTEGER ? Json.createValue((long) value) : Json.createValue(value);
            return this;
        }

        /** Pre-fills a yes/no property. */
        public Property defaultValue(boolean value) {
            only(Kind.BOOLEAN, "defaultValue");
            this.defaultValue = value ? JsonValue.TRUE : JsonValue.FALSE;
            return this;
        }

        /** Pre-fills a multiple choice property. */
        public Property defaultValue(List<String> values) {
            only(Kind.MULTI, "defaultValue");
            JsonArrayBuilder array = Json.createArrayBuilder();
            values.forEach(array::add);
            this.defaultValue = array.build();
            return this;
        }


        private void only(Kind expected, String what) {
            if (kind != expected) {
                throw new IllegalStateException(what + " does not apply to a " + kind.name().toLowerCase() + " property");
            }
        }

        private void numeric(String what) {
            if (kind != Kind.NUMBER && kind != Kind.INTEGER) {
                throw new IllegalStateException(what + " does not apply to a " + kind.name().toLowerCase() + " property");
            }
        }

        private void checkDefault(String name) {
            if (defaultValue == null) {
                return;
            }
            List<String> problems = new ArrayList<>();
            check(name, defaultValue, problems);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("Default of '" + name + "' does not fit its own constraints: " + problems.get(0));
            }
        }

        private JsonObject toJson() {
            JsonObjectBuilder json = Json.createObjectBuilder();
            switch (kind) {
                case STRING  -> json.add("type", "string");
                case NUMBER  -> json.add("type", "number");
                case INTEGER -> json.add("type", "integer");
                case BOOLEAN -> json.add("type", "boolean");
                case CHOICE  -> json.add("type", "string");
                case MULTI   -> json.add("type", "array");
            }
            if (title != null) {
                json.add("title", title);
            }
            if (description != null) {
                json.add("description", description);
            }
            if (minLength != null) {
                json.add("minLength", minLength);
            }
            if (maxLength != null) {
                json.add("maxLength", maxLength);
            }
            if (format != null) {
                json.add("format", format);
            }
            if (minimum != null) {
                json.add("minimum", minimum);
            }
            if (maximum != null) {
                json.add("maximum", maximum);
            }
            if (kind == Kind.CHOICE) {
                choiceOptions(json, "oneOf", "enum");
            }
            if (kind == Kind.MULTI) {
                if (minItems != null) {
                    json.add("minItems", minItems);
                }
                if (maxItems != null) {
                    json.add("maxItems", maxItems);
                }
                JsonObjectBuilder items = Json.createObjectBuilder().add("type", "string");
                choiceOptions(items, "anyOf", "enum");
                json.add("items", items);
            }
            if (defaultValue != null) {
                json.add("default", defaultValue);
            }
            return json.build();
        }

        /** Titles present: {@code oneOf}/{@code anyOf} with {@code const} and {@code title}; none: a plain {@code enum}. */
        private void choiceOptions(JsonObjectBuilder target, String withTitles, String plain) {
            if (choices.values().stream().anyMatch(t -> t != null)) {
                JsonArrayBuilder options = Json.createArrayBuilder();
                choices.forEach((value, title) -> options.add(Json.createObjectBuilder().add("const", value).add("title", title == null ? value : title)));
                target.add(withTitles, options);
            } else {
                JsonArrayBuilder values = Json.createArrayBuilder();
                choices.keySet().forEach(values::add);
                target.add(plain, values);
            }
        }

        private void check(String name, JsonValue value, List<String> problems) {
            switch (kind) {
                case STRING -> {
                    if (!(value instanceof JsonString s)) {
                        problems.add("'" + name + "' must be a string");
                        return;
                    }
                    String text = s.getString();
                    if (minLength != null && text.length() < minLength) {
                        problems.add("'" + name + "' is shorter than " + minLength);
                    }
                    if (maxLength != null && text.length() > maxLength) {
                        problems.add("'" + name + "' is longer than " + maxLength);
                    }
                    if (format != null && !matchesFormat(format, text)) {
                        problems.add("'" + name + "' is not a valid " + format);
                    }
                }
                case NUMBER, INTEGER -> {
                    if (!(value instanceof JsonNumber n) || (kind == Kind.INTEGER && !n.isIntegral())) {
                        problems.add("'" + name + "' must be " + (kind == Kind.INTEGER ? "a whole number" : "a number"));
                        return;
                    }
                    if (minimum != null && n.doubleValue() < minimum) {
                        problems.add("'" + name + "' is below " + minimum);
                    }
                    if (maximum != null && n.doubleValue() > maximum) {
                        problems.add("'" + name + "' is above " + maximum);
                    }
                }
                case BOOLEAN -> {
                    if (value != JsonValue.TRUE && value != JsonValue.FALSE) {
                        problems.add("'" + name + "' must be true or false");
                    }
                }
                case CHOICE -> {
                    if (!(value instanceof JsonString s) || !choices.containsKey(s.getString())) {
                        problems.add("'" + name + "' must be one of " + choices.keySet());
                    }
                }
                case MULTI -> {
                    if (!(value instanceof JsonArray array)) {
                        problems.add("'" + name + "' must be a list");
                        return;
                    }
                    if (minItems != null && array.size() < minItems) {
                        problems.add("'" + name + "' needs at least " + minItems + " values");
                    }
                    if (maxItems != null && array.size() > maxItems) {
                        problems.add("'" + name + "' allows at most " + maxItems + " values");
                    }
                    for (JsonValue item : array) {
                        if (!(item instanceof JsonString s) || !choices.containsKey(s.getString())) {
                            problems.add("'" + name + "' may only hold " + choices.keySet());
                            break;
                        }
                    }
                }
            }
        }

        private static boolean matchesFormat(String format, String text) {
            return switch (format) {
                case "email"     -> text.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
                case "uri"       -> isUri(text);
                case "date"      -> parses(() -> java.time.LocalDate.parse(text));
                case "date-time" -> parses(() -> java.time.OffsetDateTime.parse(text));
                default          -> true;
            };
        }

        private static boolean isUri(String text) {
            try {
                return new URI(text).getScheme() != null;
            } catch (URISyntaxException e) {
                return false;
            }
        }

        private static boolean parses(Runnable parse) {
            try {
                parse.run();
                return true;
            } catch (java.time.format.DateTimeParseException e) {
                return false;
            }
        }
    }


    private static JsonObject request(JsonObjectBuilder params) {
        return Json.createObjectBuilder().add("method", "elicitation/create").add("params", params).build();
    }

    private static void requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("An elicitation needs a message that tells the user why");
        }
    }

    private static void checkSensitive(String where, String text) {
        if (text != null && SENSITIVE.matcher(text).find()) {
            throw new IllegalArgumentException("Elicitation " + where + " looks like a request for sensitive data (password, key, token, card). Form mode passes data through the client; use Elicitation.url(...) for this");
        }
    }
}
