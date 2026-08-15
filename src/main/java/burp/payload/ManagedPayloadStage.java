package burp.payload;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ManagedPayloadStage implements AutoCloseable {
    private final Path temporaryFile;
    private final MessageDigest digest;
    private OutputStream output;
    private long length;
    private String sha256;
    private boolean finished;
    private boolean committed;

    ManagedPayloadStage(Path temporaryFile) throws IOException {
        this.temporaryFile = temporaryFile;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
        this.output = new BufferedOutputStream(Files.newOutputStream(temporaryFile), 1024 * 1024);
    }

    public void write(byte[] bytes) throws IOException {
        if (bytes != null) write(bytes, 0, bytes.length);
    }

    public void write(byte[] bytes, int offset, int count) throws IOException {
        if (finished || committed || output == null) {
            throw new IOException("Managed payload stage is closed.");
        }
        if (bytes == null || offset < 0 || count < 0 || offset > bytes.length - count) {
            throw new IllegalArgumentException("Invalid managed payload stage write.");
        }
        if (length > Long.MAX_VALUE - count) {
            throw new IOException("Managed payload length overflow.");
        }
        output.write(bytes, offset, count);
        digest.update(bytes, offset, count);
        length += count;
    }

    public long length() {
        return length;
    }

    Path temporaryFile() {
        return temporaryFile;
    }

    String finish() throws IOException {
        if (!finished) {
            output.flush();
            output.close();
            output = null;
            sha256 = hex(digest.digest());
            finished = true;
        }
        return sha256;
    }

    /** Finalizes this stage and exposes its descriptor without promoting the file. */
    public ManagedPayloadRef reference() throws IOException {
        String sha = finish();
        return new ManagedPayloadRef(sha, length, sha);
    }

    void markCommitted() {
        committed = true;
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (output != null) {
            try {
                output.close();
            } catch (IOException e) {
                failure = e;
            }
            output = null;
        }
        if (!committed) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        finished = true;
        if (failure != null) throw failure;
    }
}
