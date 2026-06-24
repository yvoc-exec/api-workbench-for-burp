package burp.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Brace-aware Bruno block scanner that preserves nested braces, quoted strings,
 * escaped characters, and same-line inline content.
 */
final class BrunoBlockScanner {
    private BrunoBlockScanner() {
    }

    static final class Block {
        final String name;
        final String inlineText;
        final String content;
        final int startIndex;
        final int openBraceIndex;
        final int closeBraceIndex;

        Block(String name, String inlineText, String content, int startIndex, int openBraceIndex, int closeBraceIndex) {
            this.name = name;
            this.inlineText = inlineText;
            this.content = content;
            this.startIndex = startIndex;
            this.openBraceIndex = openBraceIndex;
            this.closeBraceIndex = closeBraceIndex;
        }

        String normalizedName() {
            return name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
        }
    }

    static List<Block> scan(String source) {
        if (source == null || source.isBlank()) {
            return Collections.emptyList();
        }

        List<Block> blocks = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (!isBlockStartChar(current)) {
                index++;
                continue;
            }
            int nameStart = index;
            int nameEnd = index + 1;
            while (nameEnd < source.length() && isBlockNameChar(source.charAt(nameEnd))) {
                nameEnd++;
            }
            String name = source.substring(nameStart, nameEnd).trim();
            if (name.isEmpty()) {
                index++;
                continue;
            }

            int cursor = nameEnd;
            boolean sawLineBreak = false;
            while (cursor < source.length()) {
                char ch = source.charAt(cursor);
                if (ch == '{') {
                    String inlineText = source.substring(nameEnd, cursor).trim();
                    int closeIndex = findMatchingBrace(source, cursor);
                    if (closeIndex < 0) {
                        throw new IllegalArgumentException("Unclosed Bruno block: " + name);
                    }
                    String content = source.substring(cursor + 1, closeIndex);
                    blocks.add(new Block(name, inlineText, content, nameStart, cursor, closeIndex));
                    index = closeIndex + 1;
                    break;
                }
                if (ch == '\n' || ch == '\r') {
                    sawLineBreak = true;
                    break;
                }
                cursor++;
            }

            if (cursor >= source.length()) {
                break;
            }
            if (cursor >= nameEnd && source.charAt(cursor) != '{' && sawLineBreak) {
                index = nameEnd;
                continue;
            }
            if (cursor < source.length() && source.charAt(cursor) != '{') {
                index = nameEnd;
                continue;
            }
        }
        return blocks;
    }

    static List<Block> findByName(List<Block> blocks, String name) {
        if (blocks == null || blocks.isEmpty() || name == null) {
            return List.of();
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        List<Block> matches = new ArrayList<>();
        for (Block block : blocks) {
            if (block != null && normalized.equals(block.normalizedName())) {
                matches.add(block);
            }
        }
        return matches;
    }

    static Block firstByName(List<Block> blocks, String... names) {
        if (blocks == null || blocks.isEmpty() || names == null || names.length == 0) {
            return null;
        }
        for (Block block : blocks) {
            if (block == null) {
                continue;
            }
            String normalized = block.normalizedName();
            for (String candidate : names) {
                if (candidate != null && normalized.equals(candidate.trim().toLowerCase(Locale.ROOT))) {
                    return block;
                }
            }
        }
        return null;
    }

    private static boolean isBlockStartChar(char ch) {
        return Character.isLetter(ch) || ch == '_' || ch == '$';
    }

    private static boolean isBlockNameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == ':';
    }

    private static int findMatchingBrace(String source, int openBraceIndex) {
        if (source == null || openBraceIndex < 0 || openBraceIndex >= source.length() || source.charAt(openBraceIndex) != '{') {
            return -1;
        }
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = openBraceIndex; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && (inSingleQuote || inDoubleQuote)) {
                escaped = true;
                continue;
            }
            if (inSingleQuote) {
                if (ch == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                if (ch == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (ch == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
