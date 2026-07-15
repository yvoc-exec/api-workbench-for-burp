package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptPhase;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;
import burp.utils.RequestParameterSupport;

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
    static final int HEADER_KEY_MODEL_COLUMN = 0;
    static final int HEADER_VALUE_MODEL_COLUMN = 1;
    static final int HEADER_ENABLED_MODEL_COLUMN = 2;

    static final int PARAM_KEY_MODEL_COLUMN = 0;
    static final int PARAM_VALUE_MODEL_COLUMN = 1;
    static final int PARAM_ENABLED_MODEL_COLUMN = 2;
    static final int PARAM_DESCRIPTION_MODEL_COLUMN = 3;
    static final int PARAM_RAW_KEY_MODEL_COLUMN = 4;
    static final int PARAM_RAW_VALUE_MODEL_COLUMN = 5;
    static final int PARAM_VALUE_PRESENT_MODEL_COLUMN = 6;
    static final int PARAM_REQUIRED_MODEL_COLUMN = 7;
    static final int PARAM_TYPE_MODEL_COLUMN = 8;
    static final int PARAM_SOURCE_MODEL_COLUMN = 9;

    static final int BODY_KEY_MODEL_COLUMN = 0;
    static final int BODY_VALUE_MODEL_COLUMN = 1;
    static final int BODY_ENABLED_MODEL_COLUMN = 2;
    static final int BODY_TYPE_MODEL_COLUMN = 3;
    static final int BODY_FILE_PATH_MODEL_COLUMN = 4;

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
        if (RequestParameterSupport.hasQueryParameters(req.parameters)) {
            for (ApiRequest.Parameter parameter : req.parameters) {
                if (parameter != null && parameter.isQuery()) {
                    addParameterRow(ctx.paramsModel, parameter);
                }
            }
        } else {
            parseQueryToTable(req.url, ctx.paramsModel);
        }
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
            if ("urlencoded".equals(req.body.mode) && req.body.urlencoded != null) {
                for (ApiRequest.Body.FormField f : req.body.urlencoded) {
                    if (f != null) {
                        ctx.bodyFormModel.addRow(bodyRow(f));
                    }
                }
            }
            if ("formdata".equals(req.body.mode) && req.body.formdata != null) {
                for (ApiRequest.Body.FormField f : req.body.formdata) {
                    if (f != null) {
                        ctx.bodyFormModel.addRow(bodyRow(f));
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
        req.parameters = existingNonQueryParameters(currentRequest.parameters);
        req.parameters.addAll(parametersFromTable(ctx.paramsModel, currentRequest.parameters));
        req.url = RequestParameterSupport.materializeUrl(
                ctx.urlField.getText(),
                req.parameters,
                null);
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
            String key = (String) ctx.headersModel.getValueAt(i, HEADER_KEY_MODEL_COLUMN);
            String value = (String) ctx.headersModel.getValueAt(i, HEADER_VALUE_MODEL_COLUMN);
            boolean disabled = !Boolean.TRUE.equals(headerEnabledValue(ctx.headersModel, i));
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
                    req.body.urlencoded = formFieldsFromTable(ctx.bodyFormModel,
                            existingBody != null ? existingBody.urlencoded : null, false);
                } else {
                    req.body.formdata = formFieldsFromTable(ctx.bodyFormModel,
                            existingBody != null ? existingBody.formdata : null, true);
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
            String existingKey = (String) model.getValueAt(i, headerKeyColumn(model));
            if (existingKey != null && existingKey.trim().equalsIgnoreCase(key)) {
                return;
            }
        }
        model.addRow(headerRow(key, value, false, model));
    }

    private static Object[] headerRow(String key, String value, boolean disabled, DefaultTableModel model) {
        if (isHeaderModel(model)) {
            return new Object[]{key, value, !disabled};
        }
        return new Object[]{key, value};
    }

    private static Object headerEnabledValue(DefaultTableModel model, int row) {
        if (!isHeaderModel(model) || row < 0 || row >= model.getRowCount()) {
            return Boolean.TRUE;
        }
        return model.getValueAt(row, HEADER_ENABLED_MODEL_COLUMN);
    }

    static void ensureStarterRow(DefaultTableModel model) {
        int keyColumn = headerKeyColumn(model);
        if (model.getRowCount() > 0) {
            String lastKey = (String) model.getValueAt(model.getRowCount() - 1, keyColumn);
            if (lastKey == null || lastKey.trim().isEmpty()) {
                return;
            }
        }
        int cols = model.getColumnCount();
        Object[] row = new Object[cols];
        java.util.Arrays.fill(row, "");
        if (isHeaderModel(model)) {
            row[HEADER_ENABLED_MODEL_COLUMN] = Boolean.TRUE;
        } else if (isParamsModel(model)) {
            row[PARAM_ENABLED_MODEL_COLUMN] = Boolean.TRUE;
            row[PARAM_VALUE_PRESENT_MODEL_COLUMN] = Boolean.FALSE;
            row[PARAM_REQUIRED_MODEL_COLUMN] = Boolean.FALSE;
            row[PARAM_SOURCE_MODEL_COLUMN] = "workbench";
        } else if (isBodyModel(model)) {
            row[BODY_ENABLED_MODEL_COLUMN] = Boolean.TRUE;
            row[BODY_TYPE_MODEL_COLUMN] = "text";
        }
        model.addRow(row);
    }

    private static boolean isHeaderModel(DefaultTableModel model) {
        return model != null
                && model.getColumnCount() == 3
                && "Enabled".equals(String.valueOf(model.getColumnName(HEADER_ENABLED_MODEL_COLUMN)));
    }

    private static int headerKeyColumn(DefaultTableModel model) {
        return isHeaderModel(model) ? HEADER_KEY_MODEL_COLUMN : 0;
    }

    private static boolean isParamsModel(DefaultTableModel model) {
        return model != null
                && model.getColumnCount() == 10
                && "Value Present".equals(String.valueOf(model.getColumnName(PARAM_VALUE_PRESENT_MODEL_COLUMN)));
    }

    private static boolean isBodyModel(DefaultTableModel model) {
        return model != null
                && model.getColumnCount() == 5
                && "File Path".equals(String.valueOf(model.getColumnName(BODY_FILE_PATH_MODEL_COLUMN)));
    }

    static void parseQueryToTable(String url, DefaultTableModel paramsModel) {
        for (ApiRequest.Parameter parameter : RequestParameterSupport.parseQueryParameters(url, "legacy:url")) {
            if (isParamsModel(paramsModel)) {
                addParameterRow(paramsModel, parameter);
            } else {
                paramsModel.addRow(new Object[]{parameter.key, parameter.value});
            }
        }
    }

    static String rebuildUrlWithParams(String urlBase, DefaultTableModel model) {
        return RequestParameterSupport.materializeUrl(urlBase, parametersFromTable(model), null);
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

    private static void addParameterRow(DefaultTableModel model, ApiRequest.Parameter parameter) {
        model.addRow(new Object[]{
                parameter.key != null ? parameter.key : "",
                parameter.value != null ? parameter.value : "",
                !parameter.disabled,
                parameter.description != null ? parameter.description : "",
                parameter.rawKey != null ? parameter.rawKey : "",
                parameter.rawValue != null ? parameter.rawValue : "",
                parameter.valuePresent,
                parameter.required,
                parameter.type != null ? parameter.type : "",
                parameter.source != null ? parameter.source : ""
        });
    }

    private static Object[] bodyRow(ApiRequest.Body.FormField field) {
        String type = field.type;
        if (type == null || type.isBlank()) {
            type = field.fileUpload ? "file" : "text";
        }
        return new Object[]{
                field.key != null ? field.key : "",
                field.value != null ? field.value : "",
                !field.disabled,
                type,
                field.filePath != null ? field.filePath : ""
        };
    }

    private static List<ApiRequest.Parameter> existingNonQueryParameters(List<ApiRequest.Parameter> existing) {
        List<ApiRequest.Parameter> parameters = new ArrayList<>();
        for (ApiRequest.Parameter parameter : RequestParameterSupport.copyParameters(existing)) {
            if (parameter != null && !parameter.isQuery()) {
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    private static List<ApiRequest.Parameter> parametersFromTable(DefaultTableModel model) {
        return parametersFromTable(model, null);
    }

    private static List<ApiRequest.Parameter> parametersFromTable(DefaultTableModel model,
                                                                   List<ApiRequest.Parameter> existing) {
        List<ApiRequest.Parameter> parameters = new ArrayList<>();
        List<ApiRequest.Parameter> existingQuery = new ArrayList<>();
        if (existing != null) {
            for (ApiRequest.Parameter parameter : existing) {
                if (parameter != null && parameter.isQuery()) {
                    existingQuery.add(parameter);
                }
            }
        }
        int existingIndex = 0;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = tableString(model, i, PARAM_KEY_MODEL_COLUMN);
            String value = tableString(model, i, PARAM_VALUE_MODEL_COLUMN);
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", key, value != null ? value : "");
            ApiRequest.Parameter prior = existingIndex < existingQuery.size()
                    ? existingQuery.get(existingIndex)
                    : null;
            if (prior != null) {
                parameter.style = prior.style;
                parameter.explode = prior.explode;
                parameter.allowReserved = prior.allowReserved;
            }
            existingIndex++;
            if (isParamsModel(model)) {
                Object enabledValue = model.getValueAt(i, PARAM_ENABLED_MODEL_COLUMN);
                parameter.disabled = Boolean.FALSE.equals(enabledValue);
                String source = tableString(model, i, PARAM_SOURCE_MODEL_COLUMN);
                boolean legacy = "legacy:url".equals(source);
                parameter.description = optionalMetadata(tableString(model, i, PARAM_DESCRIPTION_MODEL_COLUMN),
                        prior != null ? prior.description : null, false);
                parameter.rawKey = optionalMetadata(tableString(model, i, PARAM_RAW_KEY_MODEL_COLUMN),
                        prior != null ? prior.rawKey : null, legacy);
                parameter.rawValue = optionalMetadata(tableString(model, i, PARAM_RAW_VALUE_MODEL_COLUMN),
                        prior != null ? prior.rawValue : null, legacy);
                parameter.valuePresent = Boolean.TRUE.equals(model.getValueAt(i, PARAM_VALUE_PRESENT_MODEL_COLUMN))
                        || (value != null && !value.isEmpty());
                parameter.required = Boolean.TRUE.equals(model.getValueAt(i, PARAM_REQUIRED_MODEL_COLUMN));
                parameter.type = optionalMetadata(tableString(model, i, PARAM_TYPE_MODEL_COLUMN),
                        prior != null ? prior.type : null, false);
                parameter.source = optionalMetadata(source, prior != null ? prior.source : null, false);
                if (enabledValue == null) {
                    parameter.rawKey = java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
                    parameter.rawValue = java.net.URLEncoder.encode(value != null ? value : "", java.nio.charset.StandardCharsets.UTF_8);
                }
            } else {
                parameter.valuePresent = value != null && !value.isEmpty();
            }
            parameters.add(parameter);
        }
        return parameters;
    }

    private static List<ApiRequest.Body.FormField> formFieldsFromTable(DefaultTableModel model,
                                                                       List<ApiRequest.Body.FormField> existing,
                                                                       boolean multipart) {
        List<ApiRequest.Body.FormField> fields = new ArrayList<>();
        int existingIndex = 0;
        for (int i = 0; i < model.getRowCount(); i++) {
            String key = tableString(model, i, BODY_KEY_MODEL_COLUMN);
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            ApiRequest.Body.FormField prior = existing != null && existingIndex < existing.size()
                    ? existing.get(existingIndex)
                    : null;
            existingIndex++;
            String value = tableString(model, i, BODY_VALUE_MODEL_COLUMN);
            String type = emptyToNull(tableString(model, i, BODY_TYPE_MODEL_COLUMN));
            String filePath = emptyToNull(tableString(model, i, BODY_FILE_PATH_MODEL_COLUMN));
            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value != null ? value : "");
            field.disabled = isBodyModel(model)
                    && Boolean.FALSE.equals(model.getValueAt(i, BODY_ENABLED_MODEL_COLUMN));
            field.type = type != null ? type : "text";
            field.filePath = filePath;
            if (multipart) {
                field.fileUpload = "file".equalsIgnoreCase(field.type) || filePath != null;
                if (field.fileUpload && type == null) {
                    field.type = "file";
                }
            } else {
                field.fileUpload = prior != null && prior.fileUpload;
            }
            fields.add(field);
        }
        return fields;
    }

    private static String tableString(DefaultTableModel model, int row, int column) {
        if (model == null || column < 0 || column >= model.getColumnCount()) {
            return "";
        }
        Object value = model.getValueAt(row, column);
        return value != null ? String.valueOf(value) : "";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String optionalMetadata(String value, String prior, boolean keepEmpty) {
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return keepEmpty || (prior != null && prior.isEmpty()) ? "" : null;
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
                    burp.scripts.ScriptDialect.LEGACY_JAVASCRIPT,
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
