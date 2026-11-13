package eu.hansolo.mcp.tickets;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Caller;
import eu.hansolo.mcp.api.CompletionCall;
import eu.hansolo.mcp.api.McpCompletionHandler;
import eu.hansolo.mcp.api.McpToolHandler;
import eu.hansolo.mcp.api.PromptCall;
import eu.hansolo.mcp.api.PromptResult;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.server.McpConfig;
import eu.hansolo.mcp.server.McpNotifications;
import eu.hansolo.mcp.server.McpRequestStates;
import eu.hansolo.mcp.tools.BeanSource;
import eu.hansolo.mcp.tools.CompletionScanner;
import eu.hansolo.mcp.tools.PromptScanner;
import eu.hansolo.mcp.tools.ResourceScanner;
import eu.hansolo.mcp.tools.ToolScanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the MCP-facing classes through the library's scanners, against an in-memory store. */
class TicketsTest {

    private static final JsonObject NONE  = Json.createObjectBuilder().build();
    private static final JsonObject ELICIT = Json.createObjectBuilder().add("elicitation", Json.createObjectBuilder()).build();
    private static final Caller     ALICE = new Caller("alice", Set.of("tickets:read", "tickets:write"), NONE);

    private final FakeTicketStore store         = new FakeTicketStore();
    private final McpNotifications notifications = new McpNotifications(new McpConfig(null, 1024, 0L, "t", "1", null));
    private final Map<String, McpToolHandler> tools = ToolScanner.scan(TicketTools.class, BeanSource.of(new TicketTools(store, notifications, new McpRequestStates(new McpConfig())))).stream().collect(Collectors.toMap(McpToolHandler::name, t -> t));


    private TicketsTest seeded() {
        for (int i = 1; i <= 25; i++) {
            store.create("Printer " + i, i % 2 == 0 ? "toner" : "paper", Priority.MEDIUM, "bob");
        }
        return this;
    }

    private ToolResult call(String tool, JsonObject arguments, JsonObject capabilities, JsonObject inputResponses) throws Exception {
        return tools.get(tool).call(new ToolCall(tool, arguments, capabilities, null, inputResponses, null, ALICE));
    }

    private ToolResult.Complete complete(String tool, JsonObject arguments) throws Exception {
        return (ToolResult.Complete) call(tool, arguments, NONE, null);
    }

    /** A rejected input comes back as an error result the model can read, not as an exception. */
    private static String error(ToolResult.Complete result) {
        assertTrue(result.isError());
        return result.content().getJsonObject(0).getString("text");
    }

    @Test
    void toolsDeclareTheirScopes() throws Exception {
        assertEquals(Set.of("search_tickets", "get_ticket", "create_ticket", "close_ticket"), tools.keySet());
        assertEquals(Set.of("tickets:read"), tools.get("search_tickets").requiredScopes());
        assertEquals(Set.of("tickets:read"), tools.get("get_ticket").requiredScopes());
        assertEquals(Set.of("tickets:write"), tools.get("create_ticket").requiredScopes());
        assertEquals(Set.of("tickets:write"), tools.get("close_ticket").requiredScopes());
    }

    @Test
    void searchPagesWithOffset() throws Exception {
        seeded();
        JsonObject first = complete("search_tickets", Json.createObjectBuilder().add("limit", 10).build()).structuredContent().asJsonObject();
        assertEquals(25, first.getInt("total"));
        assertEquals(10, first.getJsonArray("tickets").size());
        assertEquals(25, first.getJsonArray("tickets").getJsonObject(0).getInt("id"));
        assertEquals(10, first.getInt("nextOffset"));
        JsonObject last = complete("search_tickets", Json.createObjectBuilder().add("limit", 10).add("offset", 20).build()).structuredContent().asJsonObject();
        assertEquals(5, last.getJsonArray("tickets").size());
        assertTrue(!last.containsKey("nextOffset"));
    }

    @Test
    void searchFiltersByTextAndStatus() throws Exception {
        seeded();
        store.close(2, "done");
        JsonObject toner = complete("search_tickets", Json.createObjectBuilder().add("text", "TONER").build()).structuredContent().asJsonObject();
        assertEquals(12, toner.getInt("total"));
        JsonObject closed = complete("search_tickets", Json.createObjectBuilder().add("status", "CLOSED").build()).structuredContent().asJsonObject();
        assertEquals(1, closed.getInt("total"));
    }

    @Test
    void searchRejectsBadPaging() throws Exception {
        assertTrue(error(complete("search_tickets", Json.createObjectBuilder().add("limit", 0).build())).contains("limit"));
        assertTrue(error(complete("search_tickets", Json.createObjectBuilder().add("limit", 51).build())).contains("limit"));
        assertTrue(error(complete("search_tickets", Json.createObjectBuilder().add("offset", -1).build())).contains("offset"));
    }

