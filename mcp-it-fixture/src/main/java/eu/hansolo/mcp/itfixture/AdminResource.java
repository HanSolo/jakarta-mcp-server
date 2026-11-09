package eu.hansolo.mcp.itfixture;

import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import eu.hansolo.mcp.server.McpChange;
import eu.hansolo.mcp.server.McpNotifications;
import eu.hansolo.mcp.server.McpRegistrations;

/**
 * <b>Test fixture only: no authentication, by design.</b> It lets an integration test do what an application would do in its own
 * code: add and remove tools while the server runs, announce changes, and receive a change from another instance.
 */
@Path("/admin")
public class AdminResource {

    @Inject McpRegistrations registrations;
    @Inject McpNotifications notifications;


    /** Adds a tool with this name that answers "hello from <name>". 409 if the name is taken. */
    @POST
    @Path("/tools/{name}")
    public Response addTool(@PathParam("name") String name) {
        try {
            registrations.addTool(new DynamicTool(name));
            return Response.noContent().build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        }
    }

    /** Removes the tool; 404 if there was none. */
    @DELETE
    @Path("/tools/{name}")
    public Response removeTool(@PathParam("name") String name) {
        return registrations.removeTool(name) ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    /** Announces a change of the tools list the way application code does: to the local listeners and to the bridge. */
    @POST
    @Path("/announce/tools")
    public Response announceTools() {
        notifications.toolsListChanged();
        return Response.noContent().build();
    }

    /** The receiving end of {@link HttpBridge}: the body is {@code McpChange.encode()} of a change another instance made. */
    @POST
    @Path("/changes")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response receive(String body) {
        try {
            notifications.receive(McpChange.decode(body));
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/instance")
    @Produces(MediaType.APPLICATION_JSON)
    public String instance() {
        return Json.createObjectBuilder().add("instanceId", notifications.instanceId()).add("activeSubscriptions", notifications.activeSubscriptions()).build().toString();
    }
}
