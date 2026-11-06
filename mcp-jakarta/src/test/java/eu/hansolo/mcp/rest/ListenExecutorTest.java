package eu.hansolo.mcp.rest;

import eu.hansolo.mcp.server.McpConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListenExecutorTest {

    @Test
    void theDefaultRunsEachTaskOnItsOwnVirtualThread() throws Exception {
        ListenExecutor listeners = new ListenExecutor(new McpConfig());
        try {
            assertTrue(runsOnVirtualThread(listeners.executor()));
            assertTrue(listeners.executor() == listeners.executor(), "one executor, resolved once");
        } finally {
            listeners.close();
        }
    }

    @Test
    void aNameThatCannotBeLookedUpFallsBackToVirtualThreads() throws Exception {
        McpConfig config = new McpConfig(null, 4096, 0L, "t", "1", null, 0, 15_000L, 100, 10, true, "java:app/concurrent/DoesNotExist");
        assertEquals("java:app/concurrent/DoesNotExist", config.listenExecutor());
        ListenExecutor listeners = new ListenExecutor(config);
        try {
            assertTrue(runsOnVirtualThread(listeners.executor()));
        } finally {
            listeners.close();
        }
    }

    @Test
    void closingInterruptsTheStreamsTheServerStartedItself() throws Exception {
        ListenExecutor listeners = new ListenExecutor(new McpConfig());
        CountDownLatch started     = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        listeners.executor().execute(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        listeners.close();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void noExecutorNameIsTheDefault() {
        assertNull(new McpConfig().listenExecutor());
        assertEquals(false, new McpConfig().listenAsync());
    }

    private static boolean runsOnVirtualThread(Executor executor) throws InterruptedException {
        AtomicBoolean virtual = new AtomicBoolean();
        CountDownLatch done   = new CountDownLatch(1);
        executor.execute(() -> {
            virtual.set(Thread.currentThread().isVirtual());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return virtual.get();
    }
}