    @Test
    void createRecordsTheCaller() throws Exception {
        JsonObject created = complete("create_ticket", Json.createObjectBuilder().add("title", " Broken ").add("description", "It is").add("priority", "HIGH").build()).structuredContent().asJsonObject();
        assertEquals("Broken", created.getString("title"));
        assertEquals("alice", created.getString("createdBy"));
        assertEquals("HIGH", created.getString("priority"));
        assertEquals("OPEN", created.getString("status"));
        assertEquals("MEDIUM", complete("create_ticket", Json.createObjectBuilder().add("title", "t").add("description", "d").build()).structuredContent().asJsonObject().getString("priority"));
        assertTrue(error(complete("create_ticket", Json.createObjectBuilder().add("title", "  ").add("description", "d").build())).contains("title"));
    }

    @Test
    void getUnknownTicketIsAModelVisibleError() throws Exception {
        assertTrue(error(complete("get_ticket", Json.createObjectBuilder().add("id", 99).build())).contains("no ticket 99"));
    }

    private static JsonObject answer(String action) {
        return Json.createObjectBuilder().add("confirm", Json.createObjectBuilder().add("action", action)).build();
    }

    /** The second half of the round trip: the same call again, with the user's answer and the state the server handed out. */
    private ToolResult retry(JsonObject arguments, ToolResult.InputRequired asked, String action) throws Exception {
        return tools.get("close_ticket").call(new ToolCall("close_ticket", arguments, ELICIT, null, answer(action), asked.requestState(), ALICE));
    }

    @Test
    void closeAsksFirstThenClosesOnAccept() throws Exception {
        seeded();
        JsonObject args = Json.createObjectBuilder().add("id", 3).add("resolution", "replaced toner").build();
        ToolResult first = call("close_ticket", args, ELICIT, null);
        ToolResult.InputRequired asked = (ToolResult.InputRequired) first;
        assertEquals("elicitation/create", asked.inputRequests().getJsonObject("confirm").getString("method"));
        assertTrue(asked.inputRequests().getJsonObject("confirm").getJsonObject("params").getJsonObject("requestedSchema").getJsonObject("properties").isEmpty());
        assertTrue(asked.requestState() != null && !asked.requestState().isBlank());
        assertEquals("OPEN", store.find(3).orElseThrow().status());
        ToolResult.Complete done = (ToolResult.Complete) retry(args, asked, "accept");
        assertTrue(!done.isError());
        assertEquals("CLOSED", store.find(3).orElseThrow().status());
        assertEquals("replaced toner", store.find(3).orElseThrow().resolution().orElseThrow());
    }

    @Test
    void closeDeclinedLeavesTheTicketOpen() throws Exception {
        seeded();
        JsonObject args = Json.createObjectBuilder().add("id", 3).build();
        ToolResult.InputRequired asked = (ToolResult.InputRequired) call("close_ticket", args, ELICIT, null);
        ToolResult.Complete result = (ToolResult.Complete) retry(args, asked, "decline");
        assertTrue(result.isError());
        assertEquals("OPEN", store.find(3).orElseThrow().status());
    }

    @Test
    void anAnswerWithoutTheStateIsRefused() throws Exception {
        seeded();
        JsonObject args = Json.createObjectBuilder().add("id", 3).build();
        McpProtocolException e = assertThrows(McpProtocolException.class, () -> call("close_ticket", args, ELICIT, answer("accept")));
        assertEquals(-32602, e.code());
        assertEquals("OPEN", store.find(3).orElseThrow().status());
    }

    @Test
    void anAnswerForOneTicketDoesNotCloseAnother() throws Exception {
        seeded();
        ToolResult.InputRequired asked = (ToolResult.InputRequired) call("close_ticket", Json.createObjectBuilder().add("id", 3).build(), ELICIT, null);
        JsonObject other = Json.createObjectBuilder().add("id", 4).build();
        ToolResult again = retry(other, asked, "accept");
        assertTrue(again instanceof ToolResult.InputRequired, "the user is asked about ticket 4, nothing is closed");
        assertEquals("OPEN", store.find(3).orElseThrow().status());
        assertEquals("OPEN", store.find(4).orElseThrow().status());
        JsonObject otherResolution = Json.createObjectBuilder().add("id", 3).add("resolution", "something else").build();
        assertTrue(retry(otherResolution, asked, "accept") instanceof ToolResult.InputRequired, "a changed resolution is a new question too");
        assertEquals("OPEN", store.find(3).orElseThrow().status());
    }

