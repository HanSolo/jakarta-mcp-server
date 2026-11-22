package eu.hansolo.mcp.tools;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain beans with getters as results. */
class BeansTest {

    public static class Address {
        private final String city;

        public Address(String city) {
            this.city = city;
        }

        public String getCity() {
            return city;
        }
    }

    public static class Customer {
        public String nickname = "dd";                  // public field
        public transient String ignored = "x";          // transient: not a property
        public static String CONSTANT = "c";            // static: not a property
        private final String  name;

        public Customer(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public boolean isActive() {
            return true;
        }

        public Boolean isVip() {
            return Boolean.FALSE;
        }

        public String getURL() {
            return "https://example.com";
        }

        public LocalDate getSince() {
            return LocalDate.of(2026, 11, 24);
        }

        public String getMissing() {
            return null;
        }

        public Optional<String> getEmpty() {
            return Optional.empty();
        }

        public Address getAddress() {
            return new Address("Duckburg");
        }

        public List<Address> getOthers() {
            return List.of(new Address("A"), new Address("B"));
        }

        public String isNotAGetter() {
            return "no";
        }

        public String getWithArgument(String x) {
            return x;
        }

        public void getVoid() {
        }
    }

    public static class Parent {
        public Child child;
        public String getName() {
            return "parent";
        }
    }

    public static class Child {
        public Parent parent;
        public String getName() {
            return "child";
        }
    }

    public static class Deep {
        public Deep next;
        public int level;
    }

    public static class Empty {
    }

    public static class Broken {
        public String getName() {
            throw new IllegalStateException("database is down");
        }
    }

    public static class Customer$HibernateProxy$x {
        public String getName() {
            return "lazy";
        }
    }

    public static class Typed<T> {
        public T value() {
            return null;
        }

        public T getValue() {
            return null;
        }

        public int getCount() {
            return 3;
        }

        @McpParam(name = "label", description = "Shown to the user")
        public String getTitle() {
            return "t";
        }

        public Thread getThread() {
            return null;
        }
    }

    public static class Tools {
        @McpTool
        public Customer customerOf(Customer parameter) {
            return parameter;
        }
    }


    private static JsonObject json(Object value) {
        return (JsonObject) JsonMapper.toJson(value);
    }


    @Test
    void gettersAndPublicFieldsBecomePropertiesSortedByName() {
        JsonObject customer = json(new Customer("Donald"));
        assertEquals(List.of("URL", "active", "address", "name", "nickname", "others", "since", "vip"), List.copyOf(customer.keySet()));
        assertEquals("Donald", customer.getString("name"));
        assertTrue(customer.getBoolean("active"));
        assertFalse(customer.getBoolean("vip"));
        assertEquals("dd", customer.getString("nickname"));
        assertEquals("https://example.com", customer.getString("URL"));
        assertEquals("2026-11-24", customer.getString("since"));
    }

    @Test
    void nullAndEmptyOptionalPropertiesAreLeftOut() {
        JsonObject customer = json(new Customer("Donald"));
        assertFalse(customer.containsKey("missing"));
        assertFalse(customer.containsKey("empty"));
    }

    @Test
    void nestedBeansAndListsOfBeansAreWritten() {
        JsonObject customer = json(new Customer("Donald"));
        assertEquals("Duckburg", customer.getJsonObject("address").getString("city"));
        assertEquals(2, customer.getJsonArray("others").size());
        assertEquals("B", customer.getJsonArray("others").getJsonObject(1).getString("city"));
    }

    @Test
    void aBeanInsideAMapIsWritten() {
        JsonObject map = json(Map.of("a", new Address("X")));
        assertEquals("X", map.getJsonObject("a").getString("city"));
    }

    @Test
    void aBeanThatContainsItselfIsRefusedWithThePath() {
        Parent parent = new Parent();
        parent.child = new Child();
        parent.child.parent = parent;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> JsonMapper.toJson(parent));
        assertTrue(e.getMessage().contains("contains itself"), e.getMessage());
        assertTrue(e.getMessage().contains("Parent -> Child -> Parent"), e.getMessage());
    }

    @Test
    void beansNestedTooDeeplyAreRefused() {
        Deep top = new Deep();
        Deep current = top;
        for (int i = 0; i < Beans.MAX_DEPTH + 2; i++) {
            current.next = new Deep();
            current = current.next;
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> JsonMapper.toJson(top));
        assertTrue(e.getMessage().contains("nested deeper than " + Beans.MAX_DEPTH), e.getMessage());
    }

    @Test
    void aChainJustWithinTheLimitWorks() {
        Deep top = new Deep();
        Deep current = top;
        for (int i = 1; i < Beans.MAX_DEPTH; i++) {
            current.next = new Deep();
            current = current.next;
        }
        assertNotNull(json(top));
    }

