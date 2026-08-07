package burp.models;

import burp.history.HistoryEntry;
import burp.history.HistoryRequestSnapshot;
import burp.history.HistoryResponseSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceStateCopyTest {

    @Test
    void detachmentDoesNotNormalizeOrMutateSourceDefaults() {
        WorkspaceState source = new WorkspaceState();
        ApiCollection collection = new ApiCollection();
        collection.id = null;
        collection.requests = null;
        collection.folderVars = null;
        collection.auth = new ApiRequest.Auth();
        collection.auth.properties = null;
        source.collections = new ArrayList<>(List.of(collection));
        EnvironmentProfile environment = new EnvironmentProfile();
        environment.variables = null;
        environment.runtimeVariables = null;
        environment.oauth2 = null;
        source.environments = new ArrayList<>(List.of(environment));

        WorkspaceState copy = WorkspaceState.copyOf(source);

        assertThat(collection.id).isNull();
        assertThat(collection.requests).isNull();
        assertThat(collection.folderVars).isNull();
        assertThat(collection.auth.properties).isNull();
        assertThat(environment.variables).isNull();
        assertThat(environment.runtimeVariables).isNull();
        assertThat(environment.oauth2).isNull();
        assertThat(copy.collections.get(0).requests).isEmpty();
        assertThat(copy.environments.get(0).variables).isEmpty();
    }

    @Test
    void exactHistoryEnvironmentOAuthAndRedirectStateAreDetachedWithoutAliases() {
        byte[] exactBytes = "exact".getBytes(StandardCharsets.UTF_8);
        byte[] authoredBytes = "authored".getBytes(StandardCharsets.UTF_8);
        byte[] rawBytes = "raw".getBytes(StandardCharsets.UTF_8);
        byte[] responseBytes = "response".getBytes(StandardCharsets.UTF_8);

        ApiRequest request = new ApiRequest();
        request.exactHttpRequest = new ExactHttpRequestSnapshot();
        request.exactHttpRequest.rawRequestBytes = exactBytes;
        ApiCollection collection = new ApiCollection();
        collection.requests.add(request);
        collection.runtimeVars.put("token", "secret");

        HistoryEntry history = new HistoryEntry();
        history.requestSnapshot = new HistoryRequestSnapshot();
        history.requestSnapshot.bodyAsAuthored = authoredBytes;
        history.requestSnapshot.rawRequestSent = rawBytes;
        history.responseSnapshot = new HistoryResponseSnapshot();
        history.responseSnapshot.body = responseBytes;

        EnvironmentProfile environment = new EnvironmentProfile();
        environment.variables = new LinkedHashMap<>(java.util.Map.of("base", "one"));
        environment.oauth2.config.put("clientSecret", "secret");

        WorkspaceState source = new WorkspaceState();
        source.collections.add(collection);
        source.historyEntries.add(history);
        source.environments.add(environment);
        RedirectPolicy redirectPolicy = new RedirectPolicy();
        redirectPolicy.additionalSensitiveHeaderNames.add("X-Secret");
        source.redirectPolicy = redirectPolicy;

        WorkspaceState copy = WorkspaceState.copyOf(source);

        assertThat(copy.collections.get(0).requests.get(0).exactHttpRequest.rawRequestBytes)
                .isEqualTo(exactBytes).isNotSameAs(exactBytes);
        assertThat(copy.historyEntries.get(0).requestSnapshot.bodyAsAuthored)
                .isEqualTo(authoredBytes).isNotSameAs(authoredBytes);
        assertThat(copy.historyEntries.get(0).requestSnapshot.rawRequestSent)
                .isEqualTo(rawBytes).isNotSameAs(rawBytes);
        assertThat(copy.historyEntries.get(0).responseSnapshot.body)
                .isEqualTo(responseBytes).isNotSameAs(responseBytes);
        assertThat(copy.environments.get(0).variables).isNotSameAs(environment.variables);
        assertThat(copy.environments.get(0).oauth2).isNotSameAs(environment.oauth2);
        assertThat(copy.redirectPolicy).isNotSameAs(source.redirectPolicy);

        copy.collections.get(0).runtimeVars.put("token", "changed");
        copy.environments.get(0).oauth2.config.put("clientSecret", "changed");
        copy.redirectPolicy.additionalSensitiveHeaderNames.add("X-Other");
        assertThat(collection.runtimeVars).containsEntry("token", "secret");
        assertThat(environment.oauth2.config).containsEntry("clientSecret", "secret");
        assertThat(source.redirectPolicy.additionalSensitiveHeaderNames).containsExactly("X-Secret");
    }
}
