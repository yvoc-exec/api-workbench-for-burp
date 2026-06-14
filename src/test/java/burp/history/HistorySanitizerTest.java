package burp.history;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistorySanitizerTest {

    @Test
    void safeTextRemovesCarriageReturnsAndNullBytes() {
        assertThat(HistorySanitizer.safeText("  line1\r\nline2\u0000  "))
                .isEqualTo("line1\nline2");
    }

    @Test
    void csvCellQuotesDangerousAndDelimitedValues() {
        assertThat(HistorySanitizer.csvCell("=SUM(1,1)")).isEqualTo("\"'=SUM(1,1)\"");
        assertThat(HistorySanitizer.csvCell("value,with,commas")).isEqualTo("\"value,with,commas\"");
    }

    @Test
    void safeLinesSplitsMultilineInput() {
        assertThat(HistorySanitizer.safeLines("a\r\nb\nc")).containsExactly("a", "b", "c");
    }
}
