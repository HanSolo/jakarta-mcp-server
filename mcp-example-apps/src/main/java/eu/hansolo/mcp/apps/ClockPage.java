package eu.hansolo.mcp.apps;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.Apps;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The page behind {@link ClockTool} ({@code clock.html} next to this class). It is one self-contained file with no external
 * origins, so the {@code _meta} declares none and a client applies its default Content-Security-Policy. It speaks the MCP Apps host
 * protocol (JSON-RPC over {@code postMessage}): it shows the structured result of the tool call and calls {@link RefreshClockTool}.
 */
@ApplicationScoped
public class ClockPage implements McpResourceHandler {

    static final String URI = "ui://clock/app.html";

    private static final String HTML = load();


    private static String load() {
        try (InputStream in = ClockPage.class.getResourceAsStream("clock.html")) {
            if (in == null) {
                throw new IllegalStateException("clock.html is missing from the WAR");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("clock.html could not be read", e);
        }
    }


    @Override
    public String uri() {
        return URI;
    }

    @Override
    public String name() {
        return "clock-page";
    }

    @Override
    public String title() {
        return "Clock";
    }

    @Override
    public String mimeType() {
        return Apps.MIME_TYPE;
    }

    @Override
    public ResourceResult read(ResourceCall call) {
        return ResourceResult.of(Apps.html(URI, HTML, Apps.resourceMeta().prefersBorder(false).build()));
    }
}
