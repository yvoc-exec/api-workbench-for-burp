package burp.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
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
    void highlightStylerDoesNotMutateComponentText() {
        JTextArea area = new JTextArea("curl {{token}}");
        Map<String, String> variables = Map.of("token", "abc123");

        VariableHighlightStyler.apply(area, area.getText(), variables);

        assertThat(area.getText()).isEqualTo("curl {{token}}");
        assertThat(area.getHighlighter().getHighlights()).isNotEmpty();
    }
}
