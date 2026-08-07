package burp.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.models.WorkspaceState;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class WorkspaceStateService {
    private static final String KEY = "api_workbench_workspace_state_json";
    public static final long DEFAULT_MAX_SERIALIZED_WORKSPACE_BYTES = 256L * 1024L * 1024L;

    public interface StringStore {
        String get(String key);
        void set(String key, String value);
    }

    private final StringStore store;
    private final Object ioLock = new Object();
    private final long maxSerializedWorkspaceBytes;

    public WorkspaceStateService(MontoyaApi api) {
        this(api != null ? new MontoyaStringStore(api.persistence() != null ? api.persistence().extensionData() : null) : null);
    }

    public WorkspaceStateService(PersistedObject object) {
        this(object != null ? new MontoyaStringStore(object) : null);
    }

    WorkspaceStateService(StringStore store) {
        this(store, DEFAULT_MAX_SERIALIZED_WORKSPACE_BYTES);
    }

    WorkspaceStateService(StringStore store, long maxSerializedWorkspaceBytes) {
        this.store = store;
        this.maxSerializedWorkspaceBytes = validLimit(maxSerializedWorkspaceBytes);
    }

    public long maxSerializedWorkspaceBytes() {
        return maxSerializedWorkspaceBytes;
    }

    public WorkspaceState load() {
        return WorkspaceStateJson.fromJson(loadJson());
    }

    public String loadJson() {
        if (store == null) {
            return null;
        }
        synchronized (ioLock) {
            String json = store.get(KEY);
            if (utf8Length(json) > maxSerializedWorkspaceBytes) {
                throw new IllegalStateException(
                        "Persisted workspace exceeds the configured "
                                + maxSerializedWorkspaceBytes + " byte limit.");
            }
            return json;
        }
    }

    public void save(WorkspaceState state) {
        if (store == null) {
            return;
        }
        saveJson(WorkspaceStateJson.toJsonCopying(state));
    }

    public void saveJson(String json) {
        if (store == null) {
            return;
        }
        String value = json != null ? json : "";
        long length = utf8Length(value);
        WorkspaceSaveResult result = saveSerialized(0L, value, length, sha256(value));
        if (!result.successful()) {
            throw new IllegalStateException(result.failureReason());
        }
    }

    public WorkspaceSaveResult saveSerialized(
            long revision,
            WorkspaceStateJson.SerializedWorkspace serialized) {
        if (revision < 0L) {
            return failed(revision, 0L, null, "Workspace revision must be non-negative.");
        }
        if (serialized == null) {
            return failed(revision, 0L, null, "Serialized workspace is required.");
        }
        long length = serialized.utf8Length();
        if (length > maxSerializedWorkspaceBytes) {
            return new WorkspaceSaveResult(
                    revision,
                    WorkspaceSaveResult.Status.REJECTED_TOO_LARGE,
                    length,
                    serialized.sha256(),
                    "Serialized workspace exceeds the configured "
                            + maxSerializedWorkspaceBytes + " byte limit.");
        }
        if (store == null) {
            return new WorkspaceSaveResult(
                    revision,
                    WorkspaceSaveResult.Status.NO_STORE,
                    length,
                    serialized.sha256(),
                    null);
        }
        synchronized (ioLock) {
            try {
                store.set(KEY, serialized.json());
                return new WorkspaceSaveResult(
                        revision, WorkspaceSaveResult.Status.SAVED, length, serialized.sha256(), null);
            } catch (RuntimeException e) {
                return failed(revision, length, serialized.sha256(), "Workspace persistence failed.");
            }
        }
    }
    public WorkspaceSaveResult saveSerialized(
            long revision,
            String json,
            long utf8Length,
            String sha256) {
        if (revision < 0L) {
            return failed(revision, 0L, sha256, "Workspace revision must be non-negative.");
        }
        String value = json != null ? json : "";
        if (utf8Length < 0L) {
            return failed(revision, 0L, sha256, "Workspace UTF-8 length must be non-negative.");
        }
        long actualLength = utf8Length(value);
        if (actualLength != utf8Length) {
            return failed(revision, actualLength, sha256, "Workspace UTF-8 length does not match serialized content.");
        }
        if (actualLength > maxSerializedWorkspaceBytes) {
            return new WorkspaceSaveResult(
                    revision,
                    WorkspaceSaveResult.Status.REJECTED_TOO_LARGE,
                    actualLength,
                    sha256,
                    "Serialized workspace exceeds the configured "
                            + maxSerializedWorkspaceBytes + " byte limit.");
        }
        if (store == null) {
            return new WorkspaceSaveResult(
                    revision, WorkspaceSaveResult.Status.NO_STORE, actualLength, sha256, null);
        }
        synchronized (ioLock) {
            try {
                store.set(KEY, value);
                return new WorkspaceSaveResult(
                        revision, WorkspaceSaveResult.Status.SAVED, actualLength, sha256, null);
            } catch (RuntimeException e) {
                return failed(revision, actualLength, sha256, "Workspace persistence failed.");
            }
        }
    }

    public static long utf8Length(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        return processUtf8(value, null);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            processUtf8(value != null ? value : "", digest);
            StringBuilder hex = new StringBuilder(64);
            for (byte valueByte : digest.digest()) {
                hex.append(String.format("%02x", valueByte & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static long processUtf8(String value, MessageDigest digest) {
        java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        CharBuffer chars = CharBuffer.wrap(value);
        ByteBuffer bytes = ByteBuffer.allocate(8_192);
        long length = 0L;
        try {
            while (true) {
                CoderResult result = encoder.encode(chars, bytes, true);
                length += consume(bytes, digest);
                if (result.isUnderflow()) {
                    break;
                }
                if (result.isOverflow()) {
                    continue;
                }
                result.throwException();
            }
            while (true) {
                CoderResult result = encoder.flush(bytes);
                length += consume(bytes, digest);
                if (result.isUnderflow()) {
                    break;
                }
                if (result.isOverflow()) {
                    continue;
                }
                result.throwException();
            }
            return length;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Workspace text contains invalid UTF-16 data.", e);
        }
    }

    private static int consume(ByteBuffer bytes, MessageDigest digest) {
        bytes.flip();
        int count = bytes.remaining();
        if (digest != null) {
            digest.update(bytes);
        }
        bytes.clear();
        return count;
    }

    private static WorkspaceSaveResult failed(
            long revision,
            long length,
            String sha256,
            String reason) {
        return new WorkspaceSaveResult(
                revision, WorkspaceSaveResult.Status.FAILED, length, sha256, reason);
    }

    private static long validLimit(long requested) {
        return requested > 0L && requested <= DEFAULT_MAX_SERIALIZED_WORKSPACE_BYTES
                ? requested
                : DEFAULT_MAX_SERIALIZED_WORKSPACE_BYTES;
    }

    private static class MontoyaStringStore implements StringStore {
        private final PersistedObject object;

        MontoyaStringStore(PersistedObject object) {
            this.object = object;
        }

        @Override
        public String get(String key) {
            return object != null ? object.getString(key) : null;
        }

        @Override
        public void set(String key, String value) {
            if (object != null) {
                object.setString(key, value != null ? value : "");
            }
        }
    }
}
