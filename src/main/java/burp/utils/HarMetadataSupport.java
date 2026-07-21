package burp.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

public final class HarMetadataSupport {
    public static final String ROOT_FIELDS = "har.root.fields";
    public static final String LOG_FIELDS = "har.log.fields";
    public static final String ENTRY_ORIGINAL = "har.entry.original";
    public static final String REQUEST_FINGERPRINT = "har.request.semanticFingerprint";
    public static final String REQUEST_HTTP_VERSION = "har.request.httpVersion";
    public static final String QUERY_ROW_ORIGINAL = "har.query.row.original";
    public static final String COOKIE_ROW_ORIGINAL = "har.cookie.row.original";
    public static final String POST_DATA_ORIGINAL = "har.postData.original";
    public static final String POST_PARAM_ORIGINAL = "har.postData.param.original";

    private static final int MAX_LABEL_LENGTH = 160;
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private HarMetadataSupport() {
    }

    public static void putCanonical(Map<String, String> metadata,
                                    String key,
                                    JsonElement value) {
        if (metadata == null || key == null || value == null) {
            return;
        }
        metadata.put(key, GSON.toJson(value.deepCopy()));
    }

    public static JsonObject parseObject(String value) {
        if (value == null || value.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return parsed != null && parsed.isJsonObject()
                    ? parsed.getAsJsonObject().deepCopy()
                    : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    public static String safeLabel(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_LABEL_LENGTH));
        boolean pendingSpace = false;
        for (int index = 0; index < value.length() && safe.length() < MAX_LABEL_LENGTH; index++) {
            char ch = value.charAt(index);
            boolean separator = ch == '\r'
                    || ch == '\n'
                    || ch == '\u0085'
                    || ch == '\u2028'
                    || ch == '\u2029';
            boolean control = ch < 0x20 || ch == 0x7f;
            if (separator || control || Character.isWhitespace(ch)) {
                pendingSpace = safe.length() > 0;
                continue;
            }
            if (pendingSpace) {
                safe.append(' ');
                pendingSpace = false;
            }
            safe.append(ch);
        }
        String result = safe.toString().trim();
        if (value.length() > MAX_LABEL_LENGTH && result.length() >= MAX_LABEL_LENGTH) {
            result = result.substring(0, MAX_LABEL_LENGTH - 3) + "...";
        }
        return result;
    }
}
