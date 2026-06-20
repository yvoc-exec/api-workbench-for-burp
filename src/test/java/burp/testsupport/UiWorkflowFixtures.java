package burp.testsupport;

import burp.UniversalImporter;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.utils.ScriptMode;
import org.mockito.Mockito;

import javax.swing.JPanel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public final class UiWorkflowFixtures {
    private UiWorkflowFixtures() {
    }

    public static UniversalImporter newImporter(MontoyaApi api) {
        return new UniversalImporter(api, ScriptMode.DISABLED, null);
    }

    public static MontoyaApi mockUiApi(Function<HttpRequest, HttpRequestResponse> responseFactory) {
        AtomicInteger fallbackSendCount = new AtomicInteger();
        MontoyaApi api = Mockito.mock(MontoyaApi.class, Mockito.RETURNS_DEEP_STUBS);
        when(api.userInterface().createHttpRequestEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpRequestEditor editor = Mockito.mock(HttpRequestEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        when(api.userInterface().createHttpResponseEditor(any(EditorOptions.class))).thenAnswer(invocation -> {
            HttpResponseEditor editor = Mockito.mock(HttpResponseEditor.class);
            when(editor.uiComponent()).thenReturn(new JPanel());
            return editor;
        });
        when(api.http().sendRequest(any(HttpRequest.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpRequestResponse response = responseFactory != null ? responseFactory.apply(request) : null;
            return response != null
                    ? response
                    : RunnerScriptTestFixtures.mockResponse(200, "OK-" + fallbackSendCount.incrementAndGet(), "text/plain");
        });
        when(api.http().sendRequest(any(HttpRequest.class), any(RequestOptions.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpRequestResponse response = responseFactory != null ? responseFactory.apply(request) : null;
            return response != null
                    ? response
                    : RunnerScriptTestFixtures.mockResponse(200, "OK-" + fallbackSendCount.incrementAndGet(), "text/plain");
        });
        return api;
    }
}
