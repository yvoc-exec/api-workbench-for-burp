package burp.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Brace-aware Bruno block scanner that preserves nested braces, quoted strings,
 * escaped characters, and same-line inline content.
 */
final class BrunoBlockScanner {
    private BrunoBlockScanner() {
    }

    private static final Set<String> JAVA_SCRIPT_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "case", "catch", "try", "do", "else",
            "return", "function", "class", "const", "let", "var", "new", "throw",
            "break", "continue", "default", "import", "export", "extends", "super",
            "yield", "await", "async", "typeof", "instanceof", "in", "of", "void", "delete"
    );

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

    static final class ScanResult {
        final List<Block> blocks;
        final List<String> malformedBlocks;
        final List<Integer> malformedBlockStartIndices;

        ScanResult(List<Block> blocks, List<String> malformedBlocks, List<Integer> malformedBlockStartIndices) {
            this.blocks = blocks;
            this.malformedBlocks = malformedBlocks;
            this.malformedBlockStartIndices = malformedBlockStartIndices;
        }
    }

    static List<Block> scan(String source) {
        return scanDetailed(source).blocks;
    }

    static ScanResult scanDetailed(String source) {
        if (source == null || source.isBlank()) {
            return new ScanResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        List<Block> blocks = new ArrayList<>();
        List<String> malformedBlocks = new ArrayList<>();
        List<Integer> malformedStarts = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            int lineStart = skipToLineStart(source, index);
            if (lineStart < 0 || lineStart >= source.length()) {
                break;
            }
            int lineEnd = findLineEnd(source, lineStart);
            int cursor = skipLeadingWhitespace(source, lineStart, lineEnd);
            if (cursor >= lineEnd || !isBlockStartChar(source.charAt(cursor))) {
                index = lineEnd + 1;
                continue;
            }
            int nameStart = cursor;
            int nameEnd = cursor + 1;
            while (nameEnd < lineEnd && isBlockNameChar(source.charAt(nameEnd))) {
                nameEnd++;
            }
            String name = source.substring(nameStart, nameEnd).trim();
            if (name.isEmpty() || JAVA_SCRIPT_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) {
                index = lineEnd + 1;
                continue;
            }

            int openBraceIndex = -1;
            for (int braceCursor = nameEnd; braceCursor < lineEnd; braceCursor++) {
                if (source.charAt(braceCursor) == '{') {
                    openBraceIndex = braceCursor;
                    break;
                }
            }
            if (openBraceIndex < 0) {
                index = lineEnd + 1;
                continue;
            }

            String inlineText = source.substring(nameEnd, openBraceIndex).trim();
            int closeIndex = findMatchingBrace(source, openBraceIndex, isLexicalTextBlock(name));
            if (closeIndex < 0) {
                malformedBlocks.add(name);
                malformedStarts.add(nameStart);
                index = lineEnd + 1;
                continue;
            }

            String content = source.substring(openBraceIndex + 1, closeIndex);
            blocks.add(new Block(name, inlineText, content, nameStart, openBraceIndex, closeIndex));
            index = closeIndex + 1;
        }
        return new ScanResult(blocks, malformedBlocks, malformedStarts);
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

    private static int findMatchingBrace(String source, int openBraceIndex, boolean lexicalAware) {
        if (source == null || openBraceIndex < 0 || openBraceIndex >= source.length() || source.charAt(openBraceIndex) != '{') {
            return -1;
        }
        if (!lexicalAware) {
            return findMatchingBraceStructural(source, openBraceIndex);
        }
        return findMatchingBraceLexical(source, openBraceIndex);
    }

    private static int findMatchingBraceStructural(String source, int openBraceIndex) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inTemplateLiteral = false;
        boolean escaped = false;

        for (int i = openBraceIndex; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && (inSingleQuote || inDoubleQuote || inTemplateLiteral)) {
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
            if (inTemplateLiteral) {
                if (ch == '`') {
                    inTemplateLiteral = false;
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
            if (ch == '`') {
                inTemplateLiteral = true;
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

    private static int findMatchingBraceLexical(String source, int openBraceIndex) {
        int depth = 0;
        Mode mode = Mode.NORMAL;
        int templateExpressionDepth = 0;
        boolean escaped = false;
        boolean regexCharClass = false;

        for (int i = openBraceIndex; i < source.length(); i++) {
            char ch = source.charAt(i);

            if (mode == Mode.LINE_COMMENT) {
                if (ch == '\n' || ch == '\r') {
                    mode = Mode.NORMAL;
                }
                continue;
            }

            if (mode == Mode.BLOCK_COMMENT) {
                if (ch == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    mode = Mode.NORMAL;
                    i++;
                }
                continue;
            }

            if (mode == Mode.SINGLE_QUOTE || mode == Mode.DOUBLE_QUOTE) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if ((mode == Mode.SINGLE_QUOTE && ch == '\'') || (mode == Mode.DOUBLE_QUOTE && ch == '"')) {
                    mode = Mode.NORMAL;
                }
                continue;
            }

            if (mode == Mode.TEMPLATE_TEXT) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '`') {
                    mode = Mode.NORMAL;
                    continue;
                }
                if (ch == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                    depth++;
                    templateExpressionDepth = 1;
                    mode = Mode.TEMPLATE_EXPRESSION;
                    i++;
                }
                continue;
            }

            if (mode == Mode.TEMPLATE_EXPRESSION) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '\'') {
                    mode = Mode.SINGLE_QUOTE;
                    continue;
                }
                if (ch == '"') {
                    mode = Mode.DOUBLE_QUOTE;
                    continue;
                }
                if (ch == '`') {
                    mode = Mode.TEMPLATE_TEXT;
                    continue;
                }
                if (ch == '/' && i + 1 < source.length()) {
                    char next = source.charAt(i + 1);
                    if (next == '/') {
                        mode = Mode.LINE_COMMENT;
                        i++;
                        continue;
                    }
                    if (next == '*') {
                        mode = Mode.BLOCK_COMMENT;
                        i++;
                        continue;
                    }
                    if (isRegexStart(source, i)) {
                        mode = Mode.REGEX_LITERAL;
                        regexCharClass = false;
                        continue;
                    }
                }
                if (ch == '{') {
                    depth++;
                    templateExpressionDepth++;
                } else if (ch == '}') {
                    depth--;
                    templateExpressionDepth--;
                    if (depth == 0) {
                        return i;
                    }
                    if (templateExpressionDepth == 0) {
                        mode = Mode.TEMPLATE_TEXT;
                    }
                }
                continue;
            }

            if (mode == Mode.REGEX_LITERAL) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '[') {
                    regexCharClass = true;
                    continue;
                }
                if (ch == ']' && regexCharClass) {
                    regexCharClass = false;
                    continue;
                }
                if (ch == '/' && !regexCharClass) {
                    mode = Mode.NORMAL;
                }
                continue;
            }

            if (ch == '\'') {
                mode = Mode.SINGLE_QUOTE;
                continue;
            }
            if (ch == '"') {
                mode = Mode.DOUBLE_QUOTE;
                continue;
            }
            if (ch == '`') {
                mode = Mode.TEMPLATE_TEXT;
                continue;
            }
            if (ch == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    mode = Mode.LINE_COMMENT;
                    i++;
                    continue;
                }
                if (next == '*') {
                    mode = Mode.BLOCK_COMMENT;
                    i++;
                    continue;
                }
                if (isRegexStart(source, i)) {
                    mode = Mode.REGEX_LITERAL;
                    regexCharClass = false;
                    continue;
                }
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

    private static boolean isLexicalTextBlock(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "body", "body:json", "body:text", "body:xml", "body:graphql", "body:graphql:vars",
                    "script:pre-request", "script:post-response", "tests", "assert", "test", "body:test",
                    "docs" -> true;
            default -> false;
        };
    }

    private static int skipToLineStart(String source, int index) {
        if (source == null) {
            return -1;
        }
        if (index <= 0) {
            return 0;
        }
        int cursor = index;
        if (cursor < source.length()) {
            char previous = source.charAt(cursor - 1);
            if (previous == '\n') {
                return cursor;
            }
            if (previous == '\r') {
                return cursor;
            }
        }
        while (cursor < source.length()) {
            char ch = source.charAt(cursor++);
            if (ch == '\n') {
                return cursor;
            }
            if (ch == '\r') {
                if (cursor < source.length() && source.charAt(cursor) == '\n') {
                    cursor++;
                }
                return cursor;
            }
        }
        return source.length();
    }

    private static int findLineEnd(String source, int lineStart) {
        int cursor = lineStart;
        while (cursor < source.length()) {
            char ch = source.charAt(cursor);
            if (ch == '\n' || ch == '\r') {
                return cursor;
            }
            cursor++;
        }
        return source.length();
    }

    private static int skipLeadingWhitespace(String source, int lineStart, int lineEnd) {
        int cursor = lineStart;
        while (cursor < lineEnd) {
            char ch = source.charAt(cursor);
            if (!Character.isWhitespace(ch)) {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private static boolean isRegexStart(String source, int slashIndex) {
        int previous = slashIndex - 1;
        while (previous >= 0) {
            char ch = source.charAt(previous);
            if (!Character.isWhitespace(ch)) {
                return ch == '=' || ch == '(' || ch == '[' || ch == '{' || ch == ':' || ch == ',' || ch == ';'
                        || ch == '!' || ch == '?' || ch == '&' || ch == '|' || ch == '^' || ch == '~'
                        || ch == '<' || ch == '>' || ch == '+' || ch == '-' || ch == '*' || ch == '%' || ch == '\n'
                        || ch == '\r';
            }
            previous--;
        }
        return true;
    }

    private enum Mode {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        TEMPLATE_TEXT,
        TEMPLATE_EXPRESSION,
        LINE_COMMENT,
        BLOCK_COMMENT,
        REGEX_LITERAL
    }
}
