package burp.history;

import burp.testsupport.HistoryTestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryFiltersTest {

    @Test
    void fieldFiltersMatchRunnerAttemptMetadata() {
        HistoryEntry runner = HistoryTestFixtures.sampleRunnerEntry();
        HistoryFilterCriteria criteria = new HistoryFilterCriteria();
        criteria.source = HistorySource.RUNNER;
        criteria.method = "POST";
        criteria.statusClass = "5xx";
        criteria.exactStatusCode = 500;
        criteria.collection = HistoryTestFixtures.COLLECTION_NAME;
        criteria.folder = HistoryTestFixtures.REQUEST_FOLDER;
        criteria.requestName = HistoryTestFixtures.REQUEST_NAME;
        criteria.environment = HistoryTestFixtures.ENVIRONMENT_NAME;
        criteria.resultType = HistoryResult.ASSERTION_FAILURE;
        criteria.hasResponseBody = Boolean.TRUE;
        criteria.hasError = Boolean.TRUE;
        criteria.hasAssertionFailure = Boolean.TRUE;
        criteria.attemptNumber = 2;
        criteria.totalAttempts = 3;
        criteria.retriesOnly = Boolean.TRUE;

        assertThat(criteria.matches(runner)).isTrue();
    }

    @Test
    void freeTextSearchMatchesCoreFieldsAndAttemptInformation() {
        HistoryEntry entry = HistoryTestFixtures.sampleWorkbenchEntry();
        HistoryFilterCriteria criteria = new HistoryFilterCriteria();
        criteria.freeText = "1/1";
        assertThat(criteria.matches(entry)).isTrue();

        criteria.freeText = "history-workbench";
        assertThat(criteria.matches(entry)).isTrue();

        criteria.freeText = "missing";
        assertThat(criteria.matches(entry)).isTrue();
    }
}
