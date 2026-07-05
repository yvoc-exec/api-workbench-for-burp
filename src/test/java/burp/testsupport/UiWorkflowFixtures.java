package burp.testsupport;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.utils.ScriptMode;

import java.util.function.Function;

public final class UiWorkflowFixtures {
    private UiWorkflowFixtures() {
    }

    public static UniversalImporter newImporter(MontoyaApi api) {
        UniversalImporter importer = new UniversalImporter(api, ScriptMode.DISABLED, null);
        return UiBridgeInstaller.install(importer);
    }

    public static MontoyaApi mockUiApi(
            Function<HttpRequest, HttpRequestResponse> responseFactory) {
        return MockUiApiFactory.create(responseFactory);
    }
}
