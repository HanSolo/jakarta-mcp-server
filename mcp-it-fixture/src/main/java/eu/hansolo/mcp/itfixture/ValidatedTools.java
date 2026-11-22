package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import eu.hansolo.mcp.api.McpTool;

/** Bean Validation constraints on tool parameters: some become schema keywords, one only the container can check. */
@ApplicationScoped
public class ValidatedTools {

    @McpTool(description = "Books a duck; the constraints are in the schema")
    public String validated_book(@NotBlank @Size(max = 10) String duck, @Min(1) @Max(5) int times) {
        return "booked " + duck + " x" + times;
    }

    @McpTool(description = "Needs a confirmation that only the container's method validation can check")
    public String validated_confirm(@AssertTrue boolean confirmed) {
        return "confirmed";
    }
}
