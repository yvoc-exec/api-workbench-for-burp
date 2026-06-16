package burp.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.time.Instant;

public final class HistoryJsonSupport {
    private HistoryJsonSupport() {
    }

    public static GsonBuilder configure(GsonBuilder builder) {
        return builder
                .disableHtmlEscaping()
                .registerTypeAdapter(Instant.class, new InstantAdapter());
    }

    public static Gson createGson() {
        return configure(new GsonBuilder()).create();
    }

    private static final class InstantAdapter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
            return src != null ? new JsonPrimitive(src.toString()) : com.google.gson.JsonNull.INSTANCE;
        }

        @Override
        public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            String value = json.getAsString();
            if (value == null || value.isBlank()) {
                return null;
            }
            return Instant.parse(value);
        }
    }
}
