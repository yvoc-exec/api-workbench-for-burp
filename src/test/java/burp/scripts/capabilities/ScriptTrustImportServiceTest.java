package burp.scripts.capabilities;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptTrustImportServiceTest {
    private final ScriptTrustImportService service = new ScriptTrustImportService();

    @Test
    void fileImportDisablesEveryScriptBeforeReview() {
        Fixture fixture = fixture();

        ScriptTrustReviewModel model = service.prepare(
                List.of(fixture.collection),
                ScriptImportOrigin.FILE_IMPORT);

        assertThat(model.totalScriptCount()).isEqualTo(3);
        assertThat(model.highestRisk()).isEqualTo(ScriptRiskLevel.CRITICAL);
        assertThat(model.unsupportedCount()).isEqualTo(1);
        assertThat(fixture.collectionBlock.enabled).isFalse();
        assertThat(fixture.folderBlock.enabled).isFalse();
        assertThat(fixture.requestBlock.enabled).isFalse();
        assertThat(fixture.collectionBlock.metadata).containsEntry("trustState", "disabled");
        assertThat(fixture.folderBlock.metadata).containsEntry("trustState", "disabled");
        assertThat(fixture.requestBlock.metadata).containsEntry("trustState", "disabled");
    }

    @Test
    void trustSelectedEnablesOnlySelectedSupportedScripts() {
        Fixture fixture = fixture();
        ScriptTrustReviewModel model = service.prepare(
                List.of(fixture.collection),
                ScriptImportOrigin.FILE_IMPORT);
        model.setSelectedForTrust(fixture.collectionBlock.id, true);
        model.setSelectedForTrust(fixture.requestBlock.id, true);
        model.setDecision(ScriptTrustReviewModel.Decision.TRUST_SELECTED);

        boolean accepted = service.applyDecision(List.of(fixture.collection), model);

        assertThat(accepted).isTrue();
        assertThat(fixture.collectionBlock.enabled).isTrue();
        assertThat(fixture.collectionBlock.metadata).containsEntry("trustState", "trusted");
        assertThat(fixture.folderBlock.enabled).isFalse();
        assertThat(fixture.requestBlock.enabled).isFalse();
        assertThat(fixture.requestBlock.metadata).containsEntry("trustState", "disabled");
    }

    @Test
    void trustAllStillRefusesUnsupportedCapabilities() {
        Fixture fixture = fixture();
        ScriptTrustReviewModel model = service.prepare(
                List.of(fixture.collection),
                ScriptImportOrigin.FILE_IMPORT);
        model.setDecision(ScriptTrustReviewModel.Decision.TRUST_ALL);

        service.applyDecision(List.of(fixture.collection), model);

        assertThat(fixture.collectionBlock.enabled).isTrue();
        assertThat(fixture.folderBlock.enabled).isTrue();
        assertThat(fixture.requestBlock.enabled).isFalse();
    }

    @Test
    void pairedPreAndPostRequestScriptsRemainSeparatelyTrusted() {
        ApiCollection collection = new ApiCollection();
        collection.name = "Paired Scripts";
        ApiRequest request = new ApiRequest();
        request.id = "request-id";
        request.name = "Paired Request";
        ScriptBlock pre = block("pm.request.headers.add({key:'X-Pre', value:'1'});", ScriptScope.REQUEST, "pre-script");
        pre.phase = ScriptPhase.PRE_REQUEST;
        ScriptBlock post = block("pm.environment.set('seen', 'true');", ScriptScope.REQUEST, "post-script");
        post.phase = ScriptPhase.POST_RESPONSE;
        request.scriptBlocks.add(pre);
        request.scriptBlocks.add(post);
        collection.requests.add(request);

        ScriptTrustReviewModel model = service.prepare(List.of(collection), ScriptImportOrigin.FILE_IMPORT);

        assertThat(model.items()).hasSize(2);
        assertThat(model.items()).extracting(item -> item.phase)
                .containsExactlyInAnyOrder(ScriptPhase.PRE_REQUEST, ScriptPhase.POST_RESPONSE);
        model.setSelectedForTrust(pre.id, true);
        model.setDecision(ScriptTrustReviewModel.Decision.TRUST_SELECTED);

        boolean accepted = service.applyDecision(List.of(collection), model);

        assertThat(accepted).isTrue();
        assertThat(pre.enabled).isTrue();
        assertThat(post.enabled).isFalse();
        assertThat(pre.metadata).containsEntry("trustState", "trusted");
        assertThat(post.metadata).containsEntry("trustState", "disabled");
    }

    @Test
    void workspaceRestorePreservesPersistedEnabledStateWithoutPromptItems() {
        Fixture fixture = fixture();
        fixture.collectionBlock.enabled = true;
        fixture.folderBlock.enabled = false;
        fixture.requestBlock.enabled = true;

        ScriptTrustReviewModel model = service.prepare(
                List.of(fixture.collection),
                ScriptImportOrigin.WORKSPACE_RESTORE);

        assertThat(model.totalScriptCount()).isZero();
        assertThat(fixture.collectionBlock.enabled).isTrue();
        assertThat(fixture.folderBlock.enabled).isFalse();
        assertThat(fixture.requestBlock.enabled).isTrue();
        assertThat(fixture.collectionBlock.metadata).containsEntry("trustState", "native");
    }

    private static Fixture fixture() {
        ApiCollection collection = new ApiCollection();
        collection.id = "collection-id";
        collection.name = "Imported Collection";
        collection.folderPaths.add("Admin");

        ScriptBlock collectionBlock = block(
                "pm.environment.set('tenant', 'blue');",
                ScriptScope.COLLECTION,
                "collection-script");
        ScriptBlock folderBlock = block(
                "pm.request.url = 'https://example.test/admin';",
                ScriptScope.FOLDER,
                "folder-script");
        ScriptBlock requestBlock = block(
                "pm.sendRequest('https://example.test/side-effect');",
                ScriptScope.REQUEST,
                "request-script");

        collection.scriptBlocks.add(collectionBlock);
        collection.folderScriptBlocks.put("Admin", List.of(folderBlock));
        ApiRequest request = new ApiRequest();
        request.id = "request-id";
        request.name = "Admin Request";
        request.path = "Admin";
        request.scriptBlocks.add(requestBlock);
        collection.requests.add(request);
        return new Fixture(collection, collectionBlock, folderBlock, requestBlock);
    }

    private static ScriptBlock block(String source, ScriptScope scope, String id) {
        ScriptBlock block = ScriptBlock.of(
                source,
                ScriptDialect.POSTMAN,
                ScriptPhase.PRE_REQUEST,
                scope);
        block.id = id;
        block.sourceFormat = "postman";
        block.sourcePath = "fixture.json";
        return block;
    }

    private record Fixture(ApiCollection collection,
                           ScriptBlock collectionBlock,
                           ScriptBlock folderBlock,
                           ScriptBlock requestBlock) {
    }
}
