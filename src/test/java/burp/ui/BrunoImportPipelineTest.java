package burp.ui;

import burp.api.montoya.MontoyaApi;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.BrunoParser;
import burp.scripts.ScriptDialect;
import burp.testsupport.RunnerScriptTestFixtures;
import burp.utils.ExecutionResult;
import burp.utils.RequestBuilder;
import burp.utils.ScriptMode;
import burp.utils.SharedRequestPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoImportPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void brunoImportLoadsIntoRequestEditorAndSharedPipelineKeepsBrunoDialectSource() throws Exception {
        Path bru = tempDir.resolve("Pipeline.bru");
        Files.writeString(bru, """
                meta {
                  name: Pipeline
                  type: http
                  seq: 1
                }

                post {
                  url: https://api.example.test/pipeline
                }

                headers {
                  X-Flow: {{flow}}
                }

                body:text {
                  payload-{{flow}}
                }

                script:pre-request {
                  bru.setVar('flow', 'from-script');
                }
                """, StandardCharsets.UTF_8);

        ApiCollection collection = new BrunoParser().parse(bru.toFile());
        ApiRequest imported = collection.requests.get(0);

        assertThat(imported.scriptBlocks).singleElement().satisfies(block -> {
            assertThat(block.dialect).isEqualTo(ScriptDialect.BRUNO);
            assertThat(block.source).contains("bru.setVar('flow', 'from-script');");
        });

        RequestEditorPanel editor = new RequestEditorPanel();
        editor.setRequestBuilder(new RequestBuilder(null));
        editor.setCurrentCollection(collection);
        AtomicReference<ApiRequest> builtRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            editor.loadRequest(imported);
            editor.commitAllEdits();
            builtRef.set(editor.buildRequestFromUI());
        });
        ApiRequest built = builtRef.get();

        assertThat(built.scriptBlocks).singleElement().satisfies(block -> {
            assertThat(block.dialect).isEqualTo(ScriptDialect.BRUNO);
            assertThat(block.source).contains("bru.setVar('flow', 'from-script');");
        });

        AtomicInteger sendCount = new AtomicInteger();
        CopyOnWriteArrayList<burp.api.montoya.http.message.requests.HttpRequest> captured = new CopyOnWriteArrayList<>();
        MontoyaApi api = RunnerScriptTestFixtures.mockRunnerApi(
                sendCount,
                captured,
                () -> RunnerScriptTestFixtures.mockResponse(200, "{\"ok\":true}", "application/json")
        );
        SharedRequestPipeline pipeline = new SharedRequestPipeline(api, new RequestBuilder(null), new burp.utils.ScriptEngine(null, ScriptMode.FULL_JS), null);

        ExecutionResult result = pipeline.execute(built, collection, true);

        assertThat(sendCount).hasValue(1);
        assertThat(result.success).isTrue();
        assertThat(result.rawRequestText).contains("X-Flow: from-script");
        assertThat(result.rawRequestText).contains("Content-Type: text/plain");
        assertThat(result.rawRequestText).contains("payload-from-script");
    }
}
