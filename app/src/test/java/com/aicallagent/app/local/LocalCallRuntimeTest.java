package com.aicallagent.app.local;

import com.aicallagent.app.model.CallConversation;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class LocalCallRuntimeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    @After public void cleanup() throws Exception {
        worker.shutdownNow(); control.shutdownNow();
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        assertTrue(control.awaitTermination(3, TimeUnit.SECONDS));
    }
    private static class Driver implements LocalModelSession.Driver {
        final AtomicInteger closes = new AtomicInteger(), loads = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        volatile boolean block, cancelCalled;
        volatile ReplyRequest actual;
        @Override public void load(File file, boolean gpu) { loads.incrementAndGet(); }
        @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) throws Exception {
            actual = request; entered.countDown();
            if (block && !finish.await(3, TimeUnit.SECONDS)) throw new IOException("test stalled");
            return "어느 지역의 공사인가요?";
        }
        @Override public void cancel() { cancelCalled = true; }
        @Override public void close() { closes.incrementAndGet(); }
    }
    private LocalModelRuntime runtime(Driver driver) throws Exception {
        LocalModelStore store = new LocalModelStore(temp.newFolder(), 1024);
        byte[] bytes = "LITERTLMfake".getBytes(StandardCharsets.US_ASCII);
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        store.importModel(new ByteArrayInputStream(bytes), bytes.length, hash, () -> false);
        return new LocalModelRuntime(store, driver, worker, control, () -> false, gpu -> { });
    }
    private void prepare(LocalModelRuntime runtime, Object owner) throws Exception {
        CompletableFuture<String> prepared = new CompletableFuture<>();
        assertTrue(runtime.prepareForCalls(owner, (answer, error) -> prepared.complete(error)));
        assertEquals("", prepared.get(3, TimeUnit.SECONDS));
        assertTrue(runtime.isReady(owner));
    }
    @Test public void settingsScreenCannotUnloadOrReplaceCallOwnedModel() throws Exception {
        Driver driver = new Driver(); LocalModelRuntime runtime = runtime(driver); Object owner = new Object();
        prepare(runtime, owner);
        int closes = driver.closes.get();
        runtime.release(); runtime.load(true); runtime.generate();
        assertTrue(runtime.isReady(owner));
        assertEquals(closes, driver.closes.get()); assertEquals(1, driver.loads.get());
        assertFalse(runtime.prepareForCalls(new Object(), (a, e) -> fail()));
    }
    @Test public void callUsesRealHistoryAndClearsDiagnosticAnswerAfterCallback() throws Exception {
        Driver driver = new Driver(); LocalModelRuntime runtime = runtime(driver); Object owner = new Object();
        prepare(runtime, owner);
        ReplyRequest request = ReplyRequest.forCall(List.of(new CallConversation.Message("user", "주방 공사입니다.")));
        CompletableFuture<String> result = new CompletableFuture<>();
        assertTrue(runtime.generate(owner, request, (answer, error) -> result.complete(error.isEmpty() ? answer : error)));
        assertEquals("어느 지역의 공사인가요?", result.get(3, TimeUnit.SECONDS));
        assertSame(request, driver.actual);
        assertEquals("주방 공사입니다.", driver.actual.latest().content);
        assertEquals("", runtime.session.snapshot().answer);
    }
    @Test public void cancelledReplyDrainsBeforeAnotherRequestAndIsNeverReturnedAsSuccess() throws Exception {
        Driver driver = new Driver(); driver.block = true;
        LocalModelRuntime runtime = runtime(driver); Object owner = new Object(); prepare(runtime, owner);
        CompletableFuture<String> result = new CompletableFuture<>();
        assertTrue(runtime.generate(owner, ReplyRequest.test(), (answer, error) -> result.complete(answer + error)));
        assertTrue(driver.entered.await(2, TimeUnit.SECONDS));
        runtime.cancel(owner);
        assertFalse(runtime.generate(owner, ReplyRequest.test(), (a, e) -> fail()));
        driver.finish.countDown();
        assertEquals("CANCELLED", result.get(3, TimeUnit.SECONDS));
        assertTrue(runtime.isReady(owner));
    }
    @Test public void releaseDuringPreparationCannotLeaveModelReadyOrKeepOwnership() throws Exception {
        CountDownLatch loadEntered = new CountDownLatch(1), loadFinish = new CountDownLatch(1);
        Driver driver = new Driver() {
            @Override public void load(File file, boolean gpu) {
                loadEntered.countDown();
                try { if (!loadFinish.await(3, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            }
        };
        LocalModelRuntime runtime = runtime(driver); Object owner = new Object();
        CompletableFuture<String> result = new CompletableFuture<>();
        assertTrue(runtime.prepareForCalls(owner, (answer, error) -> result.complete(error)));
        assertTrue(loadEntered.await(2, TimeUnit.SECONDS));
        runtime.release(owner); loadFinish.countDown();
        assertEquals("CANCELLED", result.get(3, TimeUnit.SECONDS));
        assertFalse(runtime.callOwned()); assertFalse(runtime.isReady(owner));
        assertEquals(LocalModelSession.State.EMPTY, runtime.session.snapshot().state);
    }
}
