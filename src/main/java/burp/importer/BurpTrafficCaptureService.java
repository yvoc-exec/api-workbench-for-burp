package burp.importer;

import burp.history.HistoryHeader;
import burp.history.HistoryRetentionPolicy;
import burp.history.HistoryResponseSnapshot;
import burp.payload.FileManagedPayloadStore;
import burp.payload.ManagedPayloadRef;
import burp.payload.ManagedPayloadStage;
import burp.payload.ManagedPayloadStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Stages Burp-owned message bytes on the bounded traffic worker. */
public final class BurpTrafficCaptureService {
    private final ManagedPayloadStore payloadStore;

    public BurpTrafficCaptureService(ManagedPayloadStore payloadStore) {
        this.payloadStore = payloadStore;
    }

    public List<BurpTrafficSelection> stage(List<BurpTrafficSourceSelection> sources,
                                            HistoryRetentionPolicy retentionPolicy) throws IOException {
        if (payloadStore == null) throw new IOException("Managed payload storage is unavailable.");
        List<BurpTrafficSelection> captured = new ArrayList<>();
        try {
            for (BurpTrafficSourceSelection source : sources != null ? sources : List.<BurpTrafficSourceSelection>of()) {
                if (source != null) captured.add(stageOne(source, retentionPolicy));
            }
            return captured;
        } catch (IOException | RuntimeException failure) {
            closeAll(captured);
            throw failure;
        }
    }

