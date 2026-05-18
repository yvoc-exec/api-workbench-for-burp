package burp.ui;

import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEditorPanelAuthMetadataTest {

    @Test
    void applyAuthMetadataPreservesCollectionSourceWhenAuthTypeStaysTheSame() {
        ApiRequest currentRequest = new ApiRequest();
        currentRequest.name = "Get Me";
        currentRequest.auth = new ApiRequest.Auth();
        currentRequest.auth.type = "bearer";
        currentRequest.authInherited = true;
        currentRequest.authSource = "collection: Demo";

        ApiRequest rebuilt = new ApiRequest();
        rebuilt.name = "Get Me";
        rebuilt.auth = new ApiRequest.Auth();
        rebuilt.auth.type = "bearer";

        RequestEditorPanel.applyAuthMetadata(rebuilt, currentRequest, "bearer");

        assertThat(rebuilt.authInherited).isTrue();
        assertThat(rebuilt.authExplicitlyDisabled).isFalse();
        assertThat(rebuilt.authSource).isEqualTo("collection: Demo");
        assertThat(ImporterPanel.buildAuthMetaLine(rebuilt)).isEqualTo("Auth: bearer (collection: Demo)\n");
    }

    @Test
    void applyAuthMetadataPreservesMeaningfulNoAuthSourceWhenAuthTypeRemainsNone() {
        ApiRequest currentRequest = new ApiRequest();
        currentRequest.name = "Public";
        currentRequest.auth = new ApiRequest.Auth();
        currentRequest.auth.type = "none";
        currentRequest.authInherited = true;
        currentRequest.authExplicitlyDisabled = true;
        currentRequest.authSource = "folder: Public";

        ApiRequest rebuilt = new ApiRequest();
        rebuilt.name = "Public";

        RequestEditorPanel.applyAuthMetadata(rebuilt, currentRequest, "none");

        assertThat(rebuilt.auth).isNull();
        assertThat(rebuilt.authInherited).isTrue();
        assertThat(rebuilt.authExplicitlyDisabled).isTrue();
        assertThat(rebuilt.authSource).isEqualTo("folder: Public");
    }
}
