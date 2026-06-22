package burp.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VariableTokenScanner {
    public static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.:-]+)\\s*\\}\\}");

    private VariableTokenScanner() {
    }

    public static List<VariableToken> scan(String text, Map<String, String> values) {
        return scan(text, key -> values != null ? values.get(key) : null, key -> values != null && values.containsKey(key));
    }

    public static List<VariableToken> scan(String text,
                                           Function<String, String> resolver,
                                           Function<String, Boolean> hasValue) {
        List<VariableToken> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = normalizeKey(matcher.group(1));
            String value = resolver != null ? resolver.apply(key) : null;
            boolean resolved = hasValue != null ? Boolean.TRUE.equals(hasValue.apply(key)) : value != null;
            tokens.add(new VariableToken(
                    matcher.start(),
                    matcher.end(),
                    matcher.group(),
                    key,
                    resolved ? VariableResolutionStatus.RESOLVED : VariableResolutionStatus.UNRESOLVED,
                    value
            ));
        }
        return tokens;
    }

    public static VariableToken tokenAt(String text, int offset, Map<String, String> values) {
        if (text == null || text.isEmpty() || offset < 0 || offset > text.length()) {
            return null;
        }
        List<VariableToken> tokens = scan(text, values);
        for (VariableToken token : tokens) {
            if (token != null && offset >= token.start && offset <= token.end) {
                return token;
            }
        }
        return null;
    }

    public static String normalizeKey(String key) {
        return key != null ? key.trim() : null;
    }

    public static final class VariableToken {
        public final int start;
        public final int end;
        public final String rawText;
        public final String key;
        public final VariableResolutionStatus status;
        public final String value;

        public VariableToken(int start, int end, String rawText, String key, VariableResolutionStatus status, String value) {
            this.start = start;
            this.end = end;
            this.rawText = rawText;
            this.key = key;
            this.status = status != null ? status : VariableResolutionStatus.UNRESOLVED;
            this.value = value;
        }

        public boolean isResolved() {
            return status == VariableResolutionStatus.RESOLVED;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof VariableToken other)) return false;
            return start == other.start && end == other.end
                    && Objects.equals(rawText, other.rawText)
                    && Objects.equals(key, other.key)
                    && status == other.status
                    && Objects.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end, rawText, key, status, value);
        }
    }
}
