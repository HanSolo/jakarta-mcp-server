package eu.hansolo.mcp.server;

import java.io.IOException;

/**
 * The sink of a streamed {@code tools/call}, shared by the tool (progress events) and the keep-alive thread (comment lines). One lock
 * keeps their writes from interleaving, and the time of the last write tells the keep-alive when the stream has been quiet long enough.
 * After {@link #close()} the keep-alive can no longer write, so no comment follows the final response.
 */
final class GuardedSink implements McpHttpResponse.EventSink {

    private final McpHttpResponse.EventSink target;
    private long                            lastWrite = System.nanoTime();
    private boolean                         closed;


    GuardedSink(McpHttpResponse.EventSink target) {
        this.target = target;
    }


    @Override
    public synchronized void send(String json) throws IOException {
        target.send(json);
        lastWrite = System.nanoTime();
    }

    /** Writes a comment line, unless the sink is closed. */
    @Override
    public synchronized void comment(String text) throws IOException {
        if (!closed) {
            target.comment(text);
            lastWrite = System.nanoTime();
        }
    }

    synchronized long idleMillis() {
        return (System.nanoTime() - lastWrite) / 1_000_000L;
    }

    synchronized void close() {
        closed = true;
    }
}
