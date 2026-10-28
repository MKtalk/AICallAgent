package com.aicallagent.app.local;

import org.junit.Test;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class LocalModelSessionTest {
    @Test public void clearAnswerWaitsForSessionLock() throws Exception {
        LocalModelSession session = new LocalModelSession(new Driver());
        session.load(new File("model.litertlm"), false);
        session.generate();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> { started.countDown(); session.clearAnswer(); });
        synchronized (session) {
            worker.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            worker.join(200);
            assertTrue("clearAnswer must wait for the session lock", worker.isAlive());
            assertFalse(session.snapshot().answer.isEmpty());
        }
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertEquals("", session.snapshot().answer);
    }

    @Test public void englishTestGenerationPassesEnglishRequestToDriver() throws Exception {
        final ReplyRequest[] captured = new ReplyRequest[1];
        LocalModelSession session = new LocalModelSession(new Driver() {
            @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) {
                captured[0] = request;
                return "Apple";
            }
        });
        session.load(new File("model.litertlm"), false);
        session.generate(false);
        assertNotNull(captured[0]);
        assertTrue(captured[0].englishTest);
        assertEquals("Apple", session.snapshot().answer);
    }
    private static class Driver implements LocalModelSession.Driver {
        final CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        boolean block, failLoad;
        volatile boolean cancelCalled;
        @Override public void load(File file, boolean gpu) throws Exception {
            if (failLoad) throw new IllegalStateException();
            closed.set(false);
        }
        @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) throws Exception {
            entered.countDown();
            if (block && !finish.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test stalled");
            return "어떤 욕실 공사를 원하시나요?";
        }
        @Override public void cancel() { cancelCalled = true; }
        @Override public void close() { closed.set(true); }
    }
    @Test public void invalidAndIncompleteOutputKeepEngineReadyForNextTurn() {
        for (String code : new String[]{"INVALID_RESPONSE", "INCOMPLETE_RESPONSE"}) {
            Driver driver = new Driver() {
                int calls;
                @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) throws Exception {
                    if (calls++ == 0) throw new ReplyFailure(code);
                    return "자장가";
                }
            };
            LocalModelSession session = new LocalModelSession(driver);
            session.load(new File("model.litertlm"), false);
            session.generate();
            assertEquals(code, session.snapshot().error);
            assertEquals(LocalModelSession.State.READY, session.snapshot().state);
            assertEquals("", session.snapshot().answer);
            assertFalse(driver.closed.get());
            session.generate();
            assertEquals("자장가", session.snapshot().answer);
            assertEquals("", session.snapshot().error);
        }
    }

    @Test public void executionFailureStillReleasesEngineAndStopsGeneration() {
        Driver driver = new Driver() {
            @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) {
                throw new IllegalStateException("private native text");
            }
        };
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false);
        session.generate();
        assertEquals("GENERATION_FAILED", session.snapshot().error);
        assertEquals(LocalModelSession.State.ERROR, session.snapshot().state);
        assertTrue(driver.closed.get());
    }

    @Test public void cancellationKeepsSessionBusyUntilNativeCallReturnsAndDiscardsAnswer() throws Exception {
        Driver driver = new Driver(); driver.block = true;
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false);
        Thread worker = new Thread(session::generate); worker.start();
        assertTrue(driver.entered.await(2, TimeUnit.SECONDS));
        assertFalse(session.canStart());
        session.cancel();
        assertTrue(driver.cancelCalled);
        assertEquals(LocalModelSession.State.CANCELLING, session.snapshot().state);
        assertFalse(session.canStart());
        assertFalse(driver.closed.get());
        driver.finish.countDown(); worker.join(2000);
        assertFalse(worker.isAlive());
        assertEquals(LocalModelSession.State.READY, session.snapshot().state);
        assertEquals("", session.snapshot().answer);
    }
    @Test public void leavingScreenWaitsForGenerationThenReleasesEngine() throws Exception {
        Driver driver = new Driver(); driver.block = true;
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false);
        Thread worker = new Thread(session::generate); worker.start();
        assertTrue(driver.entered.await(2, TimeUnit.SECONDS));
        session.cancelAndClose();
        assertFalse(driver.closed.get());
        driver.finish.countDown(); worker.join(2000);
        assertTrue(driver.closed.get());
        assertEquals(LocalModelSession.State.EMPTY, session.snapshot().state);
    }
    @Test public void loadFailureCanBeRetriedAndSuccessfulGenerationHasNoPreviousHistory() {
        Driver driver = new Driver(); driver.failLoad = true;
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false);
        assertEquals(LocalModelSession.State.ERROR, session.snapshot().state);
        driver.failLoad = false;
        session.load(new File("model.litertlm"), true);
        session.generate();
        assertEquals(LocalModelSession.State.READY, session.snapshot().state);
        assertFalse(session.snapshot().answer.isEmpty());
        session.cancelAndClose();
        assertEquals("", session.snapshot().answer);
    }
    @Test public void generationBeforeLoadIsRejectedWithoutCallingNativeDriver() {
        Driver driver = new Driver(); LocalModelSession session = new LocalModelSession(driver);
        session.generate();
        assertEquals(1, driver.entered.getCount());
        assertEquals(LocalModelSession.State.EMPTY, session.snapshot().state);
    }
    @Test public void delayedCancellationCannotCancelNewGeneration() throws Exception {
        Driver driver = new Driver(); driver.block = true;
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false);
        long previousVersion = session.snapshot().version;
        Thread worker = new Thread(session::generate); worker.start();
        assertTrue(driver.entered.await(2, TimeUnit.SECONDS));
        session.cancel(previousVersion);
        assertFalse(driver.cancelCalled);
        driver.finish.countDown(); worker.join(2000);
        assertFalse(session.snapshot().answer.isEmpty());
    }
    @Test public void snapshotDoesNotWaitForNativeReleaseAndReentryIsBlocked() throws Exception {
        CountDownLatch closing = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicBoolean blockClose = new AtomicBoolean();
        Driver driver = new Driver() {
            @Override public void close() {
                if (blockClose.get()) {
                    closing.countDown();
                    try { if (!released.await(3, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                    catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                }
                super.close();
            }
        };
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false); blockClose.set(true);
        Thread worker = new Thread(session::cancelAndClose); worker.start();
        assertTrue(closing.await(2, TimeUnit.SECONDS));
        assertEquals(LocalModelSession.State.CLOSING, session.snapshot().state);
        assertFalse(session.canStart());
        released.countDown(); worker.join(2000);
        assertEquals(LocalModelSession.State.EMPTY, session.snapshot().state);
    }
    @Test public void normalNativeCancellationAllowsFreshConversationInSameEngine() throws Exception {
        Driver driver = new Driver() {
            @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) throws Exception {
                entered.countDown();
                if (!finish.await(3, TimeUnit.SECONDS)) throw new IllegalStateException();
                throw new java.io.IOException("native cancellation");
            }
        };
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false);
        Thread worker = new Thread(session::generate); worker.start();
        assertTrue(driver.entered.await(2, TimeUnit.SECONDS));
        session.cancel(); driver.finish.countDown(); worker.join(2000);
        assertEquals(LocalModelSession.State.READY, session.snapshot().state);
        assertFalse(driver.closed.get());
    }
    @Test public void unknownConversationReleaseFailureBlocksEngineReuse() {
        Driver driver = new Driver() {
            @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) throws Exception {
                throw new ReplyFailure("RELEASE_FAILED");
            }
        };
        LocalModelSession session = new LocalModelSession(driver);
        session.load(new File("model.litertlm"), false);
        session.generate();
        assertEquals("RELEASE_FAILED", session.snapshot().error);
        assertFalse(session.canStart());
        session.cancelAndClose();
        assertEquals("RELEASE_FAILED", session.snapshot().error);
        assertFalse(driver.closed.get());
    }
}
