package burp.models;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ApiCollectionTest {

    @Test
    void listenerFiresOnPutAllRuntimeVars() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);

        col.putAllRuntimeVars(Map.of("k1", "v1"));
        assertThat(count.get()).isEqualTo(1);

        col.putRuntimeVar("k2", "v2");
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    void listenerFiresOnPutAllRuntimeOAuth2() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);

        col.putAllRuntimeOAuth2(Map.of("oauth2_grant", "client_credentials"));
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void noFireOnEmptyPutAll() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);

        col.putAllRuntimeVars(Map.of());
        assertThat(count.get()).isEqualTo(0);
    }

    @Test
    void clearListenersRemovesAll() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);
        col.clearChangeListeners();

        col.putRuntimeVar("k", "v");
        assertThat(count.get()).isEqualTo(0);
    }
}
