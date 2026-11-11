package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.RequestScoped;

/** A request-scoped bean: a tool that reads it fails with ContextNotActiveException when it is called outside a request. */
@RequestScoped
public class RequestTag {

    public String value() {
        return "tagged";
    }
}
