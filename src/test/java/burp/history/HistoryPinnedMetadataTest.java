package burp.history;

import burp.testsupport.HistoryTestFixtures;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPinnedMetadataTest {

    @Test
    void pinNotesTagsRoundTrip() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "metadata-roundtrip", Instant.parse("2026-06-15T02:00:00Z"));
        entry.pinned = true;
        entry.analystNotes = "Reviewed";
        entry.tags = new LinkedHashSet<>(List.of("Auth", "Evidence"));
        store.addEntry(entry);

        HistoryEntry stored = store.getById("metadata-roundtrip");
        assertThat(stored.pinned).isTrue();
        assertThat(stored.analystNotes).isEqualTo("Reviewed");
        assertThat(stored.tags).containsExactly("Auth", "Evidence");

        HistoryEntry copied = HistoryEntry.copyOf(stored);
        assertThat(copied.pinned).isTrue();
        assertThat(copied.analystNotes).isEqualTo("Reviewed");
        assertThat(copied.tags).containsExactly("Auth", "Evidence");

        String json = new HistoryJsonExportService().export(List.of(stored));
        JsonObject exported = JsonParser.parseString(json).getAsJsonObject();
        JsonObject exportedEntry = exported.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertThat(exportedEntry.get("pinned").getAsBoolean()).isTrue();
        assertThat(exportedEntry.get("analystNotes").getAsString()).isEqualTo("Reviewed");
        assertThat(exportedEntry.getAsJsonArray("tags")).extracting(item -> item.getAsString())
                .containsExactly("Auth", "Evidence");
    }

    @Test
    void tagNormalizationIsDeterministic() {
        assertThat(HistoryBodyTruncator.normalizeTags("Auth, auth,  IDOR , , idor, Evidence"))
                .containsExactly("Auth", "IDOR", "Evidence");
    }

    @Test
    void csvPreventsFormulaInjectionInNotesAndTags() {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "csv-safe", Instant.parse("2026-06-15T02:00:00Z"));
        entry.analystNotes = "=SUM(1,1)";
        entry.tags = new LinkedHashSet<>(List.of("@danger"));

        String csv = new HistoryCsvExportService().export(List.of(entry));
        assertThat(csv).contains("'=SUM(1,1)");
        assertThat(csv).contains("'@danger");
    }

    @Test
    void filtersPinnedAndTags() {
        HistoryEntry pinned = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "pinned", Instant.parse("2026-06-15T02:00:00Z"));
        pinned.pinned = true;
        pinned.tags = new LinkedHashSet<>(List.of("Auth"));
        HistoryEntry unpinned = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "unpinned", Instant.parse("2026-06-15T02:01:00Z"));
        unpinned.pinned = false;
        unpinned.tags = new LinkedHashSet<>(List.of("Evidence"));

        List<HistoryEntry> entries = List.of(pinned, unpinned);

        HistoryFilterCriteria pinnedCriteria = new HistoryFilterCriteria();
        pinnedCriteria.pinnedState = "Pinned";
        assertThat(entries.stream().filter(pinnedCriteria::matches)).extracting(entry -> entry.id).containsExactly("pinned");

        HistoryFilterCriteria tagCriteria = new HistoryFilterCriteria();
        tagCriteria.tagText = "evi";
        assertThat(entries.stream().filter(tagCriteria::matches)).extracting(entry -> entry.id).containsExactly("unpinned");
    }

    @Test
    void freeTextSearchIncludesNotesAndTags() {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "search", Instant.parse("2026-06-15T02:00:00Z"));
        entry.analystNotes = "Reviewed by analyst";
        entry.tags = new LinkedHashSet<>(List.of("Evidence"));
        HistoryFilterCriteria notesCriteria = new HistoryFilterCriteria();
        notesCriteria.freeText = "analyst";
        HistoryFilterCriteria tagCriteria = new HistoryFilterCriteria();
        tagCriteria.freeText = "Evidence";

        assertThat(notesCriteria.matches(entry)).isTrue();
        assertThat(tagCriteria.matches(entry)).isTrue();
    }

    @Test
    void clearUnpinnedPreservesPinned() {
        HistoryStore store = new HistoryStore();
        HistoryEntry pinned = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "pinned", Instant.parse("2026-06-15T02:00:00Z"));
        pinned.pinned = true;
        HistoryEntry unpinned = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "unpinned", Instant.parse("2026-06-15T02:01:00Z"));
        store.addAll(List.of(pinned, unpinned));

        int removed = store.clearUnpinned();

        assertThat(removed).isEqualTo(1);
        assertThat(store.snapshot()).extracting(entry -> entry.id).containsExactly("pinned");
    }

    @Test
    void metadataUpdatesUseDefensiveCopies() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "defensive", Instant.parse("2026-06-15T02:00:00Z"));
        store.addEntry(entry);

        HistoryEntry updated = store.updateAnalystMetadata("defensive", "Reviewed", List.of("Auth", "Evidence"));
        updated.tags.add("Mutated");
        updated.analystNotes = "Mutated";

        HistoryEntry reread = store.getById("defensive");
        assertThat(reread.analystNotes).isEqualTo("Reviewed");
        assertThat(reread.tags).containsExactly("Auth", "Evidence");
    }

    @Test
    void oversizedAtomicEvidenceUpdateRejectsAndPreservesOriginal() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = storedEntry(store, "atomic-pin");
        long baseSize = store.getById(entry.id).estimatedStoredBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, baseSize + 32, 1_000_000, 1_000_000, true));

        HistoryEntry before = store.getById(entry.id);
        HistoryAdmissionResult result = store.updateEvidenceMetadataWithResult(
                entry.id,
                true,
                "N".repeat(512),
                List.of(" Auth ", "auth", "Evidence")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(HistoryAdmissionRejectionReason.ENTRY_EXCEEDS_POLICY);
        assertThat(store.getById(entry.id)).usingRecursiveComparison().isEqualTo(before);
        assertThat(store.getRetentionStats().overBudget()).isFalse();
    }

    @Test
    void compatibilityWrapperReturnsNullWhenPinnedMetadataCannotFit() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = storedEntry(store, "atomic-over-budget");
        long baseSize = store.getById(entry.id).estimatedStoredBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, baseSize + 8, 1_000_000, 1_000_000, true));

        HistoryEntry updated = store.updateEvidenceMetadata(entry.id, true, "Reviewed-" + "X".repeat(256), List.of("Evidence"));

        assertThat(updated).isNull();
        assertThat(store.getById(entry.id).pinned).isFalse();
        assertThat(store.getRetentionStats().overBudget()).isFalse();
        assertThat(store.snapshot()).extracting(value -> value.id).containsExactly(entry.id);
    }

    @Test
    void atomicEvidenceUpdateNeverEvictsEditedEntryToClaimSuccess() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = storedEntry(store, "atomic-evict");
        long baseSize = store.getById(entry.id).estimatedStoredBytes();
        store.setRetentionPolicy(new HistoryRetentionPolicy(10, baseSize + 16, 1_000_000, 1_000_000, true));

        HistoryEntry updated = store.updateEvidenceMetadata(entry.id, false, "Z".repeat(512), List.of("Evidence"));

        assertThat(updated).isNull();
        assertThat(store.getById(entry.id)).isNotNull();
        assertThat(store.getById(entry.id).analystNotes).isNotEqualTo("Z".repeat(512));
        assertThat(store.getRetentionStats().entryCount()).isEqualTo(1);
        assertThat(store.getRetentionStats().overBudget()).isFalse();
    }

    @Test
    void atomicEvidenceUpdateNormalizesTags() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = storedEntry(store, "atomic-tags");

        HistoryEntry updated = store.updateEvidenceMetadata(entry.id, false, "Reviewed", List.of(" Auth ", "auth", " Evidence ", "", "evidence"));

        assertThat(updated.tags).containsExactly("Auth", "Evidence");
        assertThat(store.getById(entry.id).tags).containsExactly("Auth", "Evidence");
    }

    @Test
    void atomicEvidenceUpdateReturnsDefensiveCopy() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = storedEntry(store, "atomic-defensive");

        HistoryEntry updated = store.updateEvidenceMetadata(entry.id, true, "Reviewed", List.of("Auth"));
        updated.tags.add("Mutated");
        updated.analystNotes = "Mutated";

        HistoryEntry reread = store.getById(entry.id);
        assertThat(reread.tags).containsExactly("Auth");
        assertThat(reread.analystNotes).isEqualTo("Reviewed");
    }

    @Test
    void legacyPinAndMetadataMethodsRemainCompatible() {
        HistoryStore store = new HistoryStore();
        HistoryEntry entry = storedEntry(store, "legacy-compatible");

        assertThat(store.updateAnalystMetadata(entry.id, "Reviewed", List.of("Auth", "auth"))).isNotNull();
        assertThat(store.setPinned(entry.id, true)).isTrue();

        HistoryEntry reread = store.getById(entry.id);
        assertThat(reread.pinned).isTrue();
        assertThat(reread.analystNotes).isEqualTo("Reviewed");
        assertThat(reread.tags).containsExactly("Auth");
    }

    private static HistoryEntry storedEntry(HistoryStore store, String id) {
        HistoryEntry entry = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                id, Instant.parse("2026-06-15T02:00:00Z"));
        return store.addEntry(entry);
    }
}
