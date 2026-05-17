package burp.utils;

import burp.models.ApiCollection;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeVariablesJson {
    private static final int VERSION = 1;

    private RuntimeVariablesJson() {
    }

    public static String toJson(ApiCollection collection) {
        RuntimeVariableBundle bundle = new RuntimeVariableBundle();
        bundle.version = VERSION;
        bundle.collectionName = collection != null ? collection.name : null;
        if (collection != null) {
            if (collection.runtimeVars != null) {
                bundle.runtimeVars.putAll(collection.runtimeVars);
            }
            if (collection.runtimeOAuth2 != null) {
                bundle.runtimeOAuth2.putAll(collection.runtimeOAuth2);
            }
        }

        return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(bundle);
    }

    public static RuntimeVariableBundle fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new RuntimeVariableBundle();
        }

        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            RuntimeVariableBundle bundle = new RuntimeVariableBundle();
            if (obj.has("version") && !obj.get("version").isJsonNull()) {
                bundle.version = obj.get("version").getAsInt();
            }
            if (obj.has("collection") && !obj.get("collection").isJsonNull()) {
                bundle.collectionName = obj.get("collection").getAsString();
            } else if (obj.has("collectionName") && !obj.get("collectionName").isJsonNull()) {
                bundle.collectionName = obj.get("collectionName").getAsString();
            }
            if (obj.has("runtimeVars") && obj.get("runtimeVars").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.getAsJsonObject("runtimeVars").entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                        bundle.runtimeVars.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (obj.has("runtimeOAuth2") && obj.get("runtimeOAuth2").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.getAsJsonObject("runtimeOAuth2").entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                        bundle.runtimeOAuth2.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            return bundle;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid runtime variables JSON", e);
        }
    }

    public static void applyToCollection(ApiCollection collection, RuntimeVariableBundle bundle, boolean replace) {
        if (collection == null || bundle == null) {
            return;
        }
        if (replace) {
            collection.replaceRuntimeVars(bundle.runtimeVars);
            collection.replaceRuntimeOAuth2(bundle.runtimeOAuth2);
        } else {
            collection.putAllRuntimeVars(bundle.runtimeVars);
            collection.putAllRuntimeOAuth2(bundle.runtimeOAuth2);
        }
    }

    public static class RuntimeVariableBundle {
        public int version = VERSION;
        @com.google.gson.annotations.SerializedName("collection")
        public String collectionName;
        public Map<String, String> runtimeVars = new LinkedHashMap<>();
        public Map<String, String> runtimeOAuth2 = new LinkedHashMap<>();
    }
}
