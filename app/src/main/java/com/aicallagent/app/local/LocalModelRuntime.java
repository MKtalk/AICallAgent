package com.aicallagent.app.local;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Process-wide owner. Activities never own native handles or wait for their release. */
public final class LocalModelRuntime implements ReplyEngine {
    interface InputSource { InputStream open() throws IOException; }
    private static LocalModelRuntime instance;
    public static synchronized LocalModelRuntime get(Context context) throws IOException {
        if (instance == null) instance = new LocalModelRuntime(context.getApplicationContext());
        return instance;
    }
    final LocalModelSession session;
    private final BooleanSupplier gpuChoice;
    private final Consumer<Boolean> saveGpuChoice;
    private Object callOwner;
    private final LocalModelStore store;
    private final ExecutorService worker;
    private final ScheduledExecutorService control;
    private boolean busy, cancelled, closeRequested, timedOut;
    private long ticket;
    private String operation = "", error = "", modelHash = "";
    private long modelBytes;

    private LocalModelRuntime(Context context) throws IOException {
        this(new LocalModelStore(new File(context.getNoBackupFilesDir(), "local-models"), LocalModelStore.MAX_BYTES),
                new LiteRtModelDriver(new File(context.getCacheDir(), "litertlm")),
                Executors.newSingleThreadExecutor(), Executors.newSingleThreadScheduledExecutor(),
                () -> context.getSharedPreferences("local_model", Context.MODE_PRIVATE).getBoolean("gpu", false),
                gpu -> context.getSharedPreferences("local_model", Context.MODE_PRIVATE).edit().putBoolean("gpu", gpu).apply());
    }

    LocalModelRuntime(LocalModelStore store, LocalModelSession.Driver driver, ExecutorService worker,
            ScheduledExecutorService control, BooleanSupplier gpuChoice, Consumer<Boolean> saveGpuChoice) throws IOException {
        this.store = store;
        this.session = new LocalModelSession(driver);
        this.worker = worker; this.control = control;
        this.gpuChoice = gpuChoice; this.saveGpuChoice = saveGpuChoice;
        updateModel();
    }

    static final class Snapshot {
        final boolean busy, cancelling;
        final String operation, error, hash;
        final long bytes;
        final LocalModelSession.Snapshot session;
        Snapshot(boolean busy, boolean cancelling, String operation, String error, String hash,
                long bytes, LocalModelSession.Snapshot session) {
            this.busy = busy; this.cancelling = cancelling; this.operation = operation;
            this.error = error; this.hash = hash; this.bytes = bytes; this.session = session;
        }
    }
    synchronized Snapshot snapshot() {
        return new Snapshot(busy, cancelled, operation, error, modelHash, modelBytes, session.snapshot());
    }

    synchronized void importModel(InputSource input, long size, String hash) {
        if (callOwner != null) return;
        submit("IMPORT", 0, () -> {
            session.cancelAndClose();
            if (!session.canStart()) throw new IOException("RELEASE_FAILED");
            try (InputStream stream = input.open()) {
                if (stream == null) throw new IOException("PROVIDER");
                store.importModel(stream, size, hash, this::isCancelled);
            }
            updateModel();
        });
    }
    public synchronized boolean hasModel() { return !modelHash.isEmpty(); }
    public synchronized String preparationError() {
        return "RELEASE_FAILED".equals(session.snapshot().error) ? "RELEASE_FAILED" : "LOCAL_BUSY";
    }
    synchronized boolean callOwned() { return callOwner != null; }
    boolean selectedGpu() { return gpuChoice.getAsBoolean(); }
    void selectGpu(boolean gpu) { saveGpuChoice.accept(gpu); }
    synchronized void load(boolean gpu) {
        if (callOwner != null) return;
        selectGpu(gpu);
        loadModel(gpu, null);
    }
    private boolean loadModel(boolean gpu, Callback callback) {
        return submit("LOAD", 120, () -> {
            session.cancelAndClose();
            if (!session.canStart()) throw new IOException("RELEASE_FAILED");
            LocalModelStore.Model model = store.verifyCurrent(this::isCancelled);
            if (!isCancelled()) session.load(model.file, gpu);
        }, callback);
    }
    public synchronized boolean prepareForCalls(Object owner, Callback callback) {
        if (owner == null || busy || callOwner != null || !session.canStart()) return false;
        callOwner = owner;
        if (loadModel(selectedGpu(), callback)) return true;
        callOwner = null;
        return false;
    }
    @Override public synchronized boolean isReady(Object owner) {
        return owner != null && owner == callOwner && !busy && session.canStart()
                && session.snapshot().state == LocalModelSession.State.READY;
    }
    @Override public synchronized boolean generate(Object owner, ReplyRequest request, Callback callback) {
        if (!isReady(owner)) return false;
        return submit("CALL", 30, () -> { if (!isCancelled()) session.generate(request); }, callback);
    }
    @Override public synchronized void cancel(Object owner) {
        if (owner == callOwner) stop(false);
    }
    @Override public synchronized void release(Object owner) {
        if (owner != callOwner) return;
        callOwner = null;
        stop(true);
    }
    synchronized void generate() { generate(true); }
    synchronized void generate(boolean isKorean) {
        if (callOwner == null) submit("GENERATE", 30, () -> { if (!isCancelled()) session.generate(isKorean); });
    }
    synchronized void release() { if (callOwner == null) stop(true); }
    synchronized void cancel() { if (callOwner == null) stop(false); }

