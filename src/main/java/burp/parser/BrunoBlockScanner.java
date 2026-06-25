package burp.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structural Bruno block scanner that preserves nested braces and treats known
 * Bruno text blocks as opaque text until their structural closing delimiter.
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

    private static final Set<String> TEXT_BLOCK_NAMES = Set.of(
            "body", "body:json", "body:text", "body:xml", "body:graphql", "body:graphql:vars",
            "script:pre-request", "script:post-response", "tests", "assert", "test", "body:test",
            "docs"
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
                index = nextLineStart(source, lineEnd);
                continue;
            }

            int nameStart = cursor;
            int nameEnd = cursor + 1;
            while (nameEnd < lineEnd && isBlockNameChar(source.charAt(nameEnd))) {
                nameEnd++;
            }
            String name = source.substring(nameStart, nameEnd).trim();
            if (name.isEmpty() || JAVA_SCRIPT_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) {
                index = nextLineStart(source, lineEnd);
                continue;
            }

            int openBraceIndex = findOpenBraceIndex(source, nameEnd, lineEnd);
            if (openBraceIndex < 0) {
                index = nextLineStart(source, lineEnd);
                continue;
            }

            String inlineText = source.substring(nameEnd, openBraceIndex).trim();
            int closeIndex = findStructuralTextBlockClose(source, lineStart, lineEnd);
            if (closeIndex < 0) {
                malformedBlocks.add(name);
                malformedStarts.add(nameStart);
                index = nextLineStart(source, lineEnd);
                continue;
            }

            blocks.add(new Block(name, inlineText, source.substring(openBraceIndex + 1, closeIndex), nameStart, openBraceIndex, closeIndex));
            int closeLineEnd = findLineEnd(source, closeIndex);
            index = nextLineStart(source, closeLineEnd);
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

    private static int findOpenBraceIndex(String source, int fromIndex, int lineEnd) {
        if (source == null || fromIndex < 0 || lineEnd < fromIndex) {
            return -1;
        }
        for (int i = fromIndex; i < lineEnd; i++) {
            if (source.charAt(i) == '{') {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingBraceStructural(String source, int openBraceIndex) {
        if (source == null || openBraceIndex < 0 || openBraceIndex >= source.length() || source.charAt(openBraceIndex) != '{') {
            return -1;
        }
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

    private static int findStructuralTextBlockClose(String source, int declarationLineStart, int declarationLineEnd) {
        int declarationIndent = skipLeadingWhitespace(source, declarationLineStart, declarationLineEnd) - declarationLineStart;
        int openBraceIndex = -1;
        for (int i = declarationLineStart; i < declarationLineEnd; i++) {
            if (source.charAt(i) == '{') {
                openBraceIndex = i;
                break;
            }
        }
        if (openBraceIndex >= 0) {
            int cursor = openBraceIndex + 1;
            while (cursor < declarationLineEnd && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor < declarationLineEnd
                    && source.charAt(cursor) == '}'
                    && isOnlyWhitespaceAfter(source, cursor + 1, declarationLineEnd)
                    && (cursor - declarationLineStart) == declarationIndent) {
                return cursor;
            }
        }
        int lineStart = nextLineStart(source, declarationLineEnd);
        while (lineStart < source.length()) {
            int lineEnd = findLineEnd(source, lineStart);
            int cursor = skipLeadingWhitespace(source, lineStart, lineEnd);
            if (cursor < lineEnd
                    && source.charAt(cursor) == '}'
                    && isOnlyWhitespaceAfter(source, cursor + 1, lineEnd)
                    && (cursor - lineStart) == declarationIndent) {
                return cursor;
            }
            if ((cursor - lineStart) <= declarationIndent && isBlockDeclarationLine(source, cursor, lineEnd)) {
                return -1;
            }
            lineStart = nextLineStart(source, lineEnd);
        }
        return -1;
    }

    private static boolean isBlockDeclarationLine(String source, int cursor, int lineEnd) {
        if (source == null || cursor < 0 || lineEnd <= cursor || cursor >= source.length()) {
            return false;
        }
        if (!isBlockStartChar(source.charAt(cursor))) {
            return false;
        }
        int nameEnd = cursor + 1;
        while (nameEnd < lineEnd && isBlockNameChar(source.charAt(nameEnd))) {
            nameEnd++;
        }
        int braceIndex = findOpenBraceIndex(source, nameEnd, lineEnd);
        return braceIndex >= 0;
    }

    private static boolean isOnlyWhitespaceAfter(String source, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                return false;
            }
        }
        return true;
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
            if (previous == '\n' || previous == '\r') {
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

    private static int nextLineStart(String source, int lineEnd) {
        if (source == null || lineEnd >= source.length()) {
            return source == null ? -1 : source.length();
        }
        int next = lineEnd + 1;
        if (source.charAt(lineEnd) == '\r' && next < source.length() && source.charAt(next) == '\n') {
            next++;
        }
        return next;
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

    private static boolean isKnownTextBlock(String name) {
        if (name == null) {
            return false;
        }
        return TEXT_BLOCK_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
    }
}
