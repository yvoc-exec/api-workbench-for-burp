package burp.performance;

import burp.history.HistoryEntry;
import burp.models.ApiCollection;
import burp.models.EnvironmentProfile;
import burp.models.WorkspaceState;
import burp.testsupport.HistoryTestFixtures;
import burp.utils.WorkspaceStateJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "performance.tests.enabled", matches = "true")
class WorkspacePerformanceIT {

    @Test
    void largeWorkspaceRoundTripCompletesWithinBroadBudget() {
        long started = System.nanoTime();
        WorkspaceState state = new WorkspaceState();
        state.activeEnvironmentId = "env-performance";

        for (int i = 0; i < 120; i++) {
            ApiCollection collection = HistoryTestFixtures.sampleCollection();
            collection.name = "Collection-" + i;
            collection.requests = new ArrayList<>(collection.requests);
            state.collections.add(collection);
        }

        for (int i = 0; i < 1000; i++) {
            HistoryEntry entry = HistoryTestFixtures.copyEntry(
                    HistoryTestFixtures.sampleWorkbenchEntry(),
                    "history-" + i,
                    Instant.parse("2026-06-15T10:00:00Z").plusSeconds(i));
            state.historyEntries.add(entry);
        }

        for (int i = 0; i < 20; i++) {
            EnvironmentProfile profile = HistoryTestFixtures.sampleEnvironment();
            profile.id = "env-" + i;
            profile.name = "Environment-" + i;
            state.environments.add(profile);
        }

        String json = WorkspaceStateJson.toJson(state);
        WorkspaceState parsed = WorkspaceStateJson.fromJson(json);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(parsed.collections).hasSize(120);
        assertThat(parsed.historyEntries).hasSize(1000);
        assertThat(parsed.environments).hasSize(20);
        assertThat(elapsedMs).isLessThan(30000L);
    }
}
