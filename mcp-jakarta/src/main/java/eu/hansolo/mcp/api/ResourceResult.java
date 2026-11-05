package eu.hansolo.mcp.api;

import jakarta.json.JsonObject;

import java.util.List;

/** The outcome of reading a resource: the contents, or a request for more input from the client (multi round-trip). */
public sealed interface ResourceResult permits ResourceResult.Complete, ResourceResult.InputRequired {

    /**
     * @param contents   what was read; do not return an empty list for a resource that does not exist (throw instead)
     * @param ttlMs      how long clients may cache this read, or null for 0 (immediately stale)
     * @param cacheScope {@code "public"} or {@code "private"}, or null for the server default ({@code private} when authentication is on)
     */
    record Complete(List<ResourceContent> contents, Long ttlMs, String cacheScope) implements ResourceResult {

        public Complete {
            contents = List.copyOf(contents);
            if (ttlMs != null && ttlMs < 0) {
                throw new IllegalArgumentException("ttlMs must not be negative");
            }
            if (cacheScope != null && !cacheScope.equals("public") && !cacheScope.equals("private")) {
                throw new IllegalArgumentException("cacheScope must be \"public\" or \"private\"");
            }
        }


        /** The same contents with explicit caching hints. */
        public Complete withCache(long ttlMs, String cacheScope) {
            return new Complete(contents, ttlMs, cacheScope);
        }
    }


    /** Multi Round-Trip Request, as for tools: the client retries the same read with {@code inputResponses} attached. */
    record InputRequired(JsonObject inputRequests, String requestState) implements ResourceResult {

        public InputRequired {
            if (inputRequests == null && requestState == null) {
                throw new IllegalArgumentException("inputRequests or requestState is required");
            }
        }
    }

    static Complete of(ResourceContent... contents) {
        return new Complete(List.of(contents), null, null);
    }

    static Complete of(List<ResourceContent> contents) {
        return new Complete(contents, null, null);
    }

    static Complete text(String uri, String mimeType, String text) {
        return of(ResourceContent.text(uri, mimeType, text));
    }

    static InputRequired inputRequired(JsonObject inputRequests, String requestState) {
        return new InputRequired(inputRequests, requestState);
    }
}