    @Test
    void aProxyIsRefusedWithAClearMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> JsonMapper.toJson(new Customer$HibernateProxy$x()));
        assertTrue(e.getMessage().contains("proxy"), e.getMessage());
        assertTrue(e.getMessage().contains("record or a DTO"), e.getMessage());
    }

    @Test
    void aClassWithoutGettersIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> JsonMapper.toJson(new Empty()));
        assertTrue(e.getMessage().contains("no public getters"), e.getMessage());
    }

    @Test
    void aGetterThatThrowsNamesTheProperty() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> JsonMapper.toJson(new Broken()));
        assertTrue(e.getMessage().contains("'name'"), e.getMessage());
        assertTrue(e.getMessage().contains("database is down"), e.getMessage());
    }

    @Test
    void jdkClassesAreNoBeans() {
        assertFalse(Beans.isBean(Thread.class));
        assertFalse(Beans.isBean(java.io.File.class));
        assertFalse(Beans.isBean(ArrayList.class));
        assertFalse(Beans.isBean(String.class));
        assertTrue(Beans.isBean(Customer.class));
    }

    @Test
    void decapitalizeFollowsTheJavaBeansRule() {
        assertEquals("name", Beans.decapitalize("Name"));
        assertEquals("URL", Beans.decapitalize("URL"));
        assertEquals("x", Beans.decapitalize("X"));
    }


    @Test
    void theSchemaListsTheProperties() {
        JsonObject schema = SchemaGenerator.schemaForResult(Typed.class);
        JsonObject properties = schema.getJsonObject("properties");
        assertEquals("object", schema.getString("type"));
        assertEquals("integer", properties.getJsonObject("count").getString("type"));
        assertEquals(List.of("count"), schema.getJsonArray("required").getValuesAs(jakarta.json.JsonString.class).stream().map(jakarta.json.JsonString::getString).toList());
        assertFalse(schema.containsKey("additionalProperties"));
    }

    @Test
    void anAnnotatedGetterRenamesAndDescribesTheProperty() {
        JsonObject properties = SchemaGenerator.schemaForResult(Typed.class).getJsonObject("properties");
        assertEquals("Shown to the user", properties.getJsonObject("label").getString("description"));
        assertFalse(properties.containsKey("title"));
    }

    @Test
    void aTypeThatCannotBeDescribedBecomesAnyValue() {
        JsonObject properties = SchemaGenerator.schemaForResult(Typed.class).getJsonObject("properties");
        assertEquals(JsonObject.EMPTY_JSON_OBJECT, properties.getJsonObject("value"));
        assertEquals(JsonObject.EMPTY_JSON_OBJECT, properties.getJsonObject("thread"));
    }

    @Test
    void nestedBeansAreDescribedInline() {
        JsonObject properties = SchemaGenerator.schemaForResult(Customer.class).getJsonObject("properties");
        assertEquals("string", properties.getJsonObject("address").getJsonObject("properties").getJsonObject("city").getString("type"));
        assertEquals("array", properties.getJsonObject("others").getString("type"));
        assertEquals("date", properties.getJsonObject("since").getString("format"));
    }

    @Test
    void aBeanThatRefersToItselfUsesDefs() {
        JsonObject schema = SchemaGenerator.schemaForResult(Parent.class);
        assertEquals("#/$defs/Parent", schema.getString("$ref"));
        JsonObject parent = schema.getJsonObject("$defs").getJsonObject("Parent");
        assertEquals("#/$defs/Parent", parent.getJsonObject("properties").getJsonObject("child").getJsonObject("properties").getJsonObject("parent").getString("$ref"));
    }

    @Test
    void aBeanIsNotAcceptedAsAParameter() {
        assertThrows(IllegalStateException.class, () -> SchemaGenerator.schemaFor(Customer.class));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ToolScanner.scan(Tools.class, BeanSource.of(new Tools())));
        assertTrue(e.getMessage().contains("customerOf"), e.getMessage());
    }


    public static class OkTools {
        @McpTool
        public Customer customer() {
            return new Customer("Donald");
        }

        @McpTool
        public List<Customer> customers() {
            return List.of(new Customer("A"));
        }
    }

    @Test
    void aToolReturningABeanGivesStructuredContentAndAnOutputSchema() throws Exception {
        McpToolHandler handler = ToolScanner.scan(OkTools.class, BeanSource.of(new OkTools())).stream().filter(t -> t.name().equals("customer")).findFirst().orElseThrow();
        assertNotNull(handler.outputSchema());
        ToolCall call = new ToolCall("customer", JsonObject.EMPTY_JSON_OBJECT, JsonObject.EMPTY_JSON_OBJECT, null, null, null, new Caller("alice", Set.of(), JsonObject.EMPTY_JSON_OBJECT));
        ToolResult.Complete result = (ToolResult.Complete) handler.call(call);
        assertEquals("Donald", ((JsonObject) result.structuredContent()).getString("name"));
    }

    @Test
    void aListOfBeansIsTextWithoutOutputSchema() throws Exception {
        McpToolHandler handler = ToolScanner.scan(OkTools.class, BeanSource.of(new OkTools())).stream().filter(t -> t.name().equals("customers")).findFirst().orElseThrow();
        assertNull(handler.outputSchema());
        ToolCall call = new ToolCall("customers", JsonObject.EMPTY_JSON_OBJECT, JsonObject.EMPTY_JSON_OBJECT, null, null, null, new Caller("alice", Set.of(), JsonObject.EMPTY_JSON_OBJECT));
        ToolResult.Complete result = (ToolResult.Complete) handler.call(call);
        assertNull(result.structuredContent());
        assertTrue(result.content().toString().contains("name"));
    }
}
