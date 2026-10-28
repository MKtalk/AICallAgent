package com.aicallagent.app.local;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Private, content-addressed models with an atomically published active pointer. */
// Deliberately budget actual free bytes without asking Android to reclaim other apps' caches.
@SuppressWarnings("UsableSpace")
public final class LocalModelStore {
    public static final long MAX_BYTES = 6L * 1024 * 1024 * 1024;
    private static final long FREE_RESERVE = 64L * 1024 * 1024;
    private static final byte[] MAGIC = "LITERTLM".getBytes(StandardCharsets.US_ASCII);
    private final File directory;
    private final long maxBytes;

    public static final class Model {
        public final File file;
        public final String sha256;
        Model(File file, String sha256) { this.file = file; this.sha256 = sha256; }
    }

    public LocalModelStore(File directory, long maxBytes) throws IOException {
        this.directory = directory;
        this.maxBytes = maxBytes;
        if (maxBytes <= 0 || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("STORAGE");
    }
    public File directory() { return directory; }

    public Model current() throws IOException {
        File pointer = new File(directory, "active");
        if (!pointer.exists()) return null;
        if (pointer.length() != 64) throw new IOException("INVALID_MODEL");
        String hash = new String(Files.readAllBytes(pointer.toPath()), StandardCharsets.US_ASCII);
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("INVALID_MODEL");
        File file = new File(directory, hash + ".litertlm");
        if (!file.isFile() || file.length() < MAGIC.length || file.length() > maxBytes) throw new IOException("INVALID_MODEL");
        return new Model(file, hash);
    }

    public Model verifyCurrent(BooleanSupplier cancelled) throws IOException {
        Model model = current();
        if (model == null) throw new IOException("MISSING_MODEL");
        try (InputStream input = new FileInputStream(model.file)) {
            if (!digestCopy(input, null, cancelled).equals(model.sha256)) throw new IOException("HASH_MISMATCH");
        }
        return model;
    }

    public Model importModel(InputStream input, long declaredSize, String expectedHash,
            BooleanSupplier cancelled) throws IOException {
        String expected = expectedHash.trim().toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA256");
        if (declaredSize > maxBytes || declaredSize == 0) throw new IOException("MODEL_SIZE");
        if (declaredSize > 0 && directory.getUsableSpace() < declaredSize + FREE_RESERVE) throw new IOException("STORAGE");
        Model previous = current();
        File partial = File.createTempFile("import-", ".partial", directory);
        File pointer = null, target = new File(directory, expected + ".litertlm");
        boolean published = false, createdTarget = false;
        try {
            String actual;
            try (FileOutputStream output = new FileOutputStream(partial)) {
                actual = digestCopy(input, output, cancelled);
                output.getFD().sync();
            }
            if (!expected.equals(actual)) throw new IOException("HASH_MISMATCH");
            if (declaredSize >= 0 && declaredSize != partial.length()) throw new IOException("MODEL_SIZE");
            checkCancelled(cancelled);
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            createdTarget = previous == null || !previous.sha256.equals(expected);
            pointer = File.createTempFile("active-", ".partial", directory);
            try (FileOutputStream output = new FileOutputStream(pointer)) {
                output.write(expected.getBytes(StandardCharsets.US_ASCII)); output.getFD().sync();
            }
            checkCancelled(cancelled);
            Files.move(pointer.toPath(), new File(directory, "active").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            // Old content is private app data; keep only the active model after successful publication.
            if (previous != null && !previous.sha256.equals(expected)) previous.file.delete();
            return new Model(target, expected);
        } finally {
            partial.delete();
            if (pointer != null) pointer.delete();
            if (!published && createdTarget) target.delete();
        }
    }

    private String digestCopy(InputStream input, FileOutputStream output, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buffer = new byte[256 * 1024];
        long total = 0;
        int magicCount = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            checkCancelled(cancelled);
            if (count == 0) continue;
            if (count > maxBytes - total) throw new IOException("MODEL_SIZE");
            for (int i = 0; i < count && magicCount < MAGIC.length; i++, magicCount++) {
                if (buffer[i] != MAGIC[magicCount]) throw new IOException("INVALID_MODEL");
            }
            total += count;
            digest.update(buffer, 0, count);
            if (output != null) {
                if (directory.getUsableSpace() < count + FREE_RESERVE) throw new IOException("STORAGE");
                output.write(buffer, 0, count);
            }
        }
        checkCancelled(cancelled);
        if (total < MAGIC.length) throw new IOException("INVALID_MODEL");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("CANCELLED");
    }
}
