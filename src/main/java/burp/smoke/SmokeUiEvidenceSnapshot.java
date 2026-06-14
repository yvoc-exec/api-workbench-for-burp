package burp.smoke;

import burp.models.WorkspaceState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable UI evidence snapshot captured from the smoke runtime.
 */
public final class SmokeUiEvidenceSnapshot {
    public String label;
    public String capturedAt;
    public String selectedTopLevelTab;
    public WorkspaceState workspaceState;
    public RequestTreeState requestTree = new RequestTreeState();
    public EnvironmentState environment = new EnvironmentState();
    public RunnerState runner = new RunnerState();
    public RequestEditorState requestEditor = new RequestEditorState();
    public LogState logs = new LogState();
    public final List<String> notes = new ArrayList<>();

    public static final class RequestTreeState {
        public boolean exists;
        public boolean showing;
        public boolean valid;
        public boolean displayable;
        public boolean visible;
        public boolean rootVisible;
        public boolean showsRootHandles;
        public boolean scrollsOnExpand;
        public int rowCount;
        public int selectedRow = -1;
        public String selectedPath;
        public String selectedNodeType;
        public String selectedCollectionName;
        public String selectedFolderPath;
        public String selectedRequestId;
        public String selectedRequestName;
        public String selectedRequestPath;
        public String selectedRequestSourceCollection;
        public String viewportPosition;
        public String viewportExtent;
        public final List<String> expandedTreePathKeys = new ArrayList<>();
        public final List<String> collapsedTopLevelCollections = new ArrayList<>();
        public final List<String> checkedRequestIdentityKeys = new ArrayList<>();
        public final List<String> checkedRequestKeys = new ArrayList<>();
    }

    public static final class EnvironmentState {
        public int count;
        public String activeEnvironmentId;
        public String activeEnvironmentName;
        public String selectedComboLabel;
        public String workbenchComboLabel;
        public String oauth2ComboLabel;
        public final List<EnvironmentProfileState> profiles = new ArrayList<>();
    }

    public static final class EnvironmentProfileState {
        public String id;
        public String name;
        public String sourceFormat;
        public String sourceFileName;
        public int variableCount;
        public final List<String> variableKeys = new ArrayList<>();
        public final Map<String, String> variables = new LinkedHashMap<>();
        public boolean active;
    }

    public static final class RunnerState {
        public int queueSize;
        public final List<String> queueRequestIdentityKeys = new ArrayList<>();
        public final List<String> queueRequestNames = new ArrayList<>();
        public int previewCount;
        public final List<RunnerPreviewRowState> previewRows = new ArrayList<>();
        public int resultCount;
        public final List<RunnerResultState> resultRows = new ArrayList<>();
        public boolean running;
        public boolean startEnabled;
        public boolean cancelEnabled;
        public boolean pauseEnabled;
        public boolean resumeEnabled;
        public boolean stepEnabled;
        public int selectedQueueIndex = -1;
        public String selectedQueueRequestIdentityKey;
        public String selectedQueueRequestId;
        public String selectedQueueRequestName;
        public String selectedQueueRequestMethod;
    }

    public static final class RunnerPreviewRowState {
        public int order;
        public String collectionName;
        public String requestName;
        public String method;
        public String urlPreview;
        public final List<String> unresolvedVariables = new ArrayList<>();
        public String authStatus;
    }

    public static final class RunnerResultState {
        public String requestName;
        public String requestId;
        public boolean success;
        public int statusCode;
        public long responseTimeMs;
        public int responseSize;
        public int responseBodyLength;
        public String errorMessage;
        public int extractedVariableCount;
    }

    public static final class RequestEditorState {
        public String currentCollectionName;
        public String currentRequestId;
        public String currentRequestName;
        public String method;
        public String url;
        public int headerCount;
        public String bodyMode;
        public String authMode;
        public boolean sendEnabled;
        public String sendModeLabel;
        public int tabCount;
        public String selectedTabTitle;
    }

    public static final class LogState {
        public int importLogLineCount;
        public final List<String> importLogTail = new ArrayList<>();
        public int runnerLogLineCount;
        public final List<String> runnerLogTail = new ArrayList<>();
        public int diagnosticsLogLineCount;
        public final List<String> diagnosticsLogTail = new ArrayList<>();
    }
}
