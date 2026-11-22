package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;

/** Tools that return a JAX-RS {@code Response}, as a service method written for a REST resource does. */
@ApplicationScoped
public class ResponseTools {

    public record Booking(String duck, int times) {
    }


    @McpTool(description = "Returns 200 with an entity")
    public Response response_ok(@McpParam(description = "Name of the duck") String duck) {
        return Response.ok(new Booking(duck, 2)).build();
    }

    @McpTool(description = "Returns 204 without an entity")
    public Response response_empty() {
        return Response.noContent().build();
    }

    @McpTool(description = "Returns 404 with a text")
    public Response response_not_found(@McpParam(description = "Name of the duck") String duck) {
        return Response.status(Response.Status.NOT_FOUND).entity("No duck named " + duck).build();
    }

    @McpTool(description = "Returns 409 without an entity")
    public Response response_conflict() {
        return Response.status(Response.Status.CONFLICT).build();
    }

    @McpTool(description = "Returns 500 with an internal text")
    public Response response_failure() {
        return Response.serverError().entity("jdbc:postgresql://db.internal/ducks password=hunter2").build();
    }
}
