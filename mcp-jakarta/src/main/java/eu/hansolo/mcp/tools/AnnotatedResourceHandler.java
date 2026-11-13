package eu.hansolo.mcp.tools;

import jakarta.json.JsonObject;
import eu.hansolo.mcp.api.Icon;
import eu.hansolo.mcp.api.McpResource;
import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A fixed resource backed by one {@link McpResource} method.
 *
 * <p><b>Internal.</b> Not part of the stable API: this class is public only because other packages of this library use it,
 * and it can change in any release. Applications should not call it.
 */
public final class AnnotatedResourceHandler implements McpResourceHandler {

    private final String         uri;
    private final String         name;
    private final String         title;
    private final String         description;
    private final Set<String>    scopes;
    private final ResourceMethod target;
    private final List<Icon>     icons;
    private final JsonObject     meta;


    AnnotatedResourceHandler(Method method, BeanSource source) {
        McpResource resource = method.getAnnotation(McpResource.class);
        this.uri         = resource.uri();
        this.name        = resource.name().isEmpty() ? method.getName() : resource.name();
        this.title       = resource.title().isEmpty() ? null : resource.title();
        this.description = resource.description().isEmpty() ? null : resource.description();
        this.scopes      = Set.copyOf(new LinkedHashSet<>(List.of(resource.scopes())));
        this.target      = new ResourceMethod(method, source, resource.mimeType(), null);
        String where     = method.getDeclaringClass().getName() + "." + method.getName();
        this.icons       = Descriptors.icons(resource.icons(), where);
        this.meta        = Descriptors.meta(resource.meta(), where);
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
    public String uri() {
        return uri;
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
    public JsonObject annotations() {
        return null;
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
