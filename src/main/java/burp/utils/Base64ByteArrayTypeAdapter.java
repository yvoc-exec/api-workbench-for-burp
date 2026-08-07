package burp.utils;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Base64;

public final class Base64ByteArrayTypeAdapter extends TypeAdapter<byte[]> {
    public static final String PREFIX = "awb:b64:v1:";
    public static final int DEFAULT_MAX_DECODED_BYTES = 64 * 1024 * 1024;

    private final int maxDecodedBytes;

    public Base64ByteArrayTypeAdapter(int maxDecodedBytes) {
        if (maxDecodedBytes <= 0 || maxDecodedBytes > DEFAULT_MAX_DECODED_BYTES) {
            throw new IllegalArgumentException(
                    "Decoded byte limit must be between 1 and " + DEFAULT_MAX_DECODED_BYTES + ".");
        }
        this.maxDecodedBytes = maxDecodedBytes;
    }

    @Override
    public void write(JsonWriter out, byte[] value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        if (value.length > maxDecodedBytes) {
            throw tooLarge();
        }
        out.value(PREFIX + Base64.getEncoder().encodeToString(value));
    }

    @Override
    public byte[] read(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        if (token == JsonToken.STRING) {
            return readString(in.nextString());
        }
        if (token == JsonToken.BEGIN_ARRAY) {
            return readLegacyArray(in);
        }
        throw new JsonParseException("Byte value must be a supported Base64 string or legacy numeric array.");
    }

    private byte[] readString(String encodedValue) {
        if (encodedValue == null || !encodedValue.startsWith(PREFIX)) {
            throw new JsonParseException("Byte value uses an unsupported encoding prefix.");
        }
        String payload = encodedValue.substring(PREFIX.length());
        long maximumEncodedLength = ((long) maxDecodedBytes + 2L) / 3L * 4L;
        if (payload.length() > maximumEncodedLength) {
            throw tooLarge();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length > maxDecodedBytes) {
                throw tooLarge();
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new JsonParseException("Byte value contains malformed Base64 data.", e);
        }
    }

    private byte[] readLegacyArray(JsonReader in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxDecodedBytes, 8_192));
        in.beginArray();
        int count = 0;
        while (in.hasNext()) {
            if (count >= maxDecodedBytes) {
                throw tooLarge();
            }
            if (in.peek() != JsonToken.NUMBER) {
                throw new JsonParseException("Legacy byte array contains a non-numeric value.");
            }
            String literal = in.nextString();
            int value;
            try {
                value = new BigDecimal(literal).intValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new JsonParseException("Legacy byte array values must be integral numbers.", e);
            }
            if (value < -128 || value > 255) {
                throw new JsonParseException("Legacy byte array value is outside the supported -128..255 range.");
            }
            bytes.write((byte) value);
            count++;
        }
        in.endArray();
        return bytes.toByteArray();
    }

    private JsonParseException tooLarge() {
        return new JsonParseException(
                "Decoded byte value exceeds the configured " + maxDecodedBytes + " byte limit.");
    }
}
