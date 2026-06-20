package burp.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyleConstants;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableTokenScannerTest {

    @Test
    void scanClassifiesResolvedAndUnresolvedTokens() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("baseUrl", "https://api.example.test");

        List<VariableTokenScanner.VariableToken> tokens = VariableTokenScanner.scan(
                "GET {{ baseUrl }}/{{missing}}", variables);

        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).key).isEqualTo("baseUrl");
        assertThat(tokens.get(0).status).isEqualTo(VariableResolutionStatus.RESOLVED);
        assertThat(tokens.get(0).value).isEqualTo("https://api.example.test");
        assertThat(tokens.get(1).key).isEqualTo("missing");
        assertThat(tokens.get(1).status).isEqualTo(VariableResolutionStatus.UNRESOLVED);
    }

    @Test
    void highlightStylerUsesForegroundColorAndDoesNotMutateComponentText() {
        JTextPane area = new JTextPane();
        area.setText("curl {{token}}");
        Map<String, String> variables = Map.of("token", "abc123");

        VariableHighlightStyler.apply(area, area.getText(), variables);

        assertThat(area.getText()).isEqualTo("curl {{token}}");
        StyledDocument document = area.getStyledDocument();
        AttributeSet base = document.getCharacterElement(0).getAttributes();
        AttributeSet token = document.getCharacterElement(area.getText().indexOf("{{token}}") + 2).getAttributes();
        assertThat(StyleConstants.getForeground(token)).isNotEqualTo(StyleConstants.getForeground(base));
        assertThat(area.getHighlighter().getHighlights()).isEmpty();
    }

    @Test
    void scanSupportsCustomResolutionPredicatesAndNormalizesKeys() {
        List<VariableTokenScanner.VariableToken> tokens = VariableTokenScanner.scan(
                "GET {{  base_url  }}/{{missing}}",
                key -> "base_url".equals(key) ? "https://api.example.test" : null,
                key -> "base_url".equals(key)
        );

        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).rawText).isEqualTo("{{  base_url  }}");
        assertThat(tokens.get(0).key).isEqualTo("base_url");
        assertThat(tokens.get(0).isResolved()).isTrue();
        assertThat(tokens.get(0).value).isEqualTo("https://api.example.test");
        assertThat(tokens.get(1).key).isEqualTo("missing");
        assertThat(tokens.get(1).isResolved()).isFalse();
        assertThat(VariableTokenScanner.normalizeKey("  spaced.key  ")).isEqualTo("spaced.key");
        assertThat(VariableTokenScanner.normalizeKey(null)).isNull();
    }

    @Test
    void tokenAtHonorsBoundsAndRejectsInvalidOffsets() {
        String text = "before {{token}} after";
        Map<String, String> variables = Map.of("token", "abc123");

        VariableTokenScanner.VariableToken tokenAtStart = VariableTokenScanner.tokenAt(text, text.indexOf("{{token}}"), variables);
        VariableTokenScanner.VariableToken tokenAtEnd = VariableTokenScanner.tokenAt(text, text.indexOf("{{token}}") + "{{token}}".length(), variables);

        assertThat(tokenAtStart).isNotNull();
        assertThat(tokenAtStart.key).isEqualTo("token");
        assertThat(tokenAtEnd).isEqualTo(tokenAtStart);
        assertThat(VariableTokenScanner.tokenAt(text, -1, variables)).isNull();
        assertThat(VariableTokenScanner.tokenAt(text, text.length() + 1, variables)).isNull();
        assertThat(VariableTokenScanner.tokenAt("", 0, variables)).isNull();
        assertThat(VariableTokenScanner.tokenAt(null, 0, variables)).isNull();
    }

    @Test
    void variableTokenEqualityAndHashCodeIncludeResolutionState() {
        VariableTokenScanner.VariableToken resolvedA = new VariableTokenScanner.VariableToken(
                0, 9, "{{token}}", "token", VariableResolutionStatus.RESOLVED, "abc123");
        VariableTokenScanner.VariableToken resolvedB = new VariableTokenScanner.VariableToken(
                0, 9, "{{token}}", "token", VariableResolutionStatus.RESOLVED, "abc123");
        VariableTokenScanner.VariableToken unresolved = new VariableTokenScanner.VariableToken(
                0, 9, "{{token}}", "token", VariableResolutionStatus.UNRESOLVED, null);

        assertThat(resolvedA).isEqualTo(resolvedB);
        assertThat(resolvedA.hashCode()).isEqualTo(resolvedB.hashCode());
        assertThat(resolvedA).isNotEqualTo(unresolved);
        assertThat(resolvedA).isNotEqualTo("token");
        assertThat(resolvedA).isEqualTo(resolvedA);
    }
}
