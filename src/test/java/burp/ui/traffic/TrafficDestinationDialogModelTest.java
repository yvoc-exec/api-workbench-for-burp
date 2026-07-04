package burp.ui.traffic;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficDestinationDialogModelTest {
    @Test
    void generatesDeterministicSiblingSafeNames() {
        ApiCollection existing = collectionWithRequest("GET users");
        ApiRequest first = request("GET users");
        ApiRequest second = request("GET users");

        TrafficDestinationDialogModel model = new TrafficDestinationDialogModel(
                List.of(existing),
                List.of(first, second),
                false,
                false);

        assertThat(model.createNewCollection()).isFalse();
        assertThat(model.generatedNames()).containsExactly("GET users (2)", "GET users (3)");
        assertThat(model.isValid()).isTrue();
    }

    @Test
    void binaryRequestForcesExactTransportPreservation() {
        ApiRequest binary = request("Upload");
        binary.exactHttpRequest = new ExactHttpRequestSnapshot();
        binary.exactHttpRequest.rawRequestBytes = new byte[]{0, (byte) 0xff};
        binary.exactHttpRequest.binaryBody = true;
        binary.exactHttpRequest.pristine = true;

        TrafficDestinationDialogModel model = new TrafficDestinationDialogModel(
                List.of(collectionWithRequest("Existing")),
                List.of(binary),
                false,
                false);
        model.setPreserveExactTransport(false);

        assertThat(model.hasBinaryRequest()).isTrue();
        assertThat(model.preserveExactTransport()).isTrue();
        assertThat(model.isValid()).isTrue();
    }

    @Test
    void rejectsMissingDestinationFolderAndBlankNewCollectionName() {
        ApiCollection existing = collectionWithRequest("Existing");
        TrafficDestinationDialogModel model = new TrafficDestinationDialogModel(
                List.of(existing),
                List.of(request("Imported")),
                false,
                false);

        model.setDestinationFolder("Missing/Folder");
        assertThat(model.isValid()).isFalse();
        assertThat(model.validationErrors()).contains("The selected destination folder does not exist.");

        model.setCreateNewCollection(true);
        model.setNewCollectionName("   ");
        assertThat(model.isValid()).isFalse();
        assertThat(model.validationErrors()).contains("A new collection name is required.");
    }

    @Test
    void queueActionDefaultsQueueOptionWithoutSendingAnything() {
        TrafficDestinationDialogModel model = new TrafficDestinationDialogModel(
                List.of(collectionWithRequest("Existing")),
                List.of(request("Imported")),
                true,
                true);

        assertThat(model.queueAction()).isTrue();
        assertThat(model.queueInRunner()).isTrue();
        assertThat(model.captureResponses()).isTrue();
        assertThat(model.selectedCount()).isEqualTo(1);
    }

    private static ApiCollection collectionWithRequest(String name) {
        ApiCollection collection = new ApiCollection();
        collection.id = "collection-id";
        collection.name = "Collection";
        collection.folderPaths.add("Existing/Folder");
        ApiRequest request = request(name);
        request.path = "";
        collection.requests.add(request);
        return collection;
    }

    private static ApiRequest request(String name) {
        ApiRequest request = new ApiRequest();
        request.id = java.util.UUID.randomUUID().toString();
        request.name = name;
        request.method = "GET";
        request.url = "https://example.invalid/";
        return request;
    }
}
