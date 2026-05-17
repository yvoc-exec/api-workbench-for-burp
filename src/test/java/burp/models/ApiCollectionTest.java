package burp.models;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
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
    void replaceRuntimeVarsRemovesStaleKeysAddsNewKeysAndNotifiesListeners() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);
        col.runtimeVars.put("stale", "old");

        col.replaceRuntimeVars(Map.of("fresh", "new"));

        assertThat(count.get()).isEqualTo(1);
        assertThat(col.runtimeVars).containsEntry("fresh", "new");
        assertThat(col.runtimeVars).doesNotContainKey("stale");
        assertThat(col.runtimeVars).hasSize(1);
    }

    @Test
    void applyRuntimeVarDeltaPreservesUnrelatedExistingKeys() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);
        col.runtimeVars.put("keep", "same");
        col.runtimeVars.put("changed", "old");

        col.applyRuntimeVarDelta(Map.of("changed", "new"), Set.of());

        assertThat(col.runtimeVars)
                .containsEntry("keep", "same")
                .containsEntry("changed", "new");
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void applyRuntimeVarDeltaRemovesOnlyExplicitRemovedKeys() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);
        col.runtimeVars.put("remove", "old");
        col.runtimeVars.put("concurrent", "keep");

        col.applyRuntimeVarDelta(Map.of("added", "new"), Set.of("remove"));

        assertThat(col.runtimeVars)
                .containsEntry("concurrent", "keep")
                .containsEntry("added", "new")
                .doesNotContainKey("remove");
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void applyRuntimeVarDeltaDoesNotNotifyWhenNoEffectiveChange() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(count::incrementAndGet);
        col.runtimeVars.put("same", "value");

        col.applyRuntimeVarDelta(Map.of("same", "value"), Set.of("missing"));

        assertThat(col.runtimeVars).containsEntry("same", "value");
        assertThat(count.get()).isEqualTo(0);
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
