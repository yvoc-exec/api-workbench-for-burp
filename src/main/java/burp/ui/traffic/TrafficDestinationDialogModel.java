package burp.ui.traffic;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.ui.tree.RequestTreeNamingPolicy;
import burp.ui.tree.RequestTreePathService;
import burp.utils.RequestPathResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class TrafficDestinationDialogModel {
    private final List<ApiCollection> existingCollections;
    private final List<ApiRequest> convertedRequests;
    private final boolean responseAvailable;
    private final boolean queueAction;

    private ApiCollection destinationCollection;
    private boolean createNewCollection;
    private String newCollectionName = "Burp Traffic";
    private String destinationFolder = "";
    private boolean preserveExactTransport = true;
    private boolean captureResponses;
    private boolean queueInRunner;
    private boolean cancelled;
    private final List<String> generatedNames = new ArrayList<>();
    private final List<String> validationErrors = new ArrayList<>();

    public TrafficDestinationDialogModel(List<ApiCollection> existingCollections,
                                         List<ApiRequest> convertedRequests,
                                         boolean responseAvailable,
                                         boolean queueAction) {
        this.existingCollections = existingCollections != null ? new ArrayList<>(existingCollections) : new ArrayList<>();
        this.convertedRequests = convertedRequests != null ? new ArrayList<>(convertedRequests) : new ArrayList<>();
        this.responseAvailable = responseAvailable;
        this.queueAction = queueAction;
        this.destinationCollection = this.existingCollections.isEmpty() ? null : this.existingCollections.get(0);
        this.createNewCollection = this.existingCollections.isEmpty();
        this.queueInRunner = queueAction;
        this.captureResponses = responseAvailable;
        regenerateNames();
        validate();
    }

    public List<ApiCollection> existingCollections() {
        return Collections.unmodifiableList(existingCollections);
    }

    public List<ApiRequest> convertedRequests() {
        return Collections.unmodifiableList(convertedRequests);
    }

    public int selectedCount() {
        return convertedRequests.size();
    }

    public boolean responseAvailable() {
        return responseAvailable;
    }

    public boolean queueAction() {
        return queueAction;
    }

    public ApiCollection destinationCollection() {
        return destinationCollection;
    }

    public void setDestinationCollection(ApiCollection destinationCollection) {
        this.destinationCollection = destinationCollection;
        this.createNewCollection = destinationCollection == null;
        regenerateNames();
        validate();
    }

    public boolean createNewCollection() {
        return createNewCollection;
    }

    public void setCreateNewCollection(boolean createNewCollection) {
        this.createNewCollection = createNewCollection;
        if (createNewCollection) {
            this.destinationCollection = null;
            this.destinationFolder = "";
        }
        regenerateNames();
        validate();
    }

    public String newCollectionName() {
        return newCollectionName;
    }

    public void setNewCollectionName(String newCollectionName) {
        this.newCollectionName = RequestTreeNamingPolicy.normalizeTreeLabel(newCollectionName);
        validate();
    }

    public String destinationFolder() {
        return destinationFolder;
    }

    public void setDestinationFolder(String destinationFolder) {
        this.destinationFolder = RequestTreePathService.normalizeFolderPath(destinationFolder);
        regenerateNames();
        validate();
    }

    public boolean preserveExactTransport() {
        return preserveExactTransport;
    }

    public void setPreserveExactTransport(boolean preserveExactTransport) {
        if (hasBinaryRequest()) {
            this.preserveExactTransport = true;
        } else {
            this.preserveExactTransport = preserveExactTransport;
        }
        validate();
    }

    public boolean captureResponses() {
        return captureResponses;
    }

    public void setCaptureResponses(boolean captureResponses) {
        this.captureResponses = responseAvailable && captureResponses;
    }

    public boolean queueInRunner() {
        return queueInRunner;
    }

    public void setQueueInRunner(boolean queueInRunner) {
        this.queueInRunner = queueInRunner;
    }

    public List<String> generatedNames() {
        return List.copyOf(generatedNames);
    }

    public void setGeneratedName(int index, String value) {
        if (index < 0 || index >= generatedNames.size()) {
            return;
        }
        generatedNames.set(index, RequestTreeNamingPolicy.normalizeTreeLabel(value));
        makeGeneratedNamesUnique();
        validate();
    }

    public boolean hasBinaryRequest() {
        for (ApiRequest request : convertedRequests) {
            if (request != null && request.exactHttpRequest != null && request.exactHttpRequest.binaryBody) {
                return true;
            }
        }
        return false;
    }

    public List<String> validationErrors() {
        return List.copyOf(validationErrors);
    }

    public boolean isValid() {
        return !cancelled && validationErrors.isEmpty();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
    }

    public void confirm() {
        cancelled = false;
        validate();
    }

    public String effectiveCollectionName() {
        if (createNewCollection) {
            return newCollectionName;
        }
        return destinationCollection != null ? destinationCollection.name : "";
    }

    private void regenerateNames() {
        generatedNames.clear();
        Set<String> used = existingSiblingNames();
        for (ApiRequest request : convertedRequests) {
            String base = RequestTreeNamingPolicy.normalizeTreeLabel(request != null ? request.name : null);
            if (base.isBlank()) {
                base = "Imported Request";
            }
            String candidate = uniqueWithParenthesizedSuffix(base, used);
            generatedNames.add(candidate);
            used.add(key(candidate));
        }
    }

    private void makeGeneratedNamesUnique() {
        Set<String> used = existingSiblingNames();
        for (int i = 0; i < generatedNames.size(); i++) {
            String base = RequestTreeNamingPolicy.normalizeTreeLabel(generatedNames.get(i));
            if (base.isBlank()) {
                base = "Imported Request";
            }
            String candidate = uniqueWithParenthesizedSuffix(base, used);
            generatedNames.set(i, candidate);
            used.add(key(candidate));
        }
    }

    private Set<String> existingSiblingNames() {
        LinkedHashSet<String> used = new LinkedHashSet<>();
        ApiCollection collection = createNewCollection ? null : destinationCollection;
        if (collection == null) {
            return used;
        }
        String parent = RequestTreePathService.normalizeFolderPath(destinationFolder);
        if (collection.folderPaths != null) {
            for (String folder : collection.folderPaths) {
                String normalized = RequestTreePathService.normalizeFolderPath(folder);
                if (Objects.equals(RequestTreePathService.getParentFolderPath(normalized), parent)) {
                    used.add(key(RequestTreePathService.leafFolderName(normalized)));
                }
            }
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request != null && Objects.equals(RequestPathResolver.getRequestFolderPath(collection, request), parent)) {
                    used.add(key(request.name));
                }
            }
        }
        return used;
    }

    private String uniqueWithParenthesizedSuffix(String base, Set<String> used) {
        if (!used.contains(key(base))) {
            return base;
        }
        int suffix = 2;
        while (used.contains(key(base + " (" + suffix + ")"))) {
            suffix++;
        }
        return base + " (" + suffix + ")";
    }

    private void validate() {
        validationErrors.clear();
        if (convertedRequests.isEmpty()) {
            validationErrors.add("At least one request is required.");
        }
        if (createNewCollection) {
            if (newCollectionName == null || newCollectionName.isBlank()) {
                validationErrors.add("A new collection name is required.");
            }
        } else if (destinationCollection == null) {
            validationErrors.add("Select a destination collection.");
        }
        String normalizedFolder = RequestTreePathService.normalizeFolderPath(destinationFolder);
        if (!createNewCollection && !normalizedFolder.isBlank() && !folderExists(destinationCollection, normalizedFolder)) {
            validationErrors.add("The selected destination folder does not exist.");
        }
        if (generatedNames.size() != convertedRequests.size()) {
            validationErrors.add("Every request must have a generated name.");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : generatedNames) {
            if (name == null || name.isBlank()) {
                validationErrors.add("Request names cannot be blank.");
                break;
            }
            if (!names.add(key(name))) {
                validationErrors.add("Request names must be unique within the destination.");
                break;
            }
        }
        if (hasBinaryRequest() && !preserveExactTransport) {
            validationErrors.add("Binary requests require exact transport preservation.");
        }
    }

    private boolean folderExists(ApiCollection collection, String folder) {
        if (folder == null || folder.isBlank()) {
            return true;
        }
        if (collection == null) {
            return false;
        }
        if (collection.folderPaths != null) {
            for (String candidate : collection.folderPaths) {
                if (Objects.equals(RequestTreePathService.normalizeFolderPath(candidate), folder)) {
                    return true;
                }
            }
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request != null && Objects.equals(RequestPathResolver.getRequestFolderPath(collection, request), folder)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String key(String value) {
        return RequestTreeNamingPolicy.normalizeTreeLabel(value).toLowerCase(Locale.ROOT);
    }
}
