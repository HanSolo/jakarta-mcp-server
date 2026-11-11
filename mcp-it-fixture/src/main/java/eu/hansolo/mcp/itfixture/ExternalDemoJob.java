package eu.hansolo.mcp.itfixture;

import eu.hansolo.mcp.api.ExternalJob;
import eu.hansolo.mcp.api.ExternalJobStatus;
import eu.hansolo.mcp.api.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonObject;

/** Follows the jobs of {@link ExternalDemoTool}: a job is done when its time stamp has passed. */
@ApplicationScoped
public class ExternalDemoJob implements ExternalJob {

    @Override
    public String tool() {
        return "external_demo";
    }

    @Override
    public ExternalJobStatus poll(String taskId, JsonObject handle) {
        long left = handle.getJsonNumber("readyAt").longValue() - System.currentTimeMillis();
        return left <= 0 ? ExternalJobStatus.completed(ToolResult.text("external job finished")) : ExternalJobStatus.running("about " + (left / 1000 + 1) + " s to go");
    }
}