    private BurpTrafficSelection stageOne(BurpTrafficSourceSelection source,
                                          HistoryRetentionPolicy retentionPolicy) throws IOException {
        Object exchange = source.requestResponse;
        Object request = unwrap(invokeFirst(exchange, "request"));
        if (request == null) throw new IOException("Selected item has no request.");
        Object requestBytes = fileBacked(invokeFirst(request, "toByteArray"));
        int requestLength = length(requestBytes);
        if (requestLength <= 0) throw new IOException("Selected request is empty.");
        long bodyOffset = number(invokeFirst(request, "bodyOffset"), requestLength);
        if (bodyOffset < 0L || bodyOffset > requestLength) bodyOffset = requestLength;

        ManagedPayloadStage stage = payloadStore.beginStage("burp-capture");
        try {
            MessageDigest bodyDigest = sha256();
            Utf8Validator utf8 = new Utf8Validator();
            long[] position = {0L};
            long finalBodyOffset = bodyOffset;
            stream(requestBytes, chunk -> {
                stage.write(chunk);
                int start = (int) Math.max(0L, finalBodyOffset - position[0]);
                if (start < chunk.length) {
                    bodyDigest.update(chunk, start, chunk.length - start);
                    utf8.accept(chunk, start, chunk.length - start);
                }
                position[0] += chunk.length;
            });
            ManagedPayloadRef ref = stage.reference();
            long bodyLength = requestLength - bodyOffset;

            Object service = unwrap(invokeFirst(request, "httpService"));
            if (service == null) service = unwrap(invokeFirst(exchange, "httpService"));
            String method = text(invokeFirst(request, "method"));
            String url = text(invokeFirst(request, "url"));
            String target = target(request, url);
            String version = text(invokeFirst(request, "httpVersion"));
            List<HistoryHeader> headers = headers(invokeFirst(request, "headers"));
            String warning = bodyOffset == requestLength && requestLength > 0 && !headers.isEmpty()
                    ? "Request body framing was unavailable; exact replay remains authoritative." : "";
            ResponseCapture response = captureResponse(unwrap(invokeFirst(exchange, "response")), retentionPolicy);
            return BurpTrafficSelection.staged(ref, stage, bodyOffset, bodyLength,
                    bodyLength > 0 ? hex(bodyDigest.digest()) : "", bodyLength > 0 && !utf8.valid(),
                    text(invokeFirst(service, "host")), (int) number(invokeFirst(service, "port"), 0L),
                    bool(invokeFirst(service, "secure", "isSecure")), source.sourceContext,
                    method + (target.isBlank() ? "" : " " + target), method, target,
                    version.isBlank() ? "HTTP/1.1" : version, headers, warning,
                    response.snapshot, source.encounterIndex, response.rawLength);
        } catch (IOException | RuntimeException failure) {
            try { stage.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private ResponseCapture captureResponse(Object response, HistoryRetentionPolicy retentionPolicy) throws IOException {
        if (response == null) return new ResponseCapture(null, 0L);
        HistoryRetentionPolicy policy = HistoryRetentionPolicy.copyOf(retentionPolicy);
        policy.normalize();
        HistoryResponseSnapshot snapshot = new HistoryResponseSnapshot();
        snapshot.statusCode = (int) number(invokeFirst(response, "statusCode"), 0L);
        snapshot.reasonPhrase = text(invokeFirst(response, "reasonPhrase"));
        snapshot.headers = headers(invokeFirst(response, "headers"));
        for (HistoryHeader header : snapshot.headers) {
            if (header != null && "content-type".equalsIgnoreCase(header.name)) snapshot.mimeType = header.value;
        }
        Object body = fileBacked(invokeFirst(response, "body"));
        int bodyLength = length(body);
        int retained = (int) Math.min(bodyLength, policy.maxResponseBodyBytesPerEntry);
        ByteArrayOutputStream preview = new ByteArrayOutputStream(retained);
        MessageDigest digest = sha256();
        stream(body, chunk -> {
            digest.update(chunk);
            int remaining = retained - preview.size();
            if (remaining > 0) preview.write(chunk, 0, Math.min(remaining, chunk.length));
        });
        snapshot.body = retained > 0 ? preview.toByteArray() : null;
        snapshot.originalBodyLength = bodyLength;
        snapshot.storedBodyLength = retained;
        snapshot.fullBodySha256 = bodyLength > 0 ? hex(digest.digest()) : "";
        snapshot.bodyTruncated = retained < bodyLength;
        snapshot.truncationReason = snapshot.bodyTruncated ? "RESPONSE_BODY_LIMIT" : "";
        Object raw = invokeFirst(response, "toByteArray");
        long rawLength = raw != null ? length(raw) : bodyLength;
        return new ResponseCapture(snapshot, Math.max(rawLength, bodyLength));
    }

    private static void stream(Object byteArray, ChunkConsumer consumer) throws IOException {
        int total = length(byteArray);
        for (int offset = 0; offset < total; ) {
            int end = Math.min(total, offset + FileManagedPayloadStore.CHUNK_SIZE);
            Object slice = invoke(byteArray, "subArray", new Class<?>[]{int.class, int.class}, offset, end);
            Object value = invokeFirst(slice, "getBytes");
            if (!(value instanceof byte[] chunk) || chunk.length != end - offset) {
                throw new IOException("Burp byte range could not be staged safely.");
            }
            consumer.accept(chunk);
            offset = end;
        }
    }

    private static Object fileBacked(Object value) {
        Object unwrapped = unwrap(value);
        Object copied = invokeFirst(unwrapped, "copyToTempFile");
        return copied != null ? copied : unwrapped;
    }

    private static List<HistoryHeader> headers(Object value) {
        List<HistoryHeader> result = new ArrayList<>();
        for (Object item : flatten(value)) {
            String name = text(invokeFirst(item, "name"));
            if (!name.isBlank()) result.add(new HistoryHeader(name, text(invokeFirst(item, "value")), false));
        }
        return result;
    }

    private static String target(Object request, String url) {
        String path = text(invokeFirst(request, "path"));
        if (!path.isBlank()) return path;
        try {
            URI uri = URI.create(url);
            String value = uri.getRawPath();
            if (value == null || value.isBlank()) value = "/";
            return uri.getRawQuery() != null ? value + "?" + uri.getRawQuery() : value;
        } catch (RuntimeException ignored) {
            return url;
        }
    }

    private static List<Object> flatten(Object value) {
        Object unwrapped = unwrap(value);
        List<Object> out = new ArrayList<>();
        if (unwrapped instanceof Collection<?> collection) out.addAll(collection);
        else if (unwrapped != null && unwrapped.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(unwrapped); i++) out.add(Array.get(unwrapped, i));
        } else if (unwrapped != null) out.add(unwrapped);
        return out;
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static Object invokeFirst(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try { return target.getClass().getMethod(name).invoke(target); }
            catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws IOException {
        try { return target.getClass().getMethod(name, types).invoke(target, args); }
        catch (ReflectiveOperationException failure) { throw new IOException("Burp byte range API is unavailable.", failure); }
    }

    private static int length(Object value) throws IOException {
        Object number = invokeFirst(unwrap(value), "length", "size");
        if (number instanceof Number n && n.longValue() >= 0L && n.longValue() <= Integer.MAX_VALUE) return n.intValue();
        throw new IOException("Burp message length is unavailable.");
    }

    private static long number(Object value, long fallback) {
        Object unwrapped = unwrap(value);
        return unwrapped instanceof Number number ? number.longValue() : fallback;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(unwrap(value));
    }

    private static String text(Object value) {
        Object unwrapped = unwrap(value);
        return unwrapped != null ? unwrapped.toString() : "";
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static void closeAll(List<BurpTrafficSelection> selections) {
        for (BurpTrafficSelection selection : selections) {
            try { selection.close(); } catch (IOException ignored) { }
        }
    }

    @FunctionalInterface
    private interface ChunkConsumer { void accept(byte[] chunk) throws IOException; }
    private record ResponseCapture(HistoryResponseSnapshot snapshot, long rawLength) { }

    private static final class Utf8Validator {
        private int needed;
        private int codePoint;
        private int minimum;
        private boolean valid = true;

        void accept(byte[] bytes, int offset, int count) {
            for (int i = offset; valid && i < offset + count; i++) {
                int value = bytes[i] & 0xff;
                if (needed > 0) {
                    if ((value & 0xc0) != 0x80) { valid = false; break; }
                    codePoint = (codePoint << 6) | (value & 0x3f);
                    if (--needed == 0 && (codePoint < minimum || codePoint > 0x10ffff
                            || (codePoint >= 0xd800 && codePoint <= 0xdfff))) valid = false;
                } else if (value <= 0x7f) {
                    continue;
                } else if ((value & 0xe0) == 0xc0) {
                    needed = 1; codePoint = value & 0x1f; minimum = 0x80;
                } else if ((value & 0xf0) == 0xe0) {
                    needed = 2; codePoint = value & 0x0f; minimum = 0x800;
                } else if ((value & 0xf8) == 0xf0) {
                    needed = 3; codePoint = value & 0x07; minimum = 0x10000;
                } else valid = false;
            }
        }

        boolean valid() { return valid && needed == 0; }
    }
}
