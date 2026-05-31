package burp.models;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    @Test
    void fireChangedContinuesAfterListenerException() {
        ApiCollection col = new ApiCollection();
        AtomicInteger count = new AtomicInteger(0);
        col.addChangeListener(() -> { throw new RuntimeException("boom"); });
        col.addChangeListener(count::incrementAndGet);

        assertThatCode(col::fireChanged).doesNotThrowAnyException();
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void fireChangedLogsListenerException() {
        ApiCollection col = new ApiCollection();
        col.addChangeListener(() -> { throw new RuntimeException("boom"); });

        Logger logger = Logger.getLogger(ApiCollection.class.getName());
        Level originalLevel = logger.getLevel();
        boolean originalUseParentHandlers = logger.getUseParentHandlers();
        AtomicReference<LogRecord> recordRef = new AtomicReference<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    recordRef.compareAndSet(null, record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        try {
            col.fireChanged();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(originalLevel);
            logger.setUseParentHandlers(originalUseParentHandlers);
        }

        assertThat(recordRef.get()).isNotNull();
        assertThat(recordRef.get().getLevel()).isEqualTo(Level.WARNING);
        assertThat(recordRef.get().getMessage()).contains("change listener failed");
        assertThat(recordRef.get().getThrown()).isInstanceOf(RuntimeException.class);
    }
}
