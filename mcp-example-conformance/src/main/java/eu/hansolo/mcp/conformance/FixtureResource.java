package eu.hansolo.mcp.conformance;

import eu.hansolo.mcp.api.McpResourceHandler;
import eu.hansolo.mcp.api.McpResourceTemplateHandler;
import eu.hansolo.mcp.api.ResourceCall;
import eu.hansolo.mcp.api.ResourceResult;

/** Fixed resources and resource templates defined by a function. */
final class FixtureResource {

    @FunctionalInterface
    interface Body {
        ResourceResult read(ResourceCall call) throws Exception;
    }

    private FixtureResource() {
    }


    static McpResourceHandler fixed(String uri, String name, String mimeType, Body body) {
        return new McpResourceHandler() {
            @Override
            public String uri() {
                return uri;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String mimeType() {
                return mimeType;
            }

            @Override
            public ResourceResult read(ResourceCall call) throws Exception {
                return body.read(call);
            }
        };
    }

    static McpResourceTemplateHandler template(String uriTemplate, String name, String mimeType, Body body) {
        return new McpResourceTemplateHandler() {
            @Override
            public String uriTemplate() {
                return uriTemplate;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String mimeType() {
                return mimeType;
            }

            @Override
            public ResourceResult read(ResourceCall call) throws Exception {
                return body.read(call);
            }
        };
    }
}
