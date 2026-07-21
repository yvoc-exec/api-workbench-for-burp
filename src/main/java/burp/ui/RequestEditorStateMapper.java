package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.ExactHttpRequestSnapshot;
import burp.scripts.ScriptBlock;
import burp.scripts.ScriptPhase;
import burp.parser.VariableResolver;
import burp.utils.RequestBuilder;
import burp.utils.OpenApiMetadataSupport;
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
    static final int PARAM_STYLE_MODEL_COLUMN = 10;
    static final int PARAM_EXPLODE_MODEL_COLUMN = 11;
    static final int PARAM_ALLOW_RESERVED_MODEL_COLUMN = 12;
    static final int PARAM_EXISTING_ROW_MODEL_COLUMN = 13;
    static final int PARAM_LOCATION_MODEL_COLUMN = 14;
    static final int PARAM_FORMAT_MODEL_COLUMN = 15;
    static final int PARAM_SOURCE_METADATA_MODEL_COLUMN = 16;

    static final int BODY_KEY_MODEL_COLUMN = 0;
    static final int BODY_VALUE_MODEL_COLUMN = 1;
    static final int BODY_ENABLED_MODEL_COLUMN = 2;
    static final int BODY_TYPE_MODEL_COLUMN = 3;
    static final int BODY_FILE_PATH_MODEL_COLUMN = 4;
    static final int BODY_FILE_UPLOAD_MODEL_COLUMN = 5;
    static final int BODY_ORIGINAL_TYPE_MODEL_COLUMN = 6;
    static final int BODY_ORIGINAL_FILE_PATH_MODEL_COLUMN = 7;
    static final int BODY_ORIGINAL_FILE_UPLOAD_MODEL_COLUMN = 8;
    static final int BODY_EXISTING_ROW_MODEL_COLUMN = 9;
    static final int BODY_REQUIRED_MODEL_COLUMN = 10;
    static final int BODY_DESCRIPTION_MODEL_COLUMN = 11;
    static final int BODY_CONTENT_TYPE_MODEL_COLUMN = 12;
    static final int BODY_STYLE_MODEL_COLUMN = 13;
    static final int BODY_EXPLODE_MODEL_COLUMN = 14;
    static final int BODY_ALLOW_RESERVED_MODEL_COLUMN = 15;
    static final int BODY_SOURCE_MODEL_COLUMN = 16;
    static final int BODY_SOURCE_METADATA_MODEL_COLUMN = 17;

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
        if (req.parameters != null) {
            for (ApiRequest.Parameter parameter : req.parameters) {
                if (parameter != null) {
                    addParameterRow(ctx.paramsModel, parameter);
                }
            }
        }
        if (!RequestParameterSupport.hasQueryParameters(req.parameters)) {
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
        req.description = currentRequest.description;
        req.sourceMetadata = OpenApiMetadataSupport.copy(currentRequest.sourceMetadata);
        req.disabled = currentRequest.disabled;
        req.variables = copyVariables(currentRequest.variables);
        req.parameters = new ArrayList<>();
        if (isUnchangedLegacyQueryView(currentRequest, ctx.urlField.getText(), ctx.paramsModel)) {
            req.url = currentRequest.url;
        } else {
            req.parameters = parametersFromTable(ctx.paramsModel);
            req.url = RequestParameterSupport.materializeUrl(
                    ctx.urlField.getText(),
                    req.parameters,
                    null);
        }
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
                if (Objects.equals(bodyMode, existingBody.mode)) {
                    req.body.required = existingBody.required;
                    req.body.description = existingBody.description;
                    req.body.filePath = existingBody.filePath;
                    req.body.source = existingBody.source;
                    req.body.sourceMetadata = OpenApiMetadataSupport.copy(existingBody.sourceMetadata);
                }
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
                    req.body.urlencoded = formFieldsFromTable(ctx.bodyFormModel, false);
                } else {
                    req.body.formdata = formFieldsFromTable(ctx.bodyFormModel, true);
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
        if (model.getRowCount() > 0) {
            int lastRow = model.getRowCount() - 1;
            if (isHeaderModel(model)) {
                String lastKey = tableString(model, lastRow, HEADER_KEY_MODEL_COLUMN);
                if (lastKey.trim().isEmpty()) {
                    return;
                }
            } else if (isParamsModel(model)) {
                if (isUntouchedNewParameterRow(model, lastRow)) {
                    return;
                }
            } else if (isBodyModel(model)) {
                if (isUntouchedNewBodyRow(model, lastRow)) {
                    return;
                }
            } else {
                String lastKey = tableString(model, lastRow, 0);
                if (lastKey.trim().isEmpty()) {
                    return;
                }
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
            row[PARAM_ALLOW_RESERVED_MODEL_COLUMN] = Boolean.FALSE;
            row[PARAM_EXISTING_ROW_MODEL_COLUMN] = Boolean.FALSE;
            row[PARAM_RAW_KEY_MODEL_COLUMN] = null;
            row[PARAM_RAW_VALUE_MODEL_COLUMN] = null;
            row[PARAM_TYPE_MODEL_COLUMN] = null;
            row[PARAM_STYLE_MODEL_COLUMN] = null;
            row[PARAM_EXPLODE_MODEL_COLUMN] = null;
            row[PARAM_LOCATION_MODEL_COLUMN] = "query";
            row[PARAM_FORMAT_MODEL_COLUMN] = null;
            row[PARAM_SOURCE_METADATA_MODEL_COLUMN] = null;
        } else if (isBodyModel(model)) {
            row[BODY_ENABLED_MODEL_COLUMN] = Boolean.TRUE;
            row[BODY_TYPE_MODEL_COLUMN] = "text";
            row[BODY_FILE_UPLOAD_MODEL_COLUMN] = Boolean.FALSE;
            row[BODY_ORIGINAL_TYPE_MODEL_COLUMN] = null;
            row[BODY_ORIGINAL_FILE_PATH_MODEL_COLUMN] = null;
            row[BODY_ORIGINAL_FILE_UPLOAD_MODEL_COLUMN] = Boolean.FALSE;
            row[BODY_EXISTING_ROW_MODEL_COLUMN] = Boolean.FALSE;
            row[BODY_REQUIRED_MODEL_COLUMN] = Boolean.FALSE;
            row[BODY_DESCRIPTION_MODEL_COLUMN] = null;
            row[BODY_CONTENT_TYPE_MODEL_COLUMN] = null;
            row[BODY_STYLE_MODEL_COLUMN] = null;
            row[BODY_EXPLODE_MODEL_COLUMN] = null;
            row[BODY_ALLOW_RESERVED_MODEL_COLUMN] = Boolean.FALSE;
            row[BODY_SOURCE_MODEL_COLUMN] = null;
            row[BODY_SOURCE_METADATA_MODEL_COLUMN] = null;
        }
        model.addRow(row);
    }

    static boolean isUntouchedNewParameterRow(DefaultTableModel model, int row) {
        if (!isParamsModel(model)
                || Boolean.TRUE.equals(model.getValueAt(row, PARAM_EXISTING_ROW_MODEL_COLUMN))) {
            return false;
        }
        String description = nullableTableString(model, row, PARAM_DESCRIPTION_MODEL_COLUMN);
        String type = nullableTableString(model, row, PARAM_TYPE_MODEL_COLUMN);
        String source = nullableTableString(model, row, PARAM_SOURCE_MODEL_COLUMN);
        String style = nullableTableString(model, row, PARAM_STYLE_MODEL_COLUMN);
        String location = nullableTableString(model, row, PARAM_LOCATION_MODEL_COLUMN);
        String format = nullableTableString(model, row, PARAM_FORMAT_MODEL_COLUMN);
        String sourceMetadata = nullableTableString(model, row, PARAM_SOURCE_METADATA_MODEL_COLUMN);
        return tableString(model, row, PARAM_KEY_MODEL_COLUMN).isEmpty()
                && tableString(model, row, PARAM_VALUE_MODEL_COLUMN).isEmpty()
                && Boolean.TRUE.equals(model.getValueAt(row, PARAM_ENABLED_MODEL_COLUMN))
                && (description == null || description.isEmpty())
                && model.getValueAt(row, PARAM_RAW_KEY_MODEL_COLUMN) == null
                && model.getValueAt(row, PARAM_RAW_VALUE_MODEL_COLUMN) == null
                && !Boolean.TRUE.equals(model.getValueAt(row, PARAM_VALUE_PRESENT_MODEL_COLUMN))
                && !Boolean.TRUE.equals(model.getValueAt(row, PARAM_REQUIRED_MODEL_COLUMN))
                && (type == null || type.isEmpty())
                && (source == null || source.isEmpty() || "workbench".equals(source))
                && (style == null || style.isEmpty())
                && model.getValueAt(row, PARAM_EXPLODE_MODEL_COLUMN) == null
                && !Boolean.TRUE.equals(model.getValueAt(row, PARAM_ALLOW_RESERVED_MODEL_COLUMN))
                && (location == null || location.isEmpty() || "query".equals(location))
                && (format == null || format.isEmpty())
                && (sourceMetadata == null || sourceMetadata.isEmpty());
    }

    static boolean isUntouchedNewBodyRow(DefaultTableModel model, int row) {
        return isBodyModel(model)
                && !Boolean.TRUE.equals(model.getValueAt(row, BODY_EXISTING_ROW_MODEL_COLUMN))
                && tableString(model, row, BODY_KEY_MODEL_COLUMN).isEmpty()
                && tableString(model, row, BODY_VALUE_MODEL_COLUMN).isEmpty()
                && Boolean.TRUE.equals(model.getValueAt(row, BODY_ENABLED_MODEL_COLUMN))
                && "text".equals(nullableTableString(model, row, BODY_TYPE_MODEL_COLUMN))
                && tableString(model, row, BODY_FILE_PATH_MODEL_COLUMN).isEmpty()
                && !Boolean.TRUE.equals(model.getValueAt(row, BODY_FILE_UPLOAD_MODEL_COLUMN))
                && model.getValueAt(row, BODY_ORIGINAL_TYPE_MODEL_COLUMN) == null
                && model.getValueAt(row, BODY_ORIGINAL_FILE_PATH_MODEL_COLUMN) == null
                && !Boolean.TRUE.equals(model.getValueAt(row, BODY_ORIGINAL_FILE_UPLOAD_MODEL_COLUMN))
                && !Boolean.TRUE.equals(model.getValueAt(row, BODY_REQUIRED_MODEL_COLUMN))
                && nullableTableString(model, row, BODY_DESCRIPTION_MODEL_COLUMN) == null
                && nullableTableString(model, row, BODY_CONTENT_TYPE_MODEL_COLUMN) == null
                && nullableTableString(model, row, BODY_STYLE_MODEL_COLUMN) == null
                && nullableTableBoolean(model, row, BODY_EXPLODE_MODEL_COLUMN) == null
                && !Boolean.TRUE.equals(model.getValueAt(row, BODY_ALLOW_RESERVED_MODEL_COLUMN))
                && nullableTableString(model, row, BODY_SOURCE_MODEL_COLUMN) == null
                && nullableTableString(model, row, BODY_SOURCE_METADATA_MODEL_COLUMN) == null;
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
                && model.getColumnCount() == 17
                && "Style".equals(String.valueOf(model.getColumnName(PARAM_STYLE_MODEL_COLUMN)))
                && "Explode".equals(String.valueOf(model.getColumnName(PARAM_EXPLODE_MODEL_COLUMN)))
                && "Allow Reserved".equals(String.valueOf(model.getColumnName(PARAM_ALLOW_RESERVED_MODEL_COLUMN)))
                && "Existing Row".equals(String.valueOf(
                        model.getColumnName(PARAM_EXISTING_ROW_MODEL_COLUMN)))
                && "Location".equals(String.valueOf(
                        model.getColumnName(PARAM_LOCATION_MODEL_COLUMN)))
                && "Format".equals(String.valueOf(model.getColumnName(PARAM_FORMAT_MODEL_COLUMN)))
                && "Source Metadata".equals(String.valueOf(model.getColumnName(PARAM_SOURCE_METADATA_MODEL_COLUMN)));
    }

    private static boolean isBodyModel(DefaultTableModel model) {
        return model != null
                && model.getColumnCount() == 18
                && "File Upload".equals(String.valueOf(
                        model.getColumnName(BODY_FILE_UPLOAD_MODEL_COLUMN)))
                && "Original Type".equals(String.valueOf(
                        model.getColumnName(BODY_ORIGINAL_TYPE_MODEL_COLUMN)))
                && "Original File Path".equals(String.valueOf(
                        model.getColumnName(BODY_ORIGINAL_FILE_PATH_MODEL_COLUMN)))
                && "Original File Upload".equals(String.valueOf(
                        model.getColumnName(BODY_ORIGINAL_FILE_UPLOAD_MODEL_COLUMN)))
                && "Existing Row".equals(String.valueOf(
                        model.getColumnName(BODY_EXISTING_ROW_MODEL_COLUMN)))
                && "Required".equals(String.valueOf(model.getColumnName(BODY_REQUIRED_MODEL_COLUMN)))
                && "Source Metadata".equals(String.valueOf(model.getColumnName(BODY_SOURCE_METADATA_MODEL_COLUMN)));
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
                parameter.description,
                parameter.rawKey,
                parameter.rawValue,
                parameter.valuePresent,
                parameter.required,
                parameter.type,
                parameter.source,
                parameter.style,
                parameter.explode,
                parameter.allowReserved,
                Boolean.TRUE,
                RequestParameterSupport.normalizeLocation(parameter.location),
                parameter.format,
                OpenApiMetadataSupport.canonicalJson(OpenApiMetadataSupport.copy(parameter.sourceMetadata))
        });
    }

    private static Object[] bodyRow(ApiRequest.Body.FormField field) {
        String displayedType = field.type;
        if (displayedType == null || displayedType.isBlank()) {
            displayedType = field.fileUpload ? "file" : "text";
        }
        return new Object[]{
                field.key != null ? field.key : "",
                field.value != null ? field.value : "",
                !field.disabled,
                displayedType,
                field.filePath != null ? field.filePath : "",
                field.fileUpload,
                field.type,
                field.filePath,
                field.fileUpload,
                Boolean.TRUE,
                field.required,
                field.description,
                field.contentType,
                field.style,
                field.explode,
                field.allowReserved,
                field.source,
                OpenApiMetadataSupport.canonicalJson(OpenApiMetadataSupport.copy(field.sourceMetadata))
        };
    }

    private static boolean isUnchangedLegacyQueryView(ApiRequest currentRequest,
                                                       String editorUrl,
                                                       DefaultTableModel paramsModel) {
        if (currentRequest == null
                || RequestParameterSupport.hasQueryParameters(currentRequest.parameters)
                || (currentRequest.parameters != null && !currentRequest.parameters.isEmpty())
                || !Objects.equals(editorUrl, currentRequest.url)) {
            return false;
        }
        List<ApiRequest.Parameter> expected = RequestParameterSupport.parseQueryParameters(
                currentRequest.url,
                "legacy:url");
        List<ApiRequest.Parameter> actual = parametersFromTable(paramsModel);
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!equalParameterFields(expected.get(i), actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalParameterFields(ApiRequest.Parameter left, ApiRequest.Parameter right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.location, right.location)
                && Objects.equals(left.key, right.key)
                && Objects.equals(left.value, right.value)
                && Objects.equals(left.rawKey, right.rawKey)
                && Objects.equals(left.rawValue, right.rawValue)
                && left.valuePresent == right.valuePresent
                && left.disabled == right.disabled
                && left.required == right.required
                && Objects.equals(left.type, right.type)
                && Objects.equals(left.format, right.format)
                && Objects.equals(left.description, right.description)
                && Objects.equals(left.style, right.style)
                && Objects.equals(left.explode, right.explode)
                && left.allowReserved == right.allowReserved
                && Objects.equals(left.source, right.source)
                && Objects.equals(left.sourceMetadata, right.sourceMetadata);
    }

    private static List<ApiRequest.Parameter> parametersFromTable(DefaultTableModel model) {
        List<ApiRequest.Parameter> parameters = new ArrayList<>();
        boolean fullModel = isParamsModel(model);
        for (int row = 0; row < model.getRowCount(); row++) {
            String key = tableString(model, row, PARAM_KEY_MODEL_COLUMN);
            String value = tableString(model, row, PARAM_VALUE_MODEL_COLUMN);
            if (fullModel ? isUntouchedNewParameterRow(model, row) : key.trim().isEmpty()) {
                continue;
            }
            String location = fullModel
                    ? RequestParameterSupport.normalizeLocation(
                            nullableTableString(model, row, PARAM_LOCATION_MODEL_COLUMN))
                    : "query";
            ApiRequest.Parameter parameter = new ApiRequest.Parameter(location, key, value);
            if (fullModel) {
                Object enabledValue = model.getValueAt(row, PARAM_ENABLED_MODEL_COLUMN);
                parameter.disabled = Boolean.FALSE.equals(enabledValue);
                parameter.description = nullableTableString(model, row, PARAM_DESCRIPTION_MODEL_COLUMN);
                parameter.rawKey = nullableTableString(model, row, PARAM_RAW_KEY_MODEL_COLUMN);
                parameter.rawValue = nullableTableString(model, row, PARAM_RAW_VALUE_MODEL_COLUMN);
                Object valuePresentCell =
                        model.getValueAt(row, PARAM_VALUE_PRESENT_MODEL_COLUMN);

                parameter.valuePresent = valuePresentCell instanceof Boolean
                        ? Boolean.TRUE.equals(valuePresentCell)
                        : !value.isEmpty();
                parameter.required = Boolean.TRUE.equals(
                        model.getValueAt(row, PARAM_REQUIRED_MODEL_COLUMN));
                parameter.type = nullableTableString(model, row, PARAM_TYPE_MODEL_COLUMN);
                parameter.format = nullableTableString(model, row, PARAM_FORMAT_MODEL_COLUMN);
                parameter.source = nullableTableString(model, row, PARAM_SOURCE_MODEL_COLUMN);
                parameter.style = nullableTableString(model, row, PARAM_STYLE_MODEL_COLUMN);
                parameter.explode = nullableTableBoolean(model, row, PARAM_EXPLODE_MODEL_COLUMN);
                parameter.allowReserved = Boolean.TRUE.equals(
                        model.getValueAt(row, PARAM_ALLOW_RESERVED_MODEL_COLUMN));
                parameter.sourceMetadata = metadataFromCell(
                        nullableTableString(model, row, PARAM_SOURCE_METADATA_MODEL_COLUMN));
                if (enabledValue == null) {
                    parameter.rawKey = java.net.URLEncoder.encode(
                            key, java.nio.charset.StandardCharsets.UTF_8);
                    parameter.rawValue = java.net.URLEncoder.encode(
                            value, java.nio.charset.StandardCharsets.UTF_8);
                }
            } else {
                parameter.valuePresent = !value.isEmpty();
            }
            parameters.add(parameter);
        }
        return parameters;
    }

    private static List<ApiRequest.Body.FormField> formFieldsFromTable(DefaultTableModel model,
                                                                       boolean multipart) {
        List<ApiRequest.Body.FormField> fields = new ArrayList<>();
        boolean fullModel = isBodyModel(model);
        for (int row = 0; row < model.getRowCount(); row++) {
            String key = tableString(model, row, BODY_KEY_MODEL_COLUMN);
            if (fullModel ? isUntouchedNewBodyRow(model, row) : key.trim().isEmpty()) {
                continue;
            }
            String value = tableString(model, row, BODY_VALUE_MODEL_COLUMN);
            boolean disabled = fullModel
                    && Boolean.FALSE.equals(model.getValueAt(row, BODY_ENABLED_MODEL_COLUMN));
            String visibleType = nullableTableString(model, row, BODY_TYPE_MODEL_COLUMN);
            String visibleFilePathCell = tableString(model, row, BODY_FILE_PATH_MODEL_COLUMN);
            boolean hiddenFileUpload = Boolean.TRUE.equals(
                    model.getValueAt(row, BODY_FILE_UPLOAD_MODEL_COLUMN));
            String originalType = nullableTableString(model, row, BODY_ORIGINAL_TYPE_MODEL_COLUMN);
            String originalFilePath = nullableTableString(
                    model, row, BODY_ORIGINAL_FILE_PATH_MODEL_COLUMN);
            boolean originalFileUpload = Boolean.TRUE.equals(
                    model.getValueAt(row, BODY_ORIGINAL_FILE_UPLOAD_MODEL_COLUMN));
            boolean existingRow = Boolean.TRUE.equals(
                    model.getValueAt(row, BODY_EXISTING_ROW_MODEL_COLUMN));

            String originalDisplayedType = effectiveBodyDisplayType(originalType, originalFileUpload);
            String originalDisplayedFilePath = originalFilePath != null ? originalFilePath : "";
            boolean metadataChanged = !Objects.equals(visibleType, originalDisplayedType)
                    || !Objects.equals(visibleFilePathCell, originalDisplayedFilePath)
                    || hiddenFileUpload != originalFileUpload;

            ApiRequest.Body.FormField field = new ApiRequest.Body.FormField(key, value);
            field.disabled = disabled;
            if (fullModel) {
                field.required = Boolean.TRUE.equals(model.getValueAt(row, BODY_REQUIRED_MODEL_COLUMN));
                field.description = nullableTableString(model, row, BODY_DESCRIPTION_MODEL_COLUMN);
                field.contentType = nullableTableString(model, row, BODY_CONTENT_TYPE_MODEL_COLUMN);
                field.style = nullableTableString(model, row, BODY_STYLE_MODEL_COLUMN);
                field.explode = nullableTableBoolean(model, row, BODY_EXPLODE_MODEL_COLUMN);
                field.allowReserved = Boolean.TRUE.equals(model.getValueAt(row, BODY_ALLOW_RESERVED_MODEL_COLUMN));
                field.source = nullableTableString(model, row, BODY_SOURCE_MODEL_COLUMN);
                field.sourceMetadata = metadataFromCell(
                        nullableTableString(model, row, BODY_SOURCE_METADATA_MODEL_COLUMN));
            }
            if (existingRow && !metadataChanged) {
                field.type = originalType;
                field.filePath = originalFilePath;
                field.fileUpload = originalFileUpload;
            } else {
                String authoredFilePath = visibleFilePathCell.isEmpty() ? null : visibleFilePathCell;
                field.type = visibleType;
                field.filePath = authoredFilePath;
                if (multipart) {
                    field.fileUpload = hiddenFileUpload
                            || "file".equalsIgnoreCase(field.type)
                            || field.filePath != null;
                    if (field.fileUpload && (field.type == null || field.type.isBlank())) {
                        field.type = "file";
                    }
                } else {
                    field.fileUpload = hiddenFileUpload;
                }
            }
            fields.add(field);
        }
        return fields;
    }

    private static String effectiveBodyDisplayType(String originalType, boolean originalFileUpload) {
        if (originalType != null && !originalType.isBlank()) {
            return originalType;
        }
        return originalFileUpload ? "file" : "text";
    }

    private static String tableString(DefaultTableModel model, int row, int column) {
        if (model == null || column < 0 || column >= model.getColumnCount()) {
            return "";
        }
        Object value = model.getValueAt(row, column);
        return value != null ? String.valueOf(value) : "";
    }

    private static String nullableTableString(DefaultTableModel model, int row, int column) {
        if (model == null || column < 0 || column >= model.getColumnCount()) {
            return null;
        }
        Object value = model.getValueAt(row, column);
        return value != null ? String.valueOf(value) : null;
    }

    private static Boolean nullableTableBoolean(DefaultTableModel model, int row, int column) {
        if (model == null || column < 0 || column >= model.getColumnCount()) {
            return null;
        }
        Object value = model.getValueAt(row, column);
        return value instanceof Boolean ? (Boolean) value : null;
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

    private static List<ApiRequest.Variable> copyVariables(List<ApiRequest.Variable> variables) {
        List<ApiRequest.Variable> copy = new ArrayList<>();
        if (variables == null) {
            return copy;
        }
        for (ApiRequest.Variable variable : variables) {
            if (variable == null) {
                copy.add(null);
                continue;
            }
            ApiRequest.Variable item = new ApiRequest.Variable();
            item.key = variable.key;
            item.value = variable.value;
            item.type = variable.type;
            item.enabled = variable.enabled;
            copy.add(item);
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
        copy.required = field.required;
        copy.description = field.description;
        copy.contentType = field.contentType;
        copy.style = field.style;
        copy.explode = field.explode;
        copy.allowReserved = field.allowReserved;
        copy.source = field.source;
        copy.sourceMetadata = OpenApiMetadataSupport.copy(field.sourceMetadata);
        return copy;
    }

    private static Map<String, String> metadataFromCell(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : OpenApiMetadataSupport.parseObject(value).entrySet()) {
            if (entry.getValue() instanceof String text) result.put(entry.getKey(), text);
        }
        return result;
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
