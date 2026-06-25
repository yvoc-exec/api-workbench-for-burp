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

    private static final Set<String> KNOWN_BRUNO_DECLARATION_NAMES = Set.of(
            "meta",
            "app",
            "settings",
            "grpc",
            "ws",
            "http",
            "query",
            "headers",
            "metadata",
            "params",
            "params:path",
            "params:query",
            "vars",
            "vars:pre-request",
            "vars:post-response",
            "assert",
            "test",
            "tests",
            "docs",
            "example",
            "body",
            "body:none",
            "body:json",
            "body:text",
            "body:xml",
            "body:sparql",
            "body:graphql",
            "body:graphql:vars",
            "body:grpc",
            "body:ws",
            "body:form-urlencoded",
            "body:multipart-form",
            "body:file",
            "body:test",
            "auth",
            "auth:none",
            "auth:noauth",
            "auth:no_auth",
            "auth:basic",
            "auth:bearer",
            "auth:apikey",
            "auth:awsv4",
            "auth:digest",
            "auth:ntlm",
            "auth:oauth1",
            "auth:oauth2",
            "auth:wsse",
            "auth:oauth2:additional_params:auth_req:headers",
            "auth:oauth2:additional_params:auth_req:queryparams",
            "auth:oauth2:additional_params:auth_req:body",
            "auth:oauth2:additional_params:access_token_req:headers",
            "auth:oauth2:additional_params:access_token_req:queryparams",
            "auth:oauth2:additional_params:access_token_req:body",
            "auth:oauth2:additional_params:refresh_token_req:headers",
            "auth:oauth2:additional_params:refresh_token_req:queryparams",
            "auth:oauth2:additional_params:refresh_token_req:body",
            "script",
            "script:pre-request",
            "script:post-response"
    );

    private static final Set<String> STANDARD_HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "head", "options", "trace", "connect"
    );

    private static final Set<String> OPAQUE_TEXT_BLOCK_NAMES = Set.of(
            "body",
            "body:json",
            "body:text",
            "body:xml",
            "body:sparql",
            "body:graphql",
            "body:graphql:vars",
            "script:pre-request",
            "script:post-response",
            "tests",
            "docs",
            "example",
            "assert",
            "test",
            "body:test"
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
            if (name.isEmpty()) {
                index = nextLineStart(source, lineEnd);
                continue;
            }
            if (!isTopLevelDeclarationCandidate(source, nameStart, lineEnd)) {
                index = nextLineStart(source, lineEnd);
                continue;
            }

            int openBraceIndex = findOpenBraceIndex(source, nameEnd, lineEnd);
            if (openBraceIndex < 0 || !isOnlyWhitespaceAfter(source, nameEnd, openBraceIndex)) {
                index = nextLineStart(source, lineEnd);
                continue;
            }

            String inlineText = source.substring(nameEnd, openBraceIndex).trim();
            int closeIndex = isKnownTextBlock(name)
                    ? findStructuralTextBlockClose(source, lineStart, lineEnd, openBraceIndex)
                    : findMatchingBraceStructural(source, openBraceIndex);
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
        int lineStart = findLineStart(source, openBraceIndex);
        int lineEnd = findLineEnd(source, lineStart);

        for (int cursor = lineEnd - 1; cursor > openBraceIndex; cursor--) {
            if (source.charAt(cursor) == '}'
                    && isOnlyWhitespaceAfter(source, cursor + 1, lineEnd)) {
                return cursor;
            }
        }

        int declarationIndent = skipLeadingWhitespace(source, lineStart, lineEnd) - lineStart;
        lineStart = nextLineStart(source, lineEnd);
        boolean inTripleQuotedValue = false;
        while (lineStart < source.length()) {
            lineEnd = findLineEnd(source, lineStart);
            int cursor = skipLeadingWhitespace(source, lineStart, lineEnd);
            if (containsTripleApostropheDelimiter(source, lineStart, lineEnd)) {
                if ((countTripleApostropheDelimiters(source, lineStart, lineEnd) & 1) == 1) {
                    inTripleQuotedValue = !inTripleQuotedValue;
                }
                lineStart = nextLineStart(source, lineEnd);
                continue;
            }
            if (inTripleQuotedValue) {
                lineStart = nextLineStart(source, lineEnd);
                continue;
            }
            if (cursor < lineEnd
                    && source.charAt(cursor) == '}'
                    && isOnlyWhitespaceAfter(source, cursor + 1, lineEnd)) {
                if ((cursor - lineStart) == declarationIndent) {
                    return cursor;
                }
            } else if ((cursor - lineStart) <= declarationIndent && isRecoverySiblingDeclaration(source, cursor, lineEnd)) {
                return -1;
            }
            lineStart = nextLineStart(source, lineEnd);
        }
        return -1;
    }

    private static int findStructuralTextBlockClose(String source, int declarationLineStart, int declarationLineEnd, int openBraceIndex) {
        int declarationIndent = skipLeadingWhitespace(source, declarationLineStart, declarationLineEnd) - declarationLineStart;
        int candidateClose = -1;
        if (openBraceIndex >= 0) {
            for (int cursor = declarationLineEnd - 1; cursor > openBraceIndex; cursor--) {
                if (source.charAt(cursor) == '}'
                        && isOnlyWhitespaceAfter(source, cursor + 1, declarationLineEnd)) {
                    return cursor;
                }
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
                candidateClose = cursor;
            }
            if ((cursor - lineStart) <= declarationIndent && isRecoverySiblingDeclaration(source, cursor, lineEnd)) {
                return candidateClose >= 0 ? candidateClose : -1;
            }
            lineStart = nextLineStart(source, lineEnd);
        }
        return candidateClose;
    }

    private static boolean isTopLevelDeclarationCandidate(String source, int cursor, int lineEnd) {
        if (source == null || cursor < 0 || lineEnd <= cursor || cursor >= source.length()) {
            return false;
        }
        String candidate = extractDeclarationCandidate(source, cursor, lineEnd);
        if (candidate == null || candidate.isEmpty() || candidate.endsWith(":")) {
            return false;
        }
        int braceIndex = findOpenBraceIndex(source, cursor + candidate.length(), lineEnd);
        if (braceIndex < 0 || !isOnlyWhitespaceAfter(source, cursor + candidate.length(), braceIndex)) {
            return false;
        }
        if (isKnownBrunoDeclaration(candidate) || isStandardHttpMethod(candidate)) {
            return true;
        }
        if (candidate.indexOf(':') >= 0) {
            return isStructurallyValidColonQualifiedDeclaration(candidate);
        }
        if (JAVA_SCRIPT_KEYWORDS.contains(candidate.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return isBareHttpMethod(candidate);
    }

    private static boolean isRecoverySiblingDeclaration(String source, int cursor, int lineEnd) {
        if (source == null || cursor < 0 || lineEnd <= cursor || cursor >= source.length()) {
            return false;
        }
        String candidate = extractDeclarationCandidate(source, cursor, lineEnd);
        if (candidate == null || candidate.isEmpty() || candidate.endsWith(":")) {
            return false;
        }
        int braceIndex = findOpenBraceIndex(source, cursor + candidate.length(), lineEnd);
        if (braceIndex < 0 || !isOnlyWhitespaceAfter(source, cursor + candidate.length(), braceIndex)) {
            return false;
        }
        if (isKnownBrunoDeclaration(candidate) || isStandardHttpMethod(candidate)) {
            return true;
        }
        return isConservativeCustomMethod(candidate);
    }

    private static String extractDeclarationCandidate(String source, int cursor, int lineEnd) {
        if (source == null || cursor < 0 || lineEnd <= cursor || cursor >= source.length()) {
            return null;
        }
        if (!isBlockStartChar(source.charAt(cursor))) {
            return null;
        }
        int nameEnd = cursor + 1;
        while (nameEnd < lineEnd) {
            char ch = source.charAt(nameEnd);
            if (Character.isWhitespace(ch) || ch == '{') {
                break;
            }
            if (!isBlockNameChar(ch)) {
                return null;
            }
            nameEnd++;
        }
        if (nameEnd <= cursor) {
            return null;
        }
        return source.substring(cursor, nameEnd).trim();
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

    private static int findLineStart(String source, int index) {
        if (source == null || source.isEmpty()) {
            return -1;
        }
        if (index <= 0) {
            return 0;
        }
        int cursor = Math.min(index, source.length());
        while (cursor > 0) {
            char ch = source.charAt(cursor - 1);
            if (ch == '\n' || ch == '\r') {
                break;
            }
            cursor--;
        }
        return cursor;
    }

    private static boolean isKnownTextBlock(String name) {
        if (name == null) {
            return false;
        }
        return OPAQUE_TEXT_BLOCK_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isKnownBrunoDeclaration(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        return KNOWN_BRUNO_DECLARATION_NAMES.contains(normalized);
    }

    private static boolean isStandardHttpMethod(String candidate) {
        if (candidate == null) {
            return false;
        }
        return STANDARD_HTTP_METHODS.contains(candidate.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isBareHttpMethod(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = candidate.trim();
        if (normalizedCandidate.isEmpty()) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < normalizedCandidate.length(); i++) {
            char ch = normalizedCandidate.charAt(i);
            if (Character.isLetter(ch)) {
                hasLetter = true;
            } else if (!Character.isDigit(ch) && ch != '-' && ch != '_') {
                return false;
            }
        }
        return hasLetter;
    }

    private static boolean isConservativeCustomMethod(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = candidate.trim();
        if (normalizedCandidate.isEmpty()) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < normalizedCandidate.length(); i++) {
            char ch = normalizedCandidate.charAt(i);
            if (Character.isLetter(ch)) {
                hasLetter = true;
                if (!Character.isUpperCase(ch)) {
                    return false;
                }
            } else if (!Character.isDigit(ch) && ch != '-' && ch != '_') {
                return false;
            }
        }
        return hasLetter;
    }

    private static boolean isStructurallyValidColonQualifiedDeclaration(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalized = candidate.trim();
        return !normalized.isEmpty()
                && normalized.indexOf(':') > 0
                && !normalized.endsWith(":");
    }

    private static boolean containsTripleApostropheDelimiter(String source, int lineStart, int lineEnd) {
        if (source == null || lineStart < 0 || lineEnd <= lineStart) {
            return false;
        }
        for (int index = lineStart; index <= lineEnd - 3; index++) {
            if (source.charAt(index) == '\'' && source.charAt(index + 1) == '\'' && source.charAt(index + 2) == '\'') {
                return true;
            }
        }
        return false;
    }

    private static int countTripleApostropheDelimiters(String source, int lineStart, int lineEnd) {
        if (source == null || lineStart < 0 || lineEnd <= lineStart) {
            return 0;
        }
        int count = 0;
        for (int index = lineStart; index <= lineEnd - 3; index++) {
            if (source.charAt(index) == '\'' && source.charAt(index + 1) == '\'' && source.charAt(index + 2) == '\'') {
                count++;
                index += 2;
            }
        }
        return count;
    }
}
