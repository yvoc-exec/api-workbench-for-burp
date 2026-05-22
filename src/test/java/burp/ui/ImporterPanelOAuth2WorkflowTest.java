package burp.ui;

import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.runner.CollectionRunner;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterPanelOAuth2WorkflowTest {

    @Test
    void populateOAuth2FromRequestRetargetsOAuth2ComboToOwningCollection() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collectionA = collection("Collection A");
        ApiCollection collectionB = collectionWithOAuth2Request("Collection B", "req-b", "client-b", "secret-b");
        ApiRequest requestB = collectionB.requests.get(0);

        panel.restoreWorkspaceCollections(List.of(collectionA, collectionB));
        selectOAuth2CollectionIndex(panel, 0);

        invokePrivateMethod(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, requestB);
        drainEdt();

        assertThat(selectedOAuth2Collection(panel)).isSameAs(collectionB);
        assertThat(oauth2Panel(panel).getVariables())
                .containsEntry("oauth2_token_url", "https://auth.example.test/Collection-B/token")
                .containsEntry("oauth2_client_id", "client-b")
                .containsEntry("oauth2_client_secret", "secret-b");
    }

    @Test
    void populateOAuth2FromRequestLeavesOAuth2ComboUnchangedWhenOwningCollectionCannotBeResolved() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collectionA = collection("Collection A");
        ApiCollection collectionB = collectionWithOAuth2Request("Collection B", "req-b", "client-b", "secret-b");
        ApiRequest orphanRequest = collectionWithOAuth2Request("Orphan", "req-orphan", "client-orphan", "secret-orphan").requests.get(0);
        orphanRequest.sourceCollection = "Missing Collection";

        panel.restoreWorkspaceCollections(List.of(collectionA, collectionB));
        selectOAuth2CollectionIndex(panel, 0);

        invokePrivateMethod(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, orphanRequest);
        drainEdt();

        assertThat(selectedOAuth2Collection(panel)).isSameAs(collectionA);
        assertThat(oauth2Panel(panel).getVariables())
                .containsEntry("oauth2_token_url", "https://auth.example.test/Orphan/token")
                .containsEntry("oauth2_client_id", "client-orphan")
                .containsEntry("oauth2_client_secret", "secret-orphan");
    }

    @Test
    void acquireTokenUsesRetargetedOAuth2CollectionAfterPopulateFromRequest() throws Exception {
        ImporterPanel panel = newPanel();
        ApiCollection collectionA = collection("Collection A");
        ApiCollection collectionB = collectionWithOAuth2Request("Collection B", "req-b", "client-b", "secret-b");
        ApiRequest requestB = collectionB.requests.get(0);

        panel.restoreWorkspaceCollections(List.of(collectionA, collectionB));
        selectOAuth2CollectionIndex(panel, 0);

        AtomicReference<ApiCollection> capturedCollection = new AtomicReference<>();
        CountDownLatch callbackSeen = new CountDownLatch(1);
        oauth2Panel(panel).setTokenAcquiredListener((entry, collection, oauth2Vars) -> {
            capturedCollection.set(collection);
            callbackSeen.countDown();
        });
        Mockito.when(oauth2Manager(panel).acquireToken(Mockito.any())).thenReturn(acquiredToken());

        invokePrivateMethod(panel, "populateOAuth2FromRequest", new Class<?>[]{ApiRequest.class}, requestB);
        drainEdt();

        clickPrivateButton(oauth2Panel(panel), "acquireBtn");

        assertThat(callbackSeen.await(5, TimeUnit.SECONDS)).isTrue();
        drainEdt();

        assertThat(selectedOAuth2Collection(panel)).isSameAs(collectionB);
        assertThat(capturedCollection.get()).isSameAs(collectionB);
    }

    private static ImporterPanel newPanel() {
        burp.UniversalImporter importer = Mockito.mock(burp.UniversalImporter.class, Mockito.RETURNS_DEEP_STUBS);
        HttpRequestEditor requestEditor = Mockito.mock(HttpRequestEditor.class);
        Mockito.when(requestEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpRequestEditor(Mockito.any())).thenReturn(requestEditor);
        HttpResponseEditor responseEditor = Mockito.mock(HttpResponseEditor.class);
        Mockito.when(responseEditor.uiComponent()).thenReturn(new JPanel());
        Mockito.when(importer.getApi().userInterface().createHttpResponseEditor(Mockito.any())).thenReturn(responseEditor);
        CollectionRunner runner = Mockito.mock(CollectionRunner.class, Mockito.RETURNS_DEEP_STUBS);
        OAuth2Manager oauth2Manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        return new ImporterPanel(importer, runner, oauth2Manager, burp.utils.ScriptMode.DISABLED);
    }

    private static ApiCollection collection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;
        return collection;
    }

    private static ApiCollection collectionWithOAuth2Request(String collectionName, String requestId, String clientId, String clientSecret) {
        ApiCollection collection = new ApiCollection();
        collection.name = collectionName;

        ApiRequest request = new ApiRequest();
        request.id = requestId;
        request.name = "Get Token";
        request.sourceCollection = collectionName;
        request.method = "POST";
        request.url = "https://auth.example.test/" + collectionName.replace(' ', '-') + "/token";
        request.body = new ApiRequest.Body();
        request.body.mode = "urlencoded";
        request.body.urlencoded.add(new ApiRequest.Body.FormField("grant_type", "client_credentials"));
        request.body.urlencoded.add(new ApiRequest.Body.FormField("client_id", clientId));
        request.body.urlencoded.add(new ApiRequest.Body.FormField("client_secret", clientSecret));
        collection.requests.add(request);
        return collection;
    }

    private static TokenStore.TokenEntry acquiredToken() {
        TokenStore.TokenEntry entry = new TokenStore.TokenEntry();
        entry.accessToken = "access-123";
        entry.refreshToken = "refresh-456";
        entry.tokenType = "Bearer";
        entry.scope = "read write";
        entry.expiresAt = System.currentTimeMillis() + 10_000L;
        return entry;
    }

    private static OAuth2Panel oauth2Panel(ImporterPanel panel) throws Exception {
        return (OAuth2Panel) privateField(panel, "oauth2Panel");
    }

    private static OAuth2Manager oauth2Manager(ImporterPanel panel) throws Exception {
        return (OAuth2Manager) privateField(panel, "oauth2Manager");
    }

    private static ApiCollection selectedOAuth2Collection(ImporterPanel panel) throws Exception {
        return (ApiCollection) invokePrivateMethod(panel, "getSelectedOAuth2Collection", new Class<?>[0]);
    }

    private static void selectOAuth2CollectionIndex(ImporterPanel panel, int index) throws Exception {
        ((JComboBox<?>) privateField(panel, "oauth2CollectionCombo")).setSelectedIndex(index);
    }

    private static void clickPrivateButton(OAuth2Panel oauth2Panel, String fieldName) throws Exception {
        ((JButton) privateField(oauth2Panel, fieldName)).doClick();
    }

    private static Object invokePrivateMethod(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object privateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
