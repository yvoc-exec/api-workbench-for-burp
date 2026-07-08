package burp.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImportResultTest {
    @Test
    void defaultResultKeepsMutableFailureCollections() {
        ImportResult result = new ImportResult();

        assertNotNull(result.failedRequests);
        assertNotNull(result.failedRequestDetails);
        assertFalse(result.hasFailures());

        result.recordFailure("Get User", "Users/Get User", "boom", "request-data");

        assertTrue(result.hasFailures());
        assertEquals(1, result.failureCount());
        assertEquals("Get User: boom", result.failedRequests.get(0));
        assertEquals("Get User", result.failedRequestDetails.get(0).name);
        assertEquals("Users/Get User", result.failedRequestDetails.get(0).path);
        assertEquals("boom", result.failedRequestDetails.get(0).errorMessage);
        assertEquals("request-data", result.failedRequestDetails.get(0).requestData);
    }

    @Test
    void successAndFatalErrorHelpersReflectPublicFields() {
        ImportResult result = new ImportResult("Collection", 3);

        result.recordSuccess();
        result.error = "fatal";

        assertEquals("Collection", result.collectionName);
        assertEquals(3, result.totalRequests);
        assertEquals(1, result.successCount);
        assertTrue(result.hasFatalError());
    }
}
