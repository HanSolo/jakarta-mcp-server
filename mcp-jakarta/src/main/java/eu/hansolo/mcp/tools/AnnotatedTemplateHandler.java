package eu.hansolo.mcp.tools;

import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.McpResource;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;
import eu.hansolo.mcp.server.UriTemplate;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A resource template backed by one {@link McpResource} method whose {@code uriTemplate} is set. */
public final class AnnotatedTemplateHandler implements McpResourceTemplateHandler {

    private final String         uriTemplate;
    private final String         name;
    private final String         title;
    private final String         description;
    private final Set<String>    scopes;
    private final ResourceMethod target;
    private final List<Icon>     icons;
    private final JsonObject     meta;


    AnnotatedTemplateHandler(Method method, BeanSource source) {
        McpResource resource = method.getAnnotation(McpResource.class);
        this.uriTemplate = resource.uriTemplate();
        this.name        = resource.name().isEmpty() ? method.getName() : resource.name();
        this.title       = resource.title().isEmpty() ? null : resource.title();
        this.description = resource.description().isEmpty() ? null : resource.description();
        this.scopes      = Set.copyOf(new LinkedHashSet<>(List.of(resource.scopes())));
        String where     = method.getDeclaringClass().getName() + "." + method.getName();
        this.icons       = Descriptors.icons(resource.icons(), where);
        this.meta        = Descriptors.meta(resource.meta(), where);
        try {
            this.target = new ResourceMethod(method, source, resource.mimeType(), UriTemplate.compile(uriTemplate));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(method.getDeclaringClass().getName() + "." + method.getName() + ": " + e.getMessage(), e);
        }
    }


    @Override
    public List<Icon> icons() {
        return icons;
    }

    @Override
    public JsonObject meta() {
        return meta;
    }

    @Override
    public String uriTemplate() {
        return uriTemplate;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String mimeType() {
        return target.mimeType().isEmpty() ? null : target.mimeType();
    }

    @Override
    public Set<String> requiredScopes() {
        return scopes;
    }

    @Override
    public ResourceResult read(ResourceCall call) throws Exception {
        return target.read(call);
    }
}
