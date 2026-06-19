package burp.scripts;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.models.RunnerResult;
import burp.utils.ScriptMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptDialectContractTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialectContracts")
    void dialectsSupportPreRequestMutationAndPostResponseExtraction(String label,
                                                                    ScriptDialect dialect,
                                                                    String preScript,
                                                                    String postScript,
                                                                    String expectedUrlMarker,
                                                                    String expectedBodyMarker) {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = requestWithScripts(dialect, preScript, postScript);
        EnvironmentProfile activeEnvironment = new EnvironmentProfile();
        activeEnvironment.name = "Dev";

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);

        ScriptExecutionResult preResult = runtime.executePreRequest(collection, request, activeEnvironment, "Send", 1);

        assertThat(preResult.success).as(label).isTrue();
        assertThat(preResult.mutatedRequest).isNotNull();
        assertThat(preResult.mutatedRequest.method).isEqualTo("PUT");
        assertThat(preResult.mutatedRequest.url).contains(expectedUrlMarker);
        assertThat(preResult.mutatedRequest.body.raw).contains(expectedBodyMarker);
        assertThat(preResult.mutatedRequest.headers).anySatisfy(header -> {
            assertThat(header.key).isEqualToIgnoringCase("X-Dialect");
            assertThat(header.value).isEqualTo(label);
        });
        assertThat(activeEnvironment.variables).containsEntry("token", "pre-token");
        assertThat(preResult.logs).anySatisfy(log -> assertThat(log.message).contains("pre-" + label));

        ScriptExecutionResult postResult = runtime.executePostResponse(
                collection,
                preResult.mutatedRequest,
                activeEnvironment,
                "Runner",
                1,
                "{\"token\":\"resp-123\"}",
                201,
                Map.of("content-type", List.of("application/json")),
                7L,
                new RunnerResult()
        );

        assertThat(postResult.success).as(label).isTrue();
        assertThat(postResult.assertions).hasSize(1);
        assertThat(postResult.assertions.get(0).passed).isTrue();
        assertThat(activeEnvironment.variables).containsEntry("response_token", "resp-123");
        assertThat(postResult.logs).anySatisfy(log -> assertThat(log.message).contains("post-" + label));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleSendWarnings")
    void singleSendModeEmitsVisibleWarningsInsteadOfApplyingRunnerOnlyFlowControl(String label,
                                                                                  ScriptDialect dialect,
                                                                                  String scriptSource) {
        ApiCollection collection = new ApiCollection();
        collection.name = "APIM";
        collection.format = "api-workbench";

        ApiRequest request = new ApiRequest();
        request.id = "req-warning";
        request.name = "Warning";
        request.method = "GET";
        request.url = "https://api.example.test/items";
        request.scriptBlocks = new ArrayList<>(List.of(scriptBlock("warn", dialect, ScriptPhase.PRE_REQUEST, scriptSource)));

        UnifiedScriptRuntime runtime = new UnifiedScriptRuntime(null, ScriptMode.FULL_JS);
        ScriptExecutionResult result = runtime.executePreRequest(collection, request, null, "Send", 1);

        assertThat(result.success).as(label).isTrue();
        assertThat(result.flowControl).isEqualTo(ScriptFlowControl.CONTINUE);
        assertThat(result.warnings).isNotEmpty();
        assertThat(result.warnings.get(0)).contains("ignored in single Send mode");
    }

    private static Stream<Arguments> dialectContracts() {
        return Stream.of(
                Arguments.of(
                        "postman",
                        ScriptDialect.POSTMAN,
                        """
                                pm.environment.set('token', 'pre-token');
                                pm.request.method = 'PUT';
                                pm.request.headers.upsert('X-Dialect', 'postman');
                                pm.request.url = pm.request.url + '?via=postman';
                                pm.request.body.raw = '{"postman":true}';
                                console.log('pre-postman');
                                """,
                        """
                                pm.test('status', function () { pm.expect(pm.response.code).to.equal(201); });
                                pm.environment.set('response_token', pm.response.json().get('token'));
                                console.log('post-postman');
                                """,
                        "?via=postman",
                        "\"postman\":true"
                ),
                Arguments.of(
                        "bruno",
                        ScriptDialect.BRUNO,
                        """
                                bru.setEnvVar('token', 'pre-token', { persist: true });
                                req.method = 'PUT';
                                req.headers.upsert('X-Dialect', 'bruno');
                                req.url = req.url + '?via=bruno';
                                req.body.raw = '{"bruno":true}';
                                console.log('pre-bruno');
                                """,
                        """
                                bru.test('status', function () { bru.expect(res.status).to.equal('201'); });
                                bru.setEnvVar('response_token', res.json().get('token'), { persist: true });
                                console.log('post-bruno');
                                """,
                        "?via=bruno",
                        "\"bruno\":true"
                ),
                Arguments.of(
                        "insomnia",
                        ScriptDialect.INSOMNIA,
                        """
                                insomnia.environment.set('token', 'pre-token');
                                insomnia.request.method = 'PUT';
                                insomnia.request.headers.upsert('X-Dialect', 'insomnia');
                                insomnia.request.url = insomnia.request.url + '?via=insomnia';
                                insomnia.request.body.raw = '{"insomnia":true}';
                                console.log('pre-insomnia');
                                """,
                        """
                                insomnia.test('status', function () { insomnia.expect(insomnia.response.code).to.equal(201); });
                                insomnia.environment.set('response_token', insomnia.response.json().get('token'));
                                console.log('post-insomnia');
                                """,
                        "?via=insomnia",
                        "\"insomnia\":true"
                ),
                Arguments.of(
                        "native",
                        ScriptDialect.API_WORKBENCH,
                        """
                                awb.environment.set('token', 'pre-token');
                                awb.request.method = 'PUT';
                                awb.request.headers.upsert('X-Dialect', 'native');
                                awb.request.url = awb.request.url + '?via=native';
                                awb.request.body.raw = '{"native":true}';
                                console.log('pre-native');
                                """,
                        """
                                awb.test('status', function () { awb.expect(awb.response.code).to.equal(201); });
                                awb.environment.set('response_token', awb.response.json().get('token'));
                                console.log('post-native');
                                """,
                        "?via=native",
                        "\"native\":true"
                )
        );
    }

    private static Stream<Arguments> singleSendWarnings() {
        return Stream.of(
                Arguments.of("postman-skip", ScriptDialect.POSTMAN, "pm.execution.skipRequest();"),
                Arguments.of("bruno-stop", ScriptDialect.BRUNO, "bru.stopExecution();"),
                Arguments.of("native-next", ScriptDialect.API_WORKBENCH, "awb.execution.setNextRequest('Later');")
        );
    }

    private static ApiRequest requestWithScripts(ScriptDialect dialect, String preScript, String postScript) {
        ApiRequest request = new ApiRequest();
        request.id = "req-contract";
        request.name = "Contract";
        request.method = "POST";
        request.url = "https://api.example.test/items";
        request.headers = new ArrayList<>();
        request.body = new ApiRequest.Body();
        request.body.mode = "raw";
        request.body.raw = "{\"initial\":true}";
        request.body.contentType = "application/json";
        request.scriptBlocks = new ArrayList<>();
        request.scriptBlocks.add(scriptBlock("pre", dialect, ScriptPhase.PRE_REQUEST, preScript));
        request.scriptBlocks.add(scriptBlock("post", dialect, ScriptPhase.POST_RESPONSE, postScript));
        return request;
    }

    private static ScriptBlock scriptBlock(String id, ScriptDialect dialect, ScriptPhase phase, String source) {
        ScriptBlock block = new ScriptBlock();
        block.id = id;
        block.dialect = dialect;
        block.phase = phase;
        block.scope = ScriptScope.REQUEST;
        block.source = source;
        block.enabled = true;
        return block;
    }
}
