package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;

import javax.swing.JComboBox;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Maps between RequestEditorPanel Swing state and ApiRequest models.
 *
 * <p>This keeps the panel focused on UI orchestration while centralizing
 * request load/build behavior in one place.</p>
 */
final class RequestEditorStateMapper {

    private RequestEditorStateMapper() {
    }

    static final class Context {
        final JComboBox<String> methodBox;
        final JTextField urlField;
        final DefaultTableModel paramsModel;
        final JComboBox<String> authTypeBox;
        final Runnable rebuildAuthFields;
        final DefaultTableModel headersModel;
        final JTextArea bodyRawArea;
        final DefaultTableModel bodyFormModel;
        final Consumer<String> setBodyModeInternal;
        final Supplier<String> getBodyModeInternal;
        final JTextArea preScriptArea;
        final JTextArea postScriptArea;
        final Supplier<ApiRequest> currentRequestSupplier;
        final Supplier<ApiCollection> currentCollectionSupplier;
        final Function<ApiRequest, String> resolveEditorAuthMode;
        final Function<String, ApiRequest.Auth> buildAuthFromFields;
        final Runnable refreshResolvedMirror;
        final RequestBuilder requestBuilder;

        Context(JComboBox<String> methodBox,
                JTextField urlField,
                DefaultTableModel paramsModel,
                JComboBox<String> authTypeBox,
                Runnable rebuildAuthFields,
                DefaultTableModel headersModel,
                JTextArea bodyRawArea,
                DefaultTableModel bodyFormModel,
                Consumer<String> setBodyModeInternal,
                Supplier<String> getBodyModeInternal,
                JTextArea preScriptArea,
                JTextArea postScriptArea,
                Supplier<ApiRequest> currentRequestSupplier,
                Supplier<ApiCollection> currentCollectionSupplier,
                Function<ApiRequest, String> resolveEditorAuthMode,
                Function<String, ApiRequest.Auth> buildAuthFromFields,
                Runnable refreshResolvedMirror,
                RequestBuilder requestBuilder) {
            this.methodBox = methodBox;
            this.urlField = urlField;
            this.paramsModel = paramsModel;
            this.authTypeBox = authTypeBox;
            this.rebuildAuthFields = rebuildAuthFields;
            this.headersModel = headersModel;
            this.bodyRawArea = bodyRawArea;
            this.bodyFormModel = bodyFormModel;
            this.setBodyModeInternal = setBodyModeInternal;
            this.getBodyModeInternal = getBodyModeInternal;
            this.preScriptArea = preScriptArea;
            this.postScriptArea = postScriptArea;
            this.currentRequestSupplier = currentRequestSupplier;
            this.currentCollectionSupplier = currentCollectionSupplier;
            this.resolveEditorAuthMode = resolveEditorAuthMode;
            this.buildAuthFromFields = buildAuthFromFields;
            this.refreshResolvedMirror = refreshResolvedMirror;
            this.requestBuilder = requestBuilder;
        }
    }

    private static final Set<String> TRANSPORT_HEADER_NAMES = Set.of(
            "host", "content-length", "transfer-encoding"
    );

    static void loadRequest(ApiRequest req, Context ctx) {
        if (req == null) {
            ctx.methodBox.setSelectedItem("GET");
            ctx.urlField.setText("");
            clearEditor(ctx);
            ctx.refreshResolvedMirror.run();
            return;
        }

        ctx.methodBox.setSelectedItem(req.method != null ? req.method.toUpperCase(Locale.ROOT) : "GET");
        ctx.urlField.setText(req.url != null ? req.url : "");

        ctx.paramsModel.setRowCount(0);
        parseQueryToTable(req.url, ctx.paramsModel);
        ensureStarterRow(ctx.paramsModel);

        ctx.authTypeBox.setSelectedItem(ctx.resolveEditorAuthMode.apply(req));
        ctx.rebuildAuthFields.run();

        ctx.headersModel.setRowCount(0);
        loadEditorHeaders(req, ctx);
        ensureStarterRow(ctx.headersModel);

        ctx.bodyRawArea.setText("");
        ctx.bodyFormModel.setRowCount(0);
        if (req.body != null) {
            ctx.setBodyModeInternal.accept(req.body.mode != null ? req.body.mode : "none");
            if ("raw".equals(req.body.mode) && req.body.raw != null) {
                ctx.bodyRawArea.setText(req.body.raw);
            }
            if ("graphql".equals(req.body.mode) && req.body.graphql != null) {
                ctx.bodyRawArea.setText(req.body.graphql.query != null ? req.body.graphql.query : "");
            }
            if ("file".equals(req.body.mode) && req.body.raw != null) {
                ctx.bodyRawArea.setText(req.body.raw);
            }
            if (req.body.urlencoded != null) {
                for (ApiRequest.Body.FormField f : req.body.urlencoded) {
                    if (f != null && !f.disabled) {
                        ctx.bodyFormModel.addRow(new Object[]{f.key, f.value});
                    }
                }
            }
            if (req.body.formdata != null) {
                for (ApiRequest.Body.FormField f : req.body.formdata) {
                    if (f != null && !f.disabled) {
                        ctx.bodyFormModel.addRow(new Object[]{f.key, f.value});
                    }
                }
            }
            ensureStarterRow(ctx.bodyFormModel);
        } else {
            ctx.setBodyModeInternal.accept("none");
            ensureStarterRow(ctx.bodyFormModel);
        }

        loadScripts(req.preRequestScripts, ctx.preScriptArea);
        loadScripts(req.postResponseScripts, ctx.postScriptArea);
        ctx.refreshResolvedMirror.run();
    }

