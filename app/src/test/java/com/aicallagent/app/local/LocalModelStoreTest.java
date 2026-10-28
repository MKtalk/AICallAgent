package com.aicallagent.app.local;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class LocalModelStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private byte[] model(String text) { return ("LITERTLM" + text).getBytes(StandardCharsets.UTF_8); }
    private String hash(byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    private LocalModelStore store() throws Exception { return new LocalModelStore(folder.newFolder(), 1024); }
    private void install(LocalModelStore store, byte[] bytes) throws Exception {
        store.importModel(new ByteArrayInputStream(bytes), bytes.length, hash(bytes), () -> false);
    }
    @Test public void selectedModelSurvivesStoreRecreationAndIsVerified() throws Exception {
        LocalModelStore store = store(); byte[] data = model("first"); install(store, data);
        assertEquals(hash(data), store.current().sha256);
        assertArrayEquals(data, Files.readAllBytes(new LocalModelStore(store.directory(), 1024).current().file.toPath()));
    }
    @Test public void wrongHashPreservesPreviousModel() throws Exception {
        LocalModelStore store = store(); byte[] old = model("first"); install(store, old);
        assertThrows(IOException.class, () -> store.importModel(new ByteArrayInputStream(model("second")),
                -1, hash(old), () -> false));
        assertEquals(hash(old), store.current().sha256);
        assertEquals(2, store.directory().list().length);
    }
    @Test public void htmlRenamedAsModelIsRejectedEvenWithMatchingHash() throws Exception {
        LocalModelStore store = store(); byte[] html = "<html>access denied</html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> install(store, html));
        assertNull(store.current());
    }
    @Test public void cancelledCopyPreservesPreviousModel() throws Exception {
        LocalModelStore store = store(); byte[] old = model("old"); install(store, old);
        AtomicBoolean cancelled = new AtomicBoolean();
        InputStream input = new ByteArrayInputStream(model("next")) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                int result = super.read(b, off, len); cancelled.set(true); return result;
            }
        };
        assertThrows(IOException.class, () -> store.importModel(input, -1, hash(model("next")), cancelled::get));
        assertEquals(hash(old), store.current().sha256);
    }
    @Test public void unknownSizeStillEnforcesStreamingLimit() throws Exception {
        LocalModelStore store = store(); byte[] data = new byte[1025];
        System.arraycopy(model(""), 0, data, 0, 8);
        IOException error = assertThrows(IOException.class, () -> store.importModel(new ByteArrayInputStream(data), -1,
                hash(data), () -> false));
        assertEquals("MODEL_SIZE", error.getMessage());
        assertNull(store.current());
    }
    @Test public void declaredSizeMismatchIsRejected() throws Exception {
        LocalModelStore store = store(); byte[] data = model("test");
        assertThrows(IOException.class, () -> store.importModel(new ByteArrayInputStream(data), data.length + 1,
                hash(data), () -> false));
    }
    @Test public void tamperingAfterImportIsRejectedBeforeLoading() throws Exception {
        LocalModelStore store = store(); install(store, model("test"));
        Files.write(store.current().file.toPath(), model("changed"));
        assertThrows(IOException.class, () -> store.verifyCurrent(() -> false));
    }
    @Test public void invalidDigestAndInterruptedProviderCannotReplaceModel() throws Exception {
        LocalModelStore store = store(); byte[] data = model("old"); install(store, data);
        assertThrows(IllegalArgumentException.class, () -> store.importModel(new ByteArrayInputStream(data), -1,
                "../bad", () -> false));
        assertThrows(IOException.class, () -> store.importModel(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("provider failed"); }
        }, -1, hash(data), () -> false));
        assertEquals(hash(data), store.current().sha256);
    }
    @Test public void successfulReplacementPublishesNewModelAndRemovesOldContent() throws Exception {
        LocalModelStore store = store(); byte[] first = model("first"), second = model("second");
        install(store, first); java.io.File old = store.current().file;
        install(store, second);
        assertEquals(hash(second), store.verifyCurrent(() -> false).sha256);
        assertFalse(old.exists());
        assertEquals(2, store.directory().list().length);
    }
}
