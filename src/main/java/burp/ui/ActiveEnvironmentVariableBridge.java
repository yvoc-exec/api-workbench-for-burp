package burp.ui;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.EnvironmentProfile;
import burp.utils.RuntimeResolverFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Installs the Active Environment mutation semantics advertised by the
 * request editor's confirmed variable edit/create workflow.
 */
public final class ActiveEnvironmentVariableBridge {
    private ActiveEnvironmentVariableBridge() {
    }

    public static void install(ImporterPanel panel) {
        if (panel == null) {
            return;
        }
        RequestEditorPanel editor = panel.getRequestEditorForTests();
        if (editor == null) {
            return;
        }
        editor.setVariableActionBridge(new Bridge(panel, editor));
    }

    private static final class Bridge implements RequestEditorPanel.VariableActionBridge {
        private final ImporterPanel panel;
        private final RequestEditorPanel editor;

        private Bridge(ImporterPanel panel, RequestEditorPanel editor) {
            this.panel = panel;
            this.editor = editor;
        }

        @Override
        public RequestEditorPanel.VariableHoverInfo inspect(String key) {
            EnvironmentProfile active = activeEnvironmentSnapshot();
            ApiCollection collection = editor.getCurrentCollection();
            ApiRequest request = editor.getCurrentRequest();
            Map<String, String> runtimeOverlay = active == null
                    ? editor.getRuntimeVariablesSnapshot()
                    : Collections.emptyMap();
            RuntimeResolverFactory.ResolutionTrace trace = RuntimeResolverFactory.inspect(
                    collection,
                    request,
                    active,
                    runtimeOverlay,
                    key);

            RequestEditorPanel.VariableHoverInfo info = new RequestEditorPanel.VariableHoverInfo();
            info.key = key;
            info.resolved = trace.resolved;
            info.value = trace.value;
            info.scope = trace.scope != null ? trace.scope : (trace.resolved ? "resolved" : "not found");
            info.source = trace.source != null
                    ? trace.source
                    : (active != null ? "Active Environment" : "No Active Environment selected");
            info.shadowedSource = trace.shadowedSource;
            info.shadowedValue = trace.shadowedValue;
            info.activeEnvironmentName = trace.activeEnvironmentName != null
                    ? trace.activeEnvironmentName
                    : (active != null ? active.displayName() : null);
            info.canEdit = active != null;
            info.canCreate = active != null;
            info.message = trace.message != null
                    ? trace.message
                    : (active != null
                    ? "No value found. Create target: Active Environment (persisted variable)."
                    : "No Active Environment selected. Select or import an environment to edit or create persisted variables.");
            return info;
        }

        @Override
        public boolean hasActiveEnvironment() {
            return activeEnvironmentSnapshot() != null;
        }

        @Override
        public String activeEnvironmentName() {
            EnvironmentProfile active = activeEnvironmentSnapshot();
            return active != null ? active.displayName() : null;
        }

        @Override
        public boolean updateActiveEnvironment(
                String key,
                String value,
                boolean createIfMissing,
                boolean persist) {
            String activeId = panel.getActiveEnvironmentId();
            if (key == null || key.isBlank() || activeId == null || activeId.isBlank()) {
                return false;
            }

            List<EnvironmentProfile> profiles = panel.getEnvironmentProfilesSnapshot();
            EnvironmentProfile active = findEnvironment(profiles, activeId);
            if (active == null) {
                return false;
            }

            active.ensureDefaults();
            String normalizedValue = value != null ? value : "";
            if (persist) {
                active.variables.put(key, normalizedValue);
                active.runtimeVariables.remove(key);
            } else {
                active.runtimeVariables.put(key, normalizedValue);
            }

            panel.replaceEnvironmentProfiles(profiles);
            panel.setActiveEnvironmentId(activeId);
            return true;
        }

        @Override
        public void refreshEnvironmentUi() {
            String activeId = panel.getActiveEnvironmentId();
            if (activeId != null) {
                panel.setActiveEnvironmentId(activeId);
            }
        }

        private EnvironmentProfile activeEnvironmentSnapshot() {
            return findEnvironment(panel.getEnvironmentProfilesSnapshot(), panel.getActiveEnvironmentId());
        }

        private static EnvironmentProfile findEnvironment(List<EnvironmentProfile> profiles, String id) {
            if (profiles == null || id == null) {
                return null;
            }
            for (EnvironmentProfile profile : profiles) {
                if (profile != null && Objects.equals(id, profile.id)) {
                    return profile;
                }
            }
            return null;
        }
    }
}
