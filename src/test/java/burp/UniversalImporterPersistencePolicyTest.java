package burp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalImporterPersistencePolicyTest {

    @Test
    void detectsTemporaryProjectNamesAsMemoryOnly() {
        assertThat(UniversalImporter.isDiskBackedProjectName("Temporary project")).isFalse();
        assertThat(UniversalImporter.isDiskBackedProjectName("Temporary project in memory")).isFalse();
        assertThat(UniversalImporter.isDiskBackedProjectName("  ")).isFalse();
    }

    @Test
    void treatsNamedProjectsAsDiskBacked() {
        assertThat(UniversalImporter.isDiskBackedProjectName("Customer Red Team")).isTrue();
        assertThat(UniversalImporter.isDiskBackedProjectName("Old Project")).isTrue();
    }

    @Test
    void promptsOnFirstSaveForAnyDiskBackedProjectWithoutSavedPolicy() {
        assertThat(UniversalImporter.shouldPromptForSensitivePersistence(null, true)).isTrue();
        assertThat(UniversalImporter.shouldPromptForSensitivePersistence(Boolean.FALSE, true)).isFalse();
        assertThat(UniversalImporter.shouldPromptForSensitivePersistence(Boolean.TRUE, true)).isFalse();
        assertThat(UniversalImporter.shouldPromptForSensitivePersistence(null, false)).isFalse();
    }
}
