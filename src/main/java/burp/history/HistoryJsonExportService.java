package burp.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class HistoryJsonExportService {
    private final Gson gson = HistoryJsonSupport.configure(new GsonBuilder().setPrettyPrinting()).create();

    public String export(Collection<HistoryEntry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.addProperty("generatedAt", Instant.now().toString());
        JsonArray array = new JsonArray();
        for (HistoryEntry entry : entries != null ? entries : List.<HistoryEntry>of()) {
            HistoryEntry copy = HistoryEntry.copyOf(entry);
            if (copy != null) {
                copy.ensureDefaults();
                array.add(gson.toJsonTree(copy));
            }
        }
        root.add("entries", array);
        return gson.toJson(root);
    }

    public void write(Collection<HistoryEntry> entries, Path output) throws IOException {
        if (output == null) {
            throw new IOException("Output path is required");
        }
        Files.createDirectories(output.getParent() != null ? output.getParent() : output.toAbsolutePath().getParent());
        Files.writeString(output, export(entries), StandardCharsets.UTF_8);
    }
}
