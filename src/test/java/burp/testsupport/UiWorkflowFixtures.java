package burp.testsupport;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.auth.OAuth2Manager;
import burp.utils.RequestBuilder;
import burp.utils.ScriptEngine;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.mockito.Mockito;

import java.util.function.Function;

public final class UiWorkflowFixtures {
    private UiWorkflowFixtures() {
    }

    public static UniversalImporter newImporter(MontoyaApi api) {
        UniversalImporter importer = new UniversalImporter(api, ScriptMode.DISABLED, null) {
            @Override
            protected SharedRequestPipeline createSharedRequestPipeline(MontoyaApi pipelineApi,
                                                                        RequestBuilder requestBuilder,
                                                                        ScriptEngine scriptEngine,
                                                                        OAuth2Manager oauth2Manager) {
                return SharedRequestPipeline.withRequestOptionsFactory(
                        pipelineApi,
                        requestBuilder,
                        scriptEngine,
                        oauth2Manager,
                        null,
                        timeout -> {
                            RequestOptions options = Mockito.mock(RequestOptions.class);
                            Mockito.when(options.withRedirectionMode(Mockito.any())).thenReturn(options);
                            Mockito.when(options.withResponseTimeout(Mockito.anyInt())).thenReturn(options);
                            return options;
                        });
            }
        };
        return UiBridgeInstaller.install(importer);
    }

    public static MontoyaApi mockUiApi(
            Function<HttpRequest, HttpRequestResponse> responseFactory) {
        return MockUiApiFactory.create(responseFactory);
    }
}