    static ApiRequest buildRequest(Context ctx) {
        ApiRequest currentRequest = ctx.currentRequestSupplier.get();
        if (currentRequest == null) {
            return null;
        }

        ApiRequest req = new ApiRequest();
        req.name = currentRequest.name;
        req.path = currentRequest.path;
        req.sourceCollection = currentRequest.sourceCollection;
        req.id = currentRequest.id;
        req.sequenceOrder = currentRequest.sequenceOrder;
        req.method = (String) ctx.methodBox.getSelectedItem();
        req.url = rebuildUrlWithParams(ctx.urlField.getText(), ctx.paramsModel);
        req.editorMaterialized = true;
        req.buildMode = ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.suppressedAutoHeaders = currentRequest.suppressedAutoHeaders != null
                ? new LinkedHashSet<>(currentRequest.suppressedAutoHeaders)
                : new LinkedHashSet<>();

        String authMode = (String) ctx.authTypeBox.getSelectedItem();
        req.authOverrideMode = RequestEditorPanel.selectionToOverrideMode(authMode);
        if ("inherit".equals(req.authOverrideMode)) {
            req.explicitAuth = null;
        } else {
            req.explicitAuth = ctx.buildAuthFromFields.apply(authMode);
        }
        burp.utils.AuthInheritanceResolver.resolveRequestAuth(ctx.currentCollectionSupplier.get(), req);

        for (int i = 0; i < ctx.headersModel.getRowCount(); i++) {
            String key = (String) ctx.headersModel.getValueAt(i, 0);
            String value = (String) ctx.headersModel.getValueAt(i, 1);
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            if (TRANSPORT_HEADER_NAMES.contains(key.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            req.headers.add(new ApiRequest.Header(key, value != null ? value : "", false));
        }

        String bodyMode = ctx.getBodyModeInternal.get();
        if (!"none".equals(bodyMode)) {
            req.body = new ApiRequest.Body();
            req.body.mode = bodyMode;
            if ("raw".equals(bodyMode)) {
                req.body.raw = ctx.bodyRawArea.getText();
            } else if ("graphql".equals(bodyMode)) {
                ApiRequest.Body.GraphQL graphQL = new ApiRequest.Body.GraphQL();
                if (currentRequest.body != null && currentRequest.body.graphql != null) {
                    graphQL.variables = currentRequest.body.graphql.variables;
                }
                graphQL.query = ctx.bodyRawArea.getText();
                req.body.graphql = graphQL;
                if (currentRequest.body != null) {
                    req.body.contentType = currentRequest.body.contentType;
                }
            } else if ("file".equals(bodyMode)) {
                if (currentRequest.body != null) {
                    req.body.raw = ctx.bodyRawArea.getText();
                    req.body.contentType = currentRequest.body.contentType;
                    req.body.formdata = currentRequest.body.formdata != null ? new ArrayList<>(currentRequest.body.formdata) : new ArrayList<>();
                    req.body.urlencoded = currentRequest.body.urlencoded != null ? new ArrayList<>(currentRequest.body.urlencoded) : new ArrayList<>();
                }
            } else if ("urlencoded".equals(bodyMode) || "formdata".equals(bodyMode)) {
                List<ApiRequest.Body.FormField> fields = new ArrayList<>();
                for (int i = 0; i < ctx.bodyFormModel.getRowCount(); i++) {
                    String k = (String) ctx.bodyFormModel.getValueAt(i, 0);
                    String v = (String) ctx.bodyFormModel.getValueAt(i, 1);
                    if (k != null && !k.trim().isEmpty()) {
                        fields.add(new ApiRequest.Body.FormField(k, v != null ? v : ""));
                    }
                }
                if ("urlencoded".equals(bodyMode)) {
                    req.body.urlencoded = fields;
                } else {
                    req.body.formdata = fields;
                }
            }
        }

        appendScript(ctx.preScriptArea.getText(), req.preRequestScripts);
        appendScript(ctx.postScriptArea.getText(), req.postResponseScripts);
        return req;
    }

    static void clearEditor(Context ctx) {
        ctx.paramsModel.setRowCount(0);
        ensureStarterRow(ctx.paramsModel);
        ctx.authTypeBox.setSelectedItem("none");
        ctx.rebuildAuthFields.run();
        ctx.headersModel.setRowCount(0);
        ensureStarterRow(ctx.headersModel);
        ctx.setBodyModeInternal.accept("none");
        ctx.bodyRawArea.setText("");
        ctx.bodyFormModel.setRowCount(0);
        ensureStarterRow(ctx.bodyFormModel);
        ctx.preScriptArea.setText("");
        ctx.postScriptArea.setText("");
        ctx.refreshResolvedMirror.run();
    }

    static void loadEditorHeaders(ApiRequest req, Context ctx) {
        if (req == null) {
            return;
        }
        if (req.headers != null) {
            for (ApiRequest.Header header : req.headers) {
                if (header == null || header.disabled || header.key == null) {
                    continue;
                }
                String lowerKey = header.key.trim().toLowerCase(Locale.ROOT);
                if (req.isAutoHeaderSuppressed(lowerKey)) {
                    continue;
                }
                if (!TRANSPORT_HEADER_NAMES.contains(lowerKey)) {
                    ctx.headersModel.addRow(new Object[]{header.key, header.value != null ? header.value : ""});
                }
            }
        }

        if (req.isAutoCompatibleMode()) {
            if (!req.isAutoHeaderSuppressed("accept")) {
                ensureDefaultHeader(ctx.headersModel, "Accept", "application/json, text/plain, */*");
            }
            if (!req.isAutoHeaderSuppressed("user-agent")) {
                ensureDefaultHeader(ctx.headersModel, "User-Agent", "BurpExtensionRuntime");
            }
            if (!req.isAutoHeaderSuppressed("cache-control")) {
                ensureDefaultHeader(ctx.headersModel, "Cache-Control", "no-cache");
            }
        }
    }

    private static void ensureDefaultHeader(DefaultTableModel model, String key, String value) {
        if (model == null || key == null) {
            return;
        }
        for (int i = 0; i < model.getRowCount(); i++) {
            String existingKey = (String) model.getValueAt(i, 0);
            if (existingKey != null && existingKey.trim().equalsIgnoreCase(key)) {
                return;
            }
        }
        model.addRow(new Object[]{key, value});
    }

    static void ensureStarterRow(DefaultTableModel model) {
        if (model.getRowCount() > 0) {
            String lastKey = (String) model.getValueAt(model.getRowCount() - 1, 0);
            if (lastKey == null || lastKey.trim().isEmpty()) {
                return;
            }
        }
        int cols = model.getColumnCount();
        Object[] row = new Object[cols];
        java.util.Arrays.fill(row, "");
        model.addRow(row);
    }

    static void parseQueryToTable(String url, DefaultTableModel paramsModel) {
        if (url == null) {
            return;
        }
        int q = url.indexOf('?');
        if (q < 0 || q + 1 >= url.length()) {
            return;
        }
        String query = url.substring(q + 1);
        int frag = query.indexOf('#');
        if (frag >= 0) {
            query = query.substring(0, frag);
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                paramsModel.addRow(new Object[]{pair, ""});
            } else {
                String key = pair.substring(0, eq);
                String val = eq + 1 < pair.length() ? pair.substring(eq + 1) : "";
                try {
                    key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
                    val = java.net.URLDecoder.decode(val, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // keep raw if decoding fails
                }
                paramsModel.addRow(new Object[]{key, val});
            }
        }
    }

    static String rebuildUrlWithParams(String urlBase, DefaultTableModel model) {
        if (urlBase == null) {
            urlBase = "";
        }
        String fragment = "";
        int frag = urlBase.indexOf('#');
        if (frag >= 0) {
            fragment = urlBase.substring(frag);
            urlBase = urlBase.substring(0, frag);
        }
        int q = urlBase.indexOf('?');
        if (q >= 0) {
            urlBase = urlBase.substring(0, q);
        }
        StringBuilder sb = new StringBuilder(urlBase);
        boolean first = true;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            if (first) {
                sb.append('?');
                first = false;
            } else {
                sb.append('&');
            }
            try {
                sb.append(java.net.URLEncoder.encode(key.trim(), java.nio.charset.StandardCharsets.UTF_8));
                if (value != null && !value.isEmpty()) {
                    sb.append('=');
                    sb.append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                sb.append(key.trim());
                if (value != null && !value.isEmpty()) {
                    sb.append('=').append(value);
                }
            }
        }
        sb.append(fragment);
        return sb.toString();
    }

    private static void loadScripts(List<ApiRequest.Script> scripts, JTextArea targetArea) {
        targetArea.setText("");
        if (scripts == null) {
            return;
        }
        for (int i = 0; i < scripts.size(); i++) {
            ApiRequest.Script s = scripts.get(i);
            if (s.exec != null) {
                if (i > 0 && !targetArea.getText().endsWith("\n")) {
                    targetArea.append("\n");
                }
                targetArea.append(s.exec);
            }
        }
    }

    private static void appendScript(String text, List<ApiRequest.Script> targetScripts) {
        String script = text.trim();
        if (!script.isEmpty()) {
            targetScripts.add(new ApiRequest.Script("js", script));
        }
    }
}
