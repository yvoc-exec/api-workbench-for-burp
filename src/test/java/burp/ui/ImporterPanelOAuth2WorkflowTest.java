package burp.ui;

import burp.auth.OAuth2Manager;
import burp.auth.TokenStore;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.ui.tree.CollectionTreeNode;
import burp.runner.CollectionRunner;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
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

    @Test
    void oauth2PopulatePopupSingleRequestModeKeepsExactlyOneCheckedRequest() throws Exception {
        ImporterPanel panel = newPanel();
        DefaultMutableTreeNode root = popupSelectionRoot(twoRequestCollection("Collection A"));
        JLabel countLabel = new JLabel();
        JTree tree = buildPopupSelectionTree(panel, root, countLabel, true);

        CollectionTreeNode collectionNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode firstRequest = (CollectionTreeNode) collectionNode.getChildAt(0);
        CollectionTreeNode secondRequest = (CollectionTreeNode) collectionNode.getChildAt(1);

        clickCheckbox(tree, new TreePath(collectionNode.getPath()));
        assertThat(firstRequest.isChecked()).isTrue();
        assertThat(secondRequest.isChecked()).isFalse();
        assertThat(countLabel.getText()).isEqualTo("1 requests selected");

        clickCheckbox(tree, new TreePath(secondRequest.getPath()));
        assertThat(firstRequest.isChecked()).isFalse();
        assertThat(secondRequest.isChecked()).isTrue();
        assertThat(countLabel.getText()).isEqualTo("1 requests selected");
    }

    @Test
    void otherPopupTreesStillAllowMultipleCheckedRequests() throws Exception {
        ImporterPanel panel = newPanel();
        DefaultMutableTreeNode root = popupSelectionRoot(twoRequestCollection("Collection A"));
        JLabel countLabel = new JLabel();
        JTree tree = buildPopupSelectionTree(panel, root, countLabel, false);

        CollectionTreeNode collectionNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode firstRequest = (CollectionTreeNode) collectionNode.getChildAt(0);
        CollectionTreeNode secondRequest = (CollectionTreeNode) collectionNode.getChildAt(1);

        clickCheckbox(tree, new TreePath(firstRequest.getPath()));
        clickCheckbox(tree, new TreePath(secondRequest.getPath()));

        assertThat(firstRequest.isChecked()).isTrue();
        assertThat(secondRequest.isChecked()).isTrue();
        assertThat(countLabel.getText()).isEqualTo("2 requests selected");
    }

    @Test
    void oauth2PopulateSubmitHelperRequiresExactlyOneCheckedRequest() throws Exception {
        ImporterPanel panel = newPanel();
        DefaultMutableTreeNode root = popupSelectionRoot(twoRequestCollection("Collection A"));
        JLabel countLabel = new JLabel();
        JTree tree = buildPopupSelectionTree(panel, root, countLabel, false);

        CollectionTreeNode collectionNode = (CollectionTreeNode) ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildAt(0);
        CollectionTreeNode firstRequest = (CollectionTreeNode) collectionNode.getChildAt(0);
        CollectionTreeNode secondRequest = (CollectionTreeNode) collectionNode.getChildAt(1);

        clickCheckbox(tree, new TreePath(firstRequest.getPath()));
        assertThat(collectOAuth2PopulateRequests(panel, root)).hasSize(1);

        clickCheckbox(tree, new TreePath(secondRequest.getPath()));
        assertThat(collectOAuth2PopulateRequests(panel, root)).isEmpty();
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

    private static JTree buildPopupSelectionTree(ImporterPanel panel, DefaultMutableTreeNode root, JLabel label, boolean singleRequestMode) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod(
                "buildPopupSelectionTree",
                DefaultMutableTreeNode.class,
                JLabel.class,
                boolean.class
        );
        method.setAccessible(true);
        return (JTree) method.invoke(panel, root, label, singleRequestMode);
    }

    private static DefaultMutableTreeNode popupSelectionRoot(ApiCollection collection) {
        return ImporterPanel.buildRequestTreeRoot(List.of(collection), Collections.emptyMap());
    }

    private static ApiCollection twoRequestCollection(String name) {
        ApiCollection collection = new ApiCollection();
        collection.name = name;

        ApiRequest first = new ApiRequest();
        first.id = "req-1";
        first.name = "First";
        first.method = "GET";
        first.url = "https://api.example.test/first";
        collection.requests.add(first);

        ApiRequest second = new ApiRequest();
        second.id = "req-2";
        second.name = "Second";
        second.method = "POST";
        second.url = "https://api.example.test/second";
        collection.requests.add(second);

        return collection;
    }

    private static void clickCheckbox(JTree tree, TreePath path) {
        tree.setSize(600, 400);
        tree.doLayout();
        Rectangle bounds = tree.getPathBounds(path);
        if (bounds == null) {
            throw new AssertionError("No bounds for tree path: " + path);
        }
        MouseEvent event = new MouseEvent(
                tree,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                bounds.x + 4,
                bounds.y + Math.max(4, bounds.height / 2),
                1,
                false,
                MouseEvent.BUTTON1
        );
        for (MouseListener listener : tree.getMouseListeners()) {
            listener.mouseClicked(event);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ApiRequest> collectOAuth2PopulateRequests(ImporterPanel panel, DefaultMutableTreeNode root) throws Exception {
        Method method = ImporterPanel.class.getDeclaredMethod("collectOAuth2PopulateRequests", DefaultMutableTreeNode.class);
        method.setAccessible(true);
        return (List<ApiRequest>) method.invoke(panel, root);
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
