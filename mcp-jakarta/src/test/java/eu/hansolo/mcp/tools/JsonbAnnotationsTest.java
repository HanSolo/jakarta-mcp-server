package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbPropertyOrder;
import jakarta.json.bind.annotation.JsonbTransient;
import eu.hansolo.mcp.api.McpParam;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON-B annotations that are honoured (name, transient, order) and the ones that are ignored. */
class JsonbAnnotationsTest {

    public static class Person {
        @JsonbProperty("first_name")
        private final String firstName = "Donald";

        private final String secret = "hunter2";

        @JsonbDateFormat("dd.MM.yyyy")
        private final LocalDate born = LocalDate.of(1934, 6, 9);

        public String getFirstName() {
            return firstName;
        }

        @JsonbTransient
        public String getSecret() {
            return secret;
        }

        @JsonbProperty("birthday")
        public LocalDate getBorn() {
            return born;
        }

        public int getAge() {
            return 92;
        }
    }

    public static class HiddenByField {
        @JsonbTransient
        private final String token = "t";

        public String getToken() {
            return token;
        }

        public String getName() {
            return "n";
        }
    }

    public static class PublicFields {
        @JsonbProperty("renamed")
        public String original = "x";
        @JsonbTransient
        public String hidden = "y";
        public String shown = "z";
    }

    @JsonbPropertyOrder({"name", "age"})
    public static class Ordered {
        public String getZebra() {
            return "z";
        }

        public int getAge() {
            return 1;
        }

        public String getName() {
            return "n";
        }

        public String getAlpha() {
            return "a";
        }
    }

    public static class McpWins {
        @McpParam(name = "fromMcp")
        @JsonbProperty("fromJsonb")
        public String getValue() {
            return "v";
        }
    }

    public record Booking(@JsonbProperty("duck_name") String duckName, int times, @McpParam(name = "when_mcp") @JsonbProperty("ignored") String when) {
    }


    private static JsonObject json(Object value) {
        return (JsonObject) JsonMapper.toJson(value);
    }


    @Test
    void aJsonbPropertyOnAGetterOrItsFieldRenamesTheProperty() {
        JsonObject person = json(new Person());
        assertTrue(person.containsKey("first_name"), person.toString());
        assertFalse(person.containsKey("firstName"));
        assertEquals("1934-06-09", person.getString("birthday"));
        assertEquals(92, person.getInt("age"));
    }

    @Test
    void aJsonbTransientGetterIsLeftOut() {
        assertFalse(json(new Person()).containsKey("secret"));
    }

    @Test
    void aJsonbTransientOnTheFieldLeavesOutTheGetterToo() {
        JsonObject object = json(new HiddenByField());
        assertEquals(List.of("name"), List.copyOf(object.keySet()));
    }

    @Test
    void publicFieldsFollowTheSameRules() {
        JsonObject object = json(new PublicFields());
        assertEquals(List.of("renamed", "shown"), List.copyOf(object.keySet()));
    }

    @Test
    void aJsonbDateFormatIsIgnoredAndTheTextStaysIso() {
        assertEquals("1934-06-09", json(new Person()).getString("birthday"));
    }

    @Test
    void thePropertyOrderListsTheNamedPropertiesFirst() {
        assertEquals(List.of("name", "age", "alpha", "zebra"), List.copyOf(json(new Ordered()).keySet()));
    }

    @Test
    void mcpParamWinsOverJsonbProperty() {
        assertTrue(json(new McpWins()).containsKey("fromMcp"));
    }

    @Test
    void theSchemaUsesTheRenamedProperties() {
        JsonObject properties = SchemaGenerator.schemaForResult(Person.class).getJsonObject("properties");
        assertTrue(properties.containsKey("first_name"));
        assertTrue(properties.containsKey("birthday"));
        assertFalse(properties.containsKey("secret"));
    }

    @Test
    void aRecordComponentWithJsonbPropertyIsWrittenUnderThatName() {
        JsonObject booking = json(new Booking("Donald", 2, "today"));
        assertEquals("Donald", booking.getString("duck_name"));
        assertEquals("today", booking.getString("when_mcp"));
        assertFalse(booking.containsKey("duckName"));
    }

    @Test
    void aRecordParameterIsReadFromTheRenamedProperty() {
        JsonObject schema = SchemaGenerator.schemaFor(Booking.class);
        assertTrue(schema.getJsonObject("properties").containsKey("duck_name"));
        assertTrue(schema.getJsonArray("required").toString().contains("duck_name"));
        JsonObject arguments = Json.createObjectBuilder().add("duck_name", "Daisy").add("times", 3).add("when_mcp", "now").build();
        Object value = JsonConverter.argument(arguments, Booking.class, "booking", true);
        assertEquals(new Booking("Daisy", 3, "now"), value);
    }
}
