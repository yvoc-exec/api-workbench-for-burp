package burp.history;

import java.util.ArrayList;
import java.util.List;

public final class HistorySanitizer {
    private HistorySanitizer() {
    }

    public static String safeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "")
                .replace('\u0000', ' ')
                .trim();
    }

    public static String safeMultiline(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u0000', ' ');
    }

    public static String csvCell(String value) {
        String text = safeMultiline(value);
        if (text.isEmpty()) {
            return "";
        }
        if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@")) {
            text = "'" + text;
        }
        if (text.contains("\"") || text.contains(",") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public static List<String> safeLines(String value) {
        List<String> lines = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return lines;
        }
        for (String line : value.replace("\r", "").split("\n", -1)) {
            lines.add(safeMultiline(line));
        }
        return lines;
    }
}