    @Test
    void aStateIssuedToAnotherCallerIsRefused() throws Exception {
        seeded();
        JsonObject args = Json.createObjectBuilder().add("id", 3).build();
        ToolResult.InputRequired asked = (ToolResult.InputRequired) call("close_ticket", args, ELICIT, null);
        Caller mallory = new Caller("mallory", Set.of("tickets:read", "tickets:write"), NONE);
        McpProtocolException e = assertThrows(McpProtocolException.class, () -> tools.get("close_ticket").call(new ToolCall("close_ticket", args, ELICIT, null, answer("accept"), asked.requestState(), mallory)));
        assertEquals(-32602, e.code());
        assertEquals("OPEN", store.find(3).orElseThrow().status());
    }

    @Test
    void aTicketTitleThatLooksSensitiveDoesNotBreakTheQuestion() throws Exception {
        long id = store.create("Password reset for bob", "locked out", Priority.HIGH, "bob").id();
        assertTrue(call("close_ticket", Json.createObjectBuilder().add("id", id).build(), ELICIT, null) instanceof ToolResult.InputRequired);
    }

    @Test
    void closeWithoutElicitationSupportIsRefused() throws Exception {
        seeded();
        McpProtocolException e = assertThrows(McpProtocolException.class, () -> call("close_ticket", Json.createObjectBuilder().add("id", 3).build(), NONE, null));
        assertEquals(400, e.httpStatus());
        assertEquals("OPEN", store.find(3).orElseThrow().status());
    }

    private ResourceResult.Complete read(String uri, Map<String, String> variables) throws Exception {
        ResourceScanner.Found found = ResourceScanner.scan(TicketResources.class, BeanSource.of(new TicketResources(store)));
        ResourceCall call = new ResourceCall(uri, variables, NONE, null, null, null, ALICE);
        return (ResourceResult.Complete) (variables.isEmpty() ? found.resources().get(0).read(call) : found.templates().get(0).read(call));
    }

    @Test
    void resourcesServeTicketsAndStats() throws Exception {
        seeded();
        store.close(1, "x");
        assertTrue(read("ticket://2", Map.of("id", "2")).contents().get(0).text().contains("\"title\":\"Printer 2\""));
        String stats = read("ticket://stats", Map.of()).contents().get(0).text();
        assertTrue(stats.contains("\"closed\":1") && stats.contains("\"open\":24"));
    }

    @Test
    void resourceErrors() throws Exception {
        seeded();
        McpProtocolException missing = assertThrows(McpProtocolException.class, () -> read("ticket://99", Map.of("id", "99")));
        assertEquals(200, missing.httpStatus());
        assertEquals("ticket://99", missing.data().asJsonObject().getString("uri"));
        assertEquals(-32602, assertThrows(McpProtocolException.class, () -> read("ticket://abc", Map.of("id", "abc"))).code());
    }

    @Test
    void triagePromptContainsTheTicket() throws Exception {
        seeded();
        var prompt = PromptScanner.scan(TicketPrompts.class, BeanSource.of(new TicketPrompts(store))).get(0);
        assertEquals("triage", prompt.name());
        assertEquals(Set.of("tickets:read"), prompt.requiredScopes());
        PromptResult.Complete result = (PromptResult.Complete) prompt.get(new PromptCall("triage", Map.of("ticketId", "4"), NONE, null, null, null, ALICE));
        String text = result.messages().get(0).content().getString("text");
        assertTrue(text.contains("Ticket 4") && text.contains("Printer 4") && text.contains("toner"));
        assertThrows(McpProtocolException.class, () -> prompt.get(new PromptCall("triage", Map.of("ticketId", "x"), NONE, null, null, null, ALICE)));
        assertThrows(McpProtocolException.class, () -> prompt.get(new PromptCall("triage", Map.of("ticketId", "999"), NONE, null, null, null, ALICE)));
    }

    @Test
    void completionSuggestsTicketNumbers() throws Exception {
        seeded();
        List<McpCompletionHandler> handlers = CompletionScanner.scan(TicketCompletions.class, BeanSource.of(new TicketCompletions(store)));
        assertEquals(2, handlers.size());
        for (McpCompletionHandler h : handlers) {
            assertEquals(Set.of("tickets:read"), h.requiredScopes());
            CompletionCall call = new CompletionCall(h.kind(), h.target(), h.argument(), "1", Map.of(), NONE, null, ALICE);
            assertEquals(List.of("1", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19"), h.complete(call).values());
        }
        assertTrue(handlers.stream().anyMatch(h -> h.kind() == McpCompletionHandler.Kind.PROMPT && h.target().equals("triage")));
        assertTrue(handlers.stream().anyMatch(h -> h.kind() == McpCompletionHandler.Kind.RESOURCE_TEMPLATE && h.target().equals("ticket://{id}")));
    }
}
