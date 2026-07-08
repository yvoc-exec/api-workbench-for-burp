package burp.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;

import static org.assertj.core.api.Assertions.assertThat;

class DialogParentResolverTest {
    @Test
    void parentComponentPrefersProvidedComponent() {
        JPanel panel = new JPanel();

        assertThat(DialogParentResolver.parentComponent(panel)).isSameAs(panel);
    }

    @Test
    void ownerForUnparentedComponentIsSafeInHeadlessAndCliTests() {
        assertThat(DialogParentResolver.ownerFor(new JPanel())).isNull();
    }
}
