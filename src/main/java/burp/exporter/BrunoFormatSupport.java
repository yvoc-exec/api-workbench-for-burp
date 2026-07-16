package burp.exporter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Canonical Bruno dictionary/text serialization shared by collection and environment export. */
final class BrunoFormatSupport {
    private BrunoFormatSupport() {
    }

    static String normalizePhysicalLines(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    static List<String> physicalLines(String value) {
        return List.of(normalizePhysicalLines(value).split("\n", -1));
    }

    static String renderKey(String value, String context, List<String> warnings) {
        String sanitized = sanitizeKeyText(value, context, warnings);
        if (isPlainKey(sanitized)) {
            return sanitized;
        }
        return '"' + sanitized.replace("\"", "\\\"") + '"';
    }

    static String uniqueRenderedKey(String value,
                                    Set<String> usedRenderedKeys,
                                    String context,
                                    List<String> warnings) {
        return renderKey(uniqueKeyText(value, usedRenderedKeys, context, warnings), context, warnings);
    }

    static String uniqueKeyText(String value,
                                Set<String> usedKeys,
                                String context,
                                List<String> warnings) {
        String original = value != null ? value : "";
        String sanitized = sanitizeKeyText(original, context, warnings);
        String candidate = sanitized;
        String key = candidate.toLowerCase(Locale.ROOT);
        if (java.util.Objects.equals(original, sanitized)) {
            if (usedKeys.contains("!sanitized:" + key)) {
                int suffix = 2;
                do {
                    candidate = sanitized + "_" + suffix++;
                    key = candidate.toLowerCase(Locale.ROOT);
                } while (!usedKeys.add(key));
                ExportWarningSupport.add(warnings, "Bruno export renamed a colliding key in "
                        + ExportWarningSupport.label(context) + ".");
                return candidate;
            }
            usedKeys.add(key);
            return candidate;
        }
        int suffix = 2;
        while (!usedKeys.add(key)) {
            candidate = sanitized + "_" + suffix++;
            key = candidate.toLowerCase(Locale.ROOT);
            ExportWarningSupport.add(warnings, "Bruno export renamed a colliding key in "
                    + ExportWarningSupport.label(context) + ".");
        }
        usedKeys.add("!sanitized:" + key);
        return candidate;
    }

    static Set<String> newKeySet() {
        return new LinkedHashSet<>();
    }

    static void appendDictionaryEntry(StringBuilder out,
                                      String indentation,
                                      String prefix,
                                      String renderedKey,
                                      String value,
                                      String context,
                                      List<String> warnings) throws IOException {
        String safeValue = sanitizeText(value, context + " value", warnings);
        out.append(indentation).append(prefix != null ? prefix : "").append(renderedKey).append(": ");
        appendDictionaryValue(out, indentation, safeValue, context);
    }

    static void appendDictionaryValue(StringBuilder out,
                                      String entryIndentation,
                                      String value,
                                      String context) throws IOException {
        String normalized = normalizePhysicalLines(value);
        boolean multiline = normalized.indexOf('\n') >= 0
                || !normalized.equals(normalized.strip());
        if (!multiline) {
            out.append(normalized).append('\n');
            return;
        }
        if (normalized.contains("'''")) {
            throw new IOException("Bruno export cannot represent a multiline value containing the triple-apostrophe delimiter in "
                    + ExportWarningSupport.label(context) + ".");
        }
        out.append("'''\n");
        String contentIndent = entryIndentation + "  ";
        for (String line : physicalLines(normalized)) {
            out.append(contentIndent).append(line).append('\n');
        }
        out.append(entryIndentation).append("'''\n");
    }

    static void appendTextBlock(StringBuilder out, String name, String source,
                                String context, List<String> warnings) {
        String safeSource = sanitizeText(source, context, warnings);
        out.append(name).append(" {\n");
        for (String line : physicalLines(safeSource)) {
            out.append("  ").append(line).append('\n');
        }
        out.append("}\n\n");
    }

    static String sanitizeText(String value, String context, List<String> warnings) {
        String input = value != null ? value : "";
        StringBuilder safe = new StringBuilder(input.length());
        boolean replaced = false;
        for (int index = 0; index < input.length(); index++) {
            char ch = input.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 < input.length() && Character.isLowSurrogate(input.charAt(index + 1))) {
                    safe.append(ch).append(input.charAt(++index));
                } else {
                    safe.append('\ufffd');
                    replaced = true;
                }
            } else if (Character.isLowSurrogate(ch) || ch == 0 || (ch < 0x20 && ch != '\r' && ch != '\n' && ch != '\t') || ch == 0x7f) {
                safe.append('\ufffd');
                replaced = true;
            } else {
                safe.append(ch);
            }
        }
        if (replaced) {
            ExportWarningSupport.add(warnings, "Bruno export replaced an unsafe character in "
                    + ExportWarningSupport.label(context) + ".");
        }
        return safe.toString();
    }

    static String sanitizeKeyText(String value, String context, List<String> warnings) {
        String input = value != null ? value : "";
        StringBuilder safe = new StringBuilder(input.length());
        boolean replaced = false;
        for (int index = 0; index < input.length(); index++) {
            char ch = input.charAt(index);
            if (Character.isHighSurrogate(ch) && index + 1 < input.length()
                    && Character.isLowSurrogate(input.charAt(index + 1))) {
                safe.append(ch).append(input.charAt(++index));
            } else if (Character.isSurrogate(ch) || ch < 0x20 || ch == 0x7f) {
                safe.append('\ufffd');
                replaced = true;
            } else {
                safe.append(ch);
            }
        }
        if (replaced) {
            ExportWarningSupport.add(warnings, "Bruno export replaced an unsafe character in "
                    + ExportWarningSupport.label(context) + " key.");
        }
        return safe.toString();
    }

    static boolean isSafeFilePath(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < 0x20 || ch == 0x7f || Character.isSurrogate(ch)) {
                if (Character.isHighSurrogate(ch) && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    index++;
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private static boolean isPlainKey(String value) {
        if (value == null || value.isEmpty() || value.startsWith("~")
                || !value.equals(value.strip())) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == ':' || ch == ' ' || ch == '\t' || ch == '"' || ch == '{' || ch == '}') {
                return false;
            }
        }
        return true;
    }
}