    private boolean submit(String name, int timeoutSeconds, Work work) {
        return submit(name, timeoutSeconds, work, null);
    }
    private boolean submit(String name, int timeoutSeconds, Work work, Callback callback) {
        final long id;
        synchronized (this) {
            if (busy || !session.canStart()) return false;
            busy = true; cancelled = closeRequested = timedOut = false;
            operation = name; error = ""; id = ++ticket;
        }
        if (timeoutSeconds > 0) control.schedule(() -> {
            long version;
            synchronized (this) {
                if (!busy || ticket != id) return;
                timedOut = cancelled = true;
                version = session.snapshot().version;
            }
            session.cancel(version);
        }, timeoutSeconds, TimeUnit.SECONDS);
        worker.execute(() -> {
            try { if (!isCancelled()) work.run(); }
            catch (Exception | LinkageError | OutOfMemoryError failure) {
                synchronized (this) { error = safeError(failure); }
            } finally {
                String result = session.snapshot().answer;
                if (name.equals("CALL")) session.clearAnswer();
                String diagnostic;
                boolean released = false;
                while (true) {
                    synchronized (this) {
                        if (released || !(closeRequested || (cancelled && name.equals("LOAD")))) {
                            if (timedOut) error = "TIMEOUT";
                            else if (cancelled) error = "CANCELLED";
                            if (error.isEmpty()) error = session.snapshot().error;
                            if ("RELEASE_FAILED".equals(session.snapshot().error)) error = "RELEASE_FAILED";
                            diagnostic = error;
                            busy = false;
                            break;
                        }
                    }
                    session.cancelAndClose();
                    released = true;
                }
                if (callback != null) callback.complete(diagnostic.isEmpty() ? result : "", diagnostic);
            }
        });
        return true;
    }
    private void stop(boolean close) {
        long version;
        synchronized (this) {
            if (!busy) {
                if (!close) return;
                // Reserve the worker before queuing a release; new activities cannot race it.
                submit("RELEASE", 0, session::cancelAndClose);
                return;
            }
            cancelled = true;
            closeRequested |= close;
            version = session.snapshot().version;
        }
        control.execute(() -> session.cancel(version));
    }
    private synchronized boolean isCancelled() { return cancelled; }
    private void updateModel() throws IOException {
        LocalModelStore.Model model = store.current();
        synchronized (this) {
            modelHash = model == null ? "" : model.sha256;
            modelBytes = model == null ? 0 : model.file.length();
        }
    }
    private static String safeError(Throwable failure) {
        String code = failure instanceof IOException ? failure.getMessage() : "";
        if (code != null && code.matches("MODEL_SIZE|INVALID_MODEL|MISSING_MODEL|HASH_MISMATCH|CANCELLED|STORAGE|RELEASE_FAILED")) return code;
        return failure instanceof OutOfMemoryError ? "MEMORY" : "LOCAL_FAILED";
    }
    private interface Work { void run() throws Exception; }
}
