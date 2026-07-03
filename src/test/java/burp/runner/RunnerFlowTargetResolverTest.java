package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerFlowTargetResolverTest {

    private final RunnerFlowTargetResolver resolver = new RunnerFlowTargetResolver();

    @Test
    void exactIdWins() {
        ApiCollection collection = collection("Collection",
                request("target-id", "Target", "folder-a"),
                request("other-id", "Other", "folder-b"));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "other-id", null, "target-id", null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.RESOLVED);
        assertThat(resolution.request.id).isEqualTo("target-id");
    }

    @Test
    void collectionAndRequestIdResolve() {
        ApiCollection collection = collection("Collection", request("request-id", "Target", ""));
        collection.id = "collection-id";

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "wrong", "collection-id", "request-id", null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.RESOLVED);
        assertThat(resolution.request.id).isEqualTo("request-id");
    }

    @Test
    void collectionAndRequestIdDisambiguateDuplicateRequestIds() {
        ApiRequest firstRequest = request("duplicate-id", "First", "folder-a");
        ApiRequest secondRequest = request("duplicate-id", "Second", "folder-b");

        ApiCollection first = collection("First Collection", firstRequest);
        first.id = "first-collection-id";

        ApiCollection second = collection("Second Collection", secondRequest);
        second.id = "second-collection-id";

        FlowTargetResolution resolution = resolver.resolve(
                List.of(first, second),
                List.of(firstRequest, secondRequest),
                "duplicate-id",
                "second-collection-id",
                "duplicate-id",
                null,
                null,
                null);

        assertThat(resolution.status)
                .isEqualTo(
                        FlowTargetResolutionStatus.RESOLVED);
        assertThat(resolution.form)
                .isEqualTo(
                        FlowTargetResolutionForm
                                .COLLECTION_AND_REQUEST_ID);
        assertThat(resolution.request)
                .isSameAs(secondRequest);
        assertThat(resolution.collection)
                .isSameAs(second);
    }

    @Test
    void qualifiedPathResolvesCorrectDuplicate() {
        ApiCollection collection = collection("Collection",
                request("dup-1", "Duplicate", "folder-a"),
                request("dup-2", "Duplicate", "folder-b"));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "Collection/folder-b/Duplicate", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.RESOLVED);
        assertThat(resolution.request.id).isEqualTo("dup-2");
    }

    @Test
    void collectionFolderAndNameResolve() {
        ApiCollection collection = collection("Collection", request("request-id", "Target", "folder-a"));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, null, null, null, "Collection", "folder-a", "Target");

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.RESOLVED);
        assertThat(resolution.request.id).isEqualTo("request-id");
    }

    @Test
    void duplicateNameReturnsAmbiguous() {
        ApiCollection collection = collection("Collection",
                request("a", "Duplicate", "folder-a"),
                request("b", "Duplicate", "folder-b"));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "Duplicate", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.AMBIGUOUS);
        assertThat(resolution.candidateQualifiedPaths).containsExactly(
                "Collection/folder-a/Duplicate",
                "Collection/folder-b/Duplicate");
    }

    @Test
    void duplicateRequestIdsReturnAmbiguous() {
        ApiCollection collection = collection("Collection",
                request("dup", "One", "folder-a"),
                request("dup", "Two", "folder-b"));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "dup", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.AMBIGUOUS);
        assertThat(resolution.candidateRequestIds).containsExactly("dup");
        assertThat(resolution.candidateQualifiedPaths).containsExactly(
                "Collection/folder-a/One",
                "Collection/folder-b/Two");
    }

    @Test
    void caseInsensitiveFallbackRequiresUniqueness() {
        ApiCollection collection = collection("Collection",
                request("one", "Alpha", ""),
                request("two", "alpha", ""));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "ALPHA", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.AMBIGUOUS);
        assertThat(resolution.candidateQualifiedPaths).containsExactly(
                "Collection/Alpha",
                "Collection/alpha");
    }

    @Test
    void disabledTargetReturnsDisabled() {
        ApiRequest disabled = request("disabled-id", "Disabled", "");
        disabled.disabled = true;
        ApiCollection collection = collection("Collection", disabled);

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "disabled-id", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.DISABLED);
        assertThat(resolution.request.id).isEqualTo("disabled-id");
    }

    @Test
    void requestLabelContainingSlashStillResolvesByExactQualifiedPath() {
        ApiCollection collection = collection("Collection", request("slash-id", "Leaf/Child", ""));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "Collection/Leaf/Child", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.RESOLVED);
        assertThat(resolution.request.id).isEqualTo("slash-id");
    }

    @Test
    void notFoundReturnsNotFound() {
        ApiCollection collection = collection("Collection", request("one", "One", ""));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "missing", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.NOT_FOUND);
        assertThat(resolution.safeMessage).contains("missing");
    }

    @Test
    void candidateOrderingIsDeterministic() {
        ApiCollection collection = collection("Collection",
                request("z", "Duplicate", "z"),
                request("a", "Duplicate", "a"),
                request("m", "Duplicate", "m"));

        FlowTargetResolution resolution = resolver.resolve(List.of(collection), collection.requests, "Duplicate", null, null, null, null, null);

        assertThat(resolution.status).isEqualTo(FlowTargetResolutionStatus.AMBIGUOUS);
        assertThat(resolution.candidateRequestIds).containsExactly("a", "m", "z");
        assertThat(resolution.candidateQualifiedPaths).containsExactly(
                "Collection/a/Duplicate",
                "Collection/m/Duplicate",
                "Collection/z/Duplicate");
    }

    private static ApiCollection collection(String name, ApiRequest... requests) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        collection.id = name.toLowerCase();
        collection.requests = new ArrayList<>();
        collection.folderPaths = new ArrayList<>();
        if (requests != null) {
            for (ApiRequest request : requests) {
                collection.requests.add(request);
                if (request != null && request.path != null && !request.path.isBlank()) {
                    collection.folderPaths.add(request.path);
                }
            }
        }
        return collection;
    }

    private static ApiRequest request(String id, String name, String path) {
        ApiRequest request = new ApiRequest();
        request.id = id;
        request.name = name;
        request.path = path;
        request.sourceCollection = "Collection";
        request.method = "GET";
        request.url = "https://example.test/" + id;
        return request;
    }
}
