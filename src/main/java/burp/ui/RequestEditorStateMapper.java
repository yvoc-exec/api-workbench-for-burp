package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptPhase;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;

import javax.swing.JComboBox;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
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
    static final int HEADER_DISABLED_MODEL_COLUMN = 2;

    private RequestEditorStateMapper() {
    }

    static final class Context {
        final JComboBox<String> methodBox;
        final JTextComponent urlField;
        final DefaultTableModel paramsModel;
        final JComboBox<String> authTypeBox;
        final Runnable rebuildAuthFields;
        final DefaultTableModel headersModel;
        final JTextComponent bodyRawArea;
        final DefaultTableModel bodyFormModel;
        final Consumer<String> setBodyModeInternal;
        final Supplier<String> getBodyModeInternal;
        final JTextArea preScriptArea;
        final JTextArea postScriptArea;
        final Supplier<ApiRequest> currentRequestSupplier;
        final Supplier<ApiCollection> currentCollectionSupplier;
        final Supplier<Boolean> exactHttpModeSupplier;
        final Function<ApiRequest, String> resolveEditorAuthMode;
        final Function<String, ApiRequest.Auth> buildAuthFromFields;
        final Runnable refreshResolvedMirror;
        final RequestBuilder requestBuilder;

        Context(JComboBox<String> methodBox,
                JTextComponent urlField,
                DefaultTableModel paramsModel,
                JComboBox<String> authTypeBox,
                Runnable rebuildAuthFields,
                DefaultTableModel headersModel,
                JTextComponent bodyRawArea,
                DefaultTableModel bodyFormModel,
                Consumer<String> setBodyModeInternal,
                Supplier<String> getBodyModeInternal,
                JTextArea preScriptArea,
                JTextArea postScriptArea,
                Supplier<ApiRequest> currentRequestSupplier,
                Supplier<ApiCollection> currentCollectionSupplier,
                Supplier<Boolean> exactHttpModeSupplier,
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
            this.exactHttpModeSupplier = exactHttpModeSupplier;
            this.resolveEditorAuthMode = resolveEditorAuthMode;
            this.buildAuthFromFields = buildAuthFromFields;
            this.refreshResolvedMirror = refreshResolvedMirror;
            this.requestBuilder = requestBuilder;
        }
    }

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
            if ("raw".equals(req.body.mode)) {
                if (req.exactHttpRequest != null && req.exactHttpRequest.binaryBody) {
                    ctx.bodyRawArea.setText(ExactHttpRequestSnapshot.binaryBodyPlaceholder(req.exactHttpRequest.rawRequestBytes));
                } else if (req.body.raw != null) {
                    ctx.bodyRawArea.setText(req.body.raw);
                }
            }
            if ("graphql".equals(req.body.mode) && req.body.graphql != null) {
                ctx.bodyRawArea.setText(req.body.graphql.query != null ? req.body.graphql.query : "");
            }
            if ("file".equals(req.body.mode)) {
                if (req.exactHttpRequest != null && req.exactHttpRequest.binaryBody) {
                    ctx.bodyRawArea.setText(ExactHttpRequestSnapshot.binaryBodyPlaceholder(req.exactHttpRequest.rawRequestBytes));
                } else if (req.body.raw != null) {
                    ctx.bodyRawArea.setText(req.body.raw);
                }
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

        loadScripts(preRequestScriptsForDisplay(req), ctx.preScriptArea);
        loadScripts(postResponseScriptsForDisplay(req), ctx.postScriptArea);
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
        req.buildMode = Boolean.TRUE.equals(ctx.exactHttpModeSupplier.get())
                ? ApiRequest.BuildMode.EXACT_HTTP
                : ApiRequest.BuildMode.MANUAL_PRESERVE;
        req.suppressedAutoHeaders = currentRequest.suppressedAutoHeaders != null
                ? new LinkedHashSet<>(currentRequest.suppressedAutoHeaders)
                : new LinkedHashSet<>();
        req.exactHttpRequest = ExactHttpRequestSnapshot.copyOf(currentRequest.exactHttpRequest);

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
            boolean disabled = Boolean.TRUE.equals(headerDisabledValue(ctx.headersModel, i));
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            req.headers.add(new ApiRequest.Header(key, value != null ? value : "", disabled));
        }

        String bodyMode = ctx.getBodyModeInternal.get();
        if (!"none".equals(bodyMode)) {
            req.body = new ApiRequest.Body();
            ApiRequest.Body existingBody = currentRequest.body;
            req.body.mode = bodyMode;
            if (existingBody != null) {
                req.body.contentType = existingBody.contentType;
                req.body.formdata = copyFormFields(existingBody.formdata);
                req.body.urlencoded = copyFormFields(existingBody.urlencoded);
                req.body.graphql = copyGraphQL(existingBody.graphql);
            }
            if ("raw".equals(bodyMode)) {
                req.body.raw = preserveBinaryPlaceholderBody(currentRequest, ctx.bodyRawArea.getText());
            } else if ("graphql".equals(bodyMode)) {
                ApiRequest.Body.GraphQL graphQL = existingBody != null
                        ? copyGraphQL(existingBody.graphql)
                        : null;
                if (graphQL == null) {
                    graphQL = new ApiRequest.Body.GraphQL();
                }
                graphQL.query = ctx.bodyRawArea.getText();
                req.body.graphql = graphQL;
            } else if ("file".equals(bodyMode)) {
                req.body.raw = preserveBinaryPlaceholderBody(currentRequest, ctx.bodyRawArea.getText());
            } else if ("urlencoded".equals(bodyMode) || "formdata".equals(bodyMode)) {
                if ("urlencoded".equals(bodyMode)) {
                    req.body.urlencoded = mergeFormFields(ctx.bodyFormModel,
                            existingBody != null ? existingBody.urlencoded : null);
                } else {
                    req.body.formdata = mergeFormFields(ctx.bodyFormModel,
                            existingBody != null ? existingBody.formdata : null);
                }
            }
        }

        appendScript(ctx.preScriptArea.getText(), req.preRequestScripts);
        appendScript(ctx.postScriptArea.getText(), req.postResponseScripts);
        req.scriptBlocks = currentRequest.scriptBlocks != null ? copyScriptBlocks(currentRequest.scriptBlocks) : new ArrayList<>();
        if (req.scriptBlocks.isEmpty()) {
            req.scriptBlocks.addAll(convertLegacyScripts(req.preRequestScripts, ScriptPhase.PRE_REQUEST, currentRequest));
            req.scriptBlocks.addAll(convertLegacyScripts(req.postResponseScripts, ScriptPhase.POST_RESPONSE, currentRequest));
        }
        reconcileExactHttpSnapshot(currentRequest, req);
        return req;
    }

    private static String preserveBinaryPlaceholderBody(ApiRequest currentRequest, String bodyText) {
        if (currentRequest == null
                || currentRequest.exactHttpRequest == null
                || !currentRequest.exactHttpRequest.binaryBody
                || !ExactHttpRequestSnapshot.isBinaryBodyPlaceholder(bodyText)) {
            return bodyText;
        }
        return currentRequest.body != null ? currentRequest.body.raw : null;
    }

    private static void reconcileExactHttpSnapshot(ApiRequest currentRequest, ApiRequest builtRequest) {
        if (currentRequest == null || builtRequest == null || builtRequest.exactHttpRequest == null) {
            return;
        }
        String expectedFingerprint = currentRequest.exactHttpRequest.semanticFingerprint;
        String actualFingerprint = builtRequest.computeSemanticFingerprint();
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            builtRequest.exactHttpRequest.semanticFingerprint = actualFingerprint;
            return;
        }
        builtRequest.exactHttpRequest.semanticFingerprint = expectedFingerprint;
        if (!expectedFingerprint.equals(actualFingerprint)) {
            builtRequest.invalidateExactTransport("REQUEST_EDITOR_SEMANTIC_CHANGE");
        }
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
                if (header == null || header.key == null) {
                    continue;
                }
                ctx.headersModel.addRow(headerRow(header.key, header.value != null ? header.value : "", header.disabled, ctx.headersModel));
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
        model.addRow(headerRow(key, value, false, model));
    }

    private static Object[] headerRow(String key, String value, boolean disabled, DefaultTableModel model) {
        if (model != null && model.getColumnCount() > HEADER_DISABLED_MODEL_COLUMN) {
            return new Object[]{key, value, disabled};
        }
        return new Object[]{key, value};
    }

    private static Object headerDisabledValue(DefaultTableModel model, int row) {
        if (model == null || row < 0 || row >= model.getRowCount()) {
            return Boolean.FALSE;
        }
        if (model.getColumnCount() <= HEADER_DISABLED_MODEL_COLUMN) {
            return Boolean.FALSE;
        }
        return model.getValueAt(row, HEADER_DISABLED_MODEL_COLUMN);
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

    private static List<ApiRequest.Script> preRequestScriptsForDisplay(ApiRequest request) {
        if (request == null) {
            return List.of();
        }
        if (request.preRequestScripts != null && !request.preRequestScripts.isEmpty()) {
            return request.preRequestScripts;
        }
        return scriptsForPhase(request.scriptBlocks, ScriptPhase.PRE_REQUEST);
    }

    private static List<ApiRequest.Script> postResponseScriptsForDisplay(ApiRequest request) {
        if (request == null) {
            return List.of();
        }
        if (request.postResponseScripts != null && !request.postResponseScripts.isEmpty()) {
            return request.postResponseScripts;
        }
        return scriptsForPhase(request.scriptBlocks, ScriptPhase.POST_RESPONSE);
    }

    private static List<ApiRequest.Script> scriptsForPhase(List<ScriptBlock> blocks, ScriptPhase phase) {
        List<ApiRequest.Script> scripts = new ArrayList<>();
        if (blocks == null) {
            return scripts;
        }
        for (ScriptBlock block : blocks) {
            if (block != null && block.phase == phase && block.source != null) {
                scripts.add(block.toLegacyScript());
            }
        }
        return scripts;
    }

    private static List<ScriptBlock> copyScriptBlocks(List<ScriptBlock> blocks) {
        List<ScriptBlock> copy = new ArrayList<>();
        if (blocks == null) {
            return copy;
        }
        for (ScriptBlock block : blocks) {
            ScriptBlock cloned = ScriptBlock.copyOf(block);
            if (cloned != null) {
                copy.add(cloned);
            }
        }
        return copy;
    }

    private static List<ApiRequest.Body.FormField> mergeFormFields(DefaultTableModel model, List<ApiRequest.Body.FormField> existing) {
        List<ApiRequest.Body.FormField> merged = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = (String) model.getValueAt(i, 0);
            String value = (String) model.getValueAt(i, 1);
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            int match = findMatchingEnabledField(existing, consumed, key, value);
            ApiRequest.Body.FormField field = match >= 0
                    ? copyFormField(existing.get(match))
                    : new ApiRequest.Body.FormField(key, value != null ? value : "");
            if (match >= 0) {
                consumed.add(match);
            }
            field.key = key;
            field.value = value != null ? value : "";
            field.disabled = false;
            merged.add(field);
        }
        if (existing != null) {
            for (int i = 0; i < existing.size(); i++) {
                ApiRequest.Body.FormField field = existing.get(i);
                if (field != null && field.disabled) {
                    merged.add(copyFormField(field));
                }
            }
        }
        return merged;
    }

    private static int findMatchingEnabledField(List<ApiRequest.Body.FormField> existing,
                                                Set<Integer> consumed,
                                                String key,
                                                String value) {
        if (existing == null) {
            return -1;
        }
        for (int i = 0; i < existing.size(); i++) {
            ApiRequest.Body.FormField field = existing.get(i);
            if (field == null || field.disabled || consumed.contains(i)) {
                continue;
            }
            if (Objects.equals(field.key, key) && Objects.equals(field.value, value != null ? value : "")) {
                return i;
            }
        }
        for (int i = 0; i < existing.size(); i++) {
            ApiRequest.Body.FormField field = existing.get(i);
            if (field != null && !field.disabled && !consumed.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    private static List<ApiRequest.Body.FormField> copyFormFields(List<ApiRequest.Body.FormField> fields) {
        List<ApiRequest.Body.FormField> copy = new ArrayList<>();
        if (fields == null) {
            return copy;
        }
        for (ApiRequest.Body.FormField field : fields) {
            copy.add(copyFormField(field));
        }
        return copy;
    }

    private static ApiRequest.Body.FormField copyFormField(ApiRequest.Body.FormField field) {
        if (field == null) {
            return null;
        }
        ApiRequest.Body.FormField copy = new ApiRequest.Body.FormField(field.key, field.value);
        copy.type = field.type;
        copy.fileUpload = field.fileUpload;
        copy.filePath = field.filePath;
        copy.disabled = field.disabled;
        return copy;
    }

    private static ApiRequest.Body.GraphQL copyGraphQL(ApiRequest.Body.GraphQL graphQL) {
        if (graphQL == null) {
            return null;
        }
        ApiRequest.Body.GraphQL copy = new ApiRequest.Body.GraphQL();
        copy.query = graphQL.query;
        copy.variables = graphQL.variables;
        return copy;
    }

    private static List<ScriptBlock> convertLegacyScripts(List<ApiRequest.Script> scripts, ScriptPhase phase, ApiRequest currentRequest) {
        List<ScriptBlock> blocks = new ArrayList<>();
        if (scripts == null) {
            return blocks;
        }
        int order = 0;
        for (ApiRequest.Script script : scripts) {
            ScriptBlock block = ScriptBlock.fromLegacy(
                    script,
                    burp.scripts.ScriptDialect.LEGACY_NASHORN,
                    phase,
                    burp.scripts.ScriptScope.REQUEST,
                    currentRequest != null ? currentRequest.sourceCollection : null,
                    currentRequest != null ? currentRequest.path : null,
                    order++
            );
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }
}
