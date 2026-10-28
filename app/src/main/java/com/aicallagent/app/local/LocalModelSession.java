package com.aicallagent.app.local;

import java.io.File;
import com.aicallagent.app.model.ReplyDiagnostics;
import java.util.function.BooleanSupplier;

/** One native operation at a time. Cancellation never releases a still-running native object. */
public final class LocalModelSession {
    public enum State { EMPTY, LOADING, READY, GENERATING, CANCELLING, CLOSING, ERROR }
    public interface Driver {
        void load(File model, boolean gpu) throws Exception;
        String generate(ReplyRequest request, BooleanSupplier cancelled) throws Exception;
        void cancel();
        void close();
    }
    public static final class Snapshot {
        public final State state;
        public final String answer, error;
        public final long elapsedMs, version;
        Snapshot(State state, String answer, String error, long elapsedMs, long version) {
            this.state = state; this.answer = answer; this.error = error; this.elapsedMs = elapsedMs; this.version = version;
        }
    }
    private final Driver driver;
    private volatile State state = State.EMPTY;
    private volatile String answer = "", error = "";
    private volatile long elapsedMs, version;
    private volatile boolean busy, cancelled;
    private boolean closeRequested, loaded;
    public LocalModelSession(Driver driver) { this.driver = driver; }
    public Snapshot snapshot() { return new Snapshot(state, answer, error, elapsedMs, version); }
    public boolean canStart() { return !busy; }
    public synchronized void clearAnswer() { answer = ""; }

    public void load(File file, boolean gpu) {
        synchronized (this) {
            if (busy) return;
            begin(State.LOADING);
        }
        long start = System.nanoTime();
        boolean success = false;
        try {
            driver.close(); loaded = false;
            driver.load(file, gpu); loaded = true; success = true;
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            setError(failure instanceof OutOfMemoryError ? "MEMORY" : "LOAD_FAILED");
        }
        finally { finish(start, success, true); }
    }

    public void generate() {
        generate(true);
    }
    public void generate(boolean isKorean) {
        generate(ReplyRequest.test(isKorean));
    }
    public void generate(ReplyRequest request) {
        synchronized (this) {
            if (busy || state != State.READY) return;
            begin(State.GENERATING);
        }
        long start = System.nanoTime();
        boolean success = false;
        try {
            String result = driver.generate(request, this::isCancelled);
            if (!cancelled && (result == null || result.trim().isEmpty())) throw new IllegalStateException();
            synchronized (this) { if (!cancelled) answer = result.trim(); }
            success = true;
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            if (cancelled && failure instanceof Exception
                    && !(failure instanceof ReplyFailure && "RELEASE_FAILED".equals(((ReplyFailure) failure).code))) {
                // The driver has closed the cancelled Conversation in its finally block.
                // A later turn uses a fresh Conversation, so no cancelled KV state is reused.
                success = true;
            } else {
                setError(failure instanceof ReplyFailure ? ((ReplyFailure) failure).code
                        : failure instanceof OutOfMemoryError ? "MEMORY" : "GENERATION_FAILED");
                // A ReplyFailure is raised after the per-turn Conversation closes. The engine
                // remains usable for output validation failures; native failures still release it.
                success = failure instanceof ReplyFailure
                        && ReplyDiagnostics.isRecoverableGenerationError(((ReplyFailure) failure).code);
            }
        }
        finally { finish(start, success, false); }
    }
    private void begin(State next) {
        version++;
        busy = true; cancelled = closeRequested = false; answer = error = ""; state = next;
    }
    private synchronized void setError(String value) { error = value; }
    private boolean isCancelled() { return cancelled; }

    private void finish(long start, boolean success, boolean loading) {
        synchronized (this) {
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if ("RELEASE_FAILED".equals(error)) {
                state = State.ERROR; answer = "";
                // A Conversation may still be live: do not delete or reuse its parent engine.
                return;
            }
            if (cancelled) { answer = ""; error = "CANCELLED"; }
            if (!closeRequested && success && !(loading && cancelled)) {
                state = loaded ? State.READY : State.EMPTY;
                busy = false;
                return;
            }
            state = State.CLOSING;
        }
        try { driver.close(); loaded = false; }
        catch (RuntimeException | LinkageError failure) {
            synchronized (this) {
                error = "RELEASE_FAILED"; state = State.ERROR;
                // Keep busy: a native handle of unknown lifetime must never be reused.
            }
            return;
        }
        synchronized (this) {
            state = !error.isEmpty() && !cancelled ? State.ERROR : State.EMPTY;
            busy = false;
        }
    }
    public void cancel() { requestCancel(false, -1); }
    public void cancel(long expectedVersion) { requestCancel(false, expectedVersion); }
    public void cancelAndClose() { requestCancel(true, -1); }
    private void requestCancel(boolean close, long expectedVersion) {
        boolean interrupt, closeNow = false;
        synchronized (this) {
            if (busy && state == State.ERROR && "RELEASE_FAILED".equals(error)) return;
            if (expectedVersion >= 0 && expectedVersion != version) return;
            if (!busy && !close) return;
            closeRequested |= close;
            cancelled = true; answer = "";
            if (!busy) {
                begin(State.CLOSING);
                closeRequested = true;
                closeNow = true;
            }
            interrupt = !closeNow && (state == State.GENERATING || state == State.CANCELLING);
            if (!closeNow) state = State.CANCELLING;
            // Hold the session gate until cancel returns, so it cannot target a later native call.
            if (interrupt) {
                try { driver.cancel(); }
                catch (RuntimeException | LinkageError failure) { error = "CANCEL_FAILED"; }
            }
        }
        if (closeNow) { finish(System.nanoTime(), true, false); return; }
    }
}
