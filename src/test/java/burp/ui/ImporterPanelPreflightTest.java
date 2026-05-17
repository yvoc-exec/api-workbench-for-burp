package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.UnresolvedVariableIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelPreflightTest {

    @Test
    void collectUnresolvedVariableIssuesUsesMatchingCollectionContextAndSourceFallback() {
        ApiCollection alpha = new ApiCollection();
        alpha.name = "Alpha";
        ApiRequest alphaRequest = new ApiRequest();
        alphaRequest.name = "Alpha Request";
        alphaRequest.sourceCollection = "Alpha";
        alphaRequest.url = "{{alphaHost}}/items";
        alpha.requests.add(alphaRequest);

        ApiRequest betaRequest = new ApiRequest();
        betaRequest.name = "Beta Request";
        betaRequest.sourceCollection = "Beta";
        betaRequest.url = "{{betaHost}}/items";

        List<UnresolvedVariableIssue> issues = ImporterPanel.collectUnresolvedVariableIssues(
                List.of(alpha),
                List.of(alphaRequest, betaRequest)
        );

        assertThat(issues)
                .extracting(issue -> issue.collectionName + ":" + issue.requestName + ":" + issue.variableName + ":" + issue.location)
                .containsExactlyInAnyOrder(
                        "Alpha:Alpha Request:alphaHost:url",
                        "Beta:Beta Request:betaHost:url"
                );
    }
}
