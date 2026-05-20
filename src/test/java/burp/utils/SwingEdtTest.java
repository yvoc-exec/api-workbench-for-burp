package burp.utils;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SwingEdtTest {
    @Test
    void callRunsSupplierOnEdtWhenInvokedFromBackgroundThread() throws Exception {
        AtomicBoolean supplierRanOnEdt = new AtomicBoolean(false);

        String value = SwingEdt.call(() -> {
            supplierRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            return "ok";
        });

        assertThat(value).isEqualTo("ok");
        assertThat(supplierRanOnEdt).isTrue();
    }

    @Test
    void callRunsInlineWhenAlreadyOnEdt() throws Exception {
        AtomicBoolean supplierRanOnEdt = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        SwingUtilities.invokeAndWait(() -> {
            try {
                String value = SwingEdt.call(() -> {
                    supplierRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                    return "inline";
                });
                assertThat(value).isEqualTo("inline");
                completed.set(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(completed).isTrue();
        assertThat(supplierRanOnEdt).isTrue();
    }
}
