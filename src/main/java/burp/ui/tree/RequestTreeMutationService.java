package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.AuthInheritanceResolver;
import burp.utils.RequestPathResolver;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Model-only mutations for request-tree create/duplicate/rename/delete flows.
 */
public final class RequestTreeMutationService {

    public ApiCollection createCollection(List<ApiCollection> loadedCollections) {
        ApiCollection collection = new ApiCollection();
        collection.name = RequestTreeNamingPolicy.uniqueCollectionName(loadedCollections, "Untitled Collection");
        collection.requests = new ArrayList<>();
        collection.folderPaths = new ArrayList<>();
        collection.variables = new ArrayList<>();
        collection.folderVars = new LinkedHashMap<>();
        collection.environment = new LinkedHashMap<>();
        collection.folderAuthModes = new LinkedHashMap<>();
        collection.folderAuth = new LinkedHashMap<>();
        collection.runtimeVars = new LinkedHashMap<>();
        collection.runtimeOAuth2 = new LinkedHashMap<>();
        if (loadedCollections != null) {
            loadedCollections.add(collection);
        }
        return collection;
    }

    public String createFolder(ApiCollection collection, String parentFolderPath) {
        if (collection == null) {
            return "";
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        String folderName = RequestTreeNamingPolicy.uniqueChildName(collection, normalizedParent, "Untitled Folder");
        String folderPath = RequestTreePathService.joinFolderPath(normalizedParent, folderName);
        addCollectionFolderPath(collection, folderPath, normalizedParent, null);
        return folderPath;
    }

    public ApiRequest createBlankManualRequest(ApiCollection collection, String parentFolderPath) {
        if (collection == null) {
            return null;
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        String requestName = RequestTreeNamingPolicy.uniqueChildName(collection, normalizedParent, "Untitled Request");
        ApiRequest request = new ApiRequest();
        request.id = UUID.randomUUID().toString();
        request.name = RequestTreeNamingPolicy.normalizeTreeLabel(requestName);
        request.path = normalizedParent;
        request.sourceCollection = collection.name;
        request.method = "GET";
        request.url = "";
        request.description = "";
        request.headers = new ArrayList<>();
        request.body = null;
        request.variables = new ArrayList<>();
        request.preRequestScripts = new ArrayList<>();
        request.postResponseScripts = new ArrayList<>();
        request.editorMaterialized = false;
        request.buildMode = ApiRequest.BuildMode.AUTO_COMPATIBLE;
        request.suppressedAutoHeaders = new LinkedHashSet<>();
        request.authOverrideMode = "inherit";
        request.explicitAuth = null;
        request.auth = null;
        request.authInherited = false;
        request.authExplicitlyDisabled = false;
        request.authSource = "none";
        insertRequestForParentPath(collection, request, normalizedParent, null);
        AuthInheritanceResolver.resolveRequestAuth(collection, request);
        return request;
    }

    public ApiCollection duplicateCollection(List<ApiCollection> loadedCollections, ApiCollection source) {
        if (source == null) {
            return null;
        }
        String duplicateName = RequestTreeNamingPolicy.uniqueCollectionCopyName(loadedCollections, source.name);
        ApiCollection copy = copyCollectionForDuplicate(source, duplicateName);
        if (loadedCollections != null) {
            int insertIndex = loadedCollections.indexOf(source);
            if (insertIndex < 0 || insertIndex >= loadedCollections.size()) {
                loadedCollections.add(copy);
            } else {
                loadedCollections.add(insertIndex + 1, copy);
            }
        }
        return copy;
    }

    public String duplicateFolder(ApiCollection collection, String sourceFolderPath) {
        if (collection == null) {
            return "";
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourceFolderPath);
        if (normalizedSource.isBlank()) {
            return "";
        }
        String parentPath = RequestTreePathService.getParentFolderPath(normalizedSource);
        String sourceLeaf = RequestTreePathService.leafFolderName(normalizedSource);
        String duplicateLeaf = RequestTreeNamingPolicy.uniqueChildCopyName(collection, parentPath, sourceLeaf);
        String targetPrefix = RequestTreePathService.joinFolderPath(parentPath, duplicateLeaf);
        copyFolderSubtree(collection, normalizedSource, targetPrefix);
        return targetPrefix;
    }

    public ApiRequest duplicateRequest(ApiCollection collection, ApiRequest sourceRequest) {
        if (collection == null || sourceRequest == null) {
            return null;
        }
        String parentFolderPath = RequestPathResolver.getRequestFolderPath(collection, sourceRequest);
        String duplicateName = RequestTreeNamingPolicy.uniqueChildCopyName(collection, parentFolderPath, sourceRequest.name);
        ApiRequest duplicate = copyRequestForDuplicate(sourceRequest, collection.name, duplicateName, parentFolderPath);
        insertRequestForParentPath(collection, duplicate, parentFolderPath, sourceRequest);
        AuthInheritanceResolver.resolveRequestAuth(collection, duplicate);
        return duplicate;
    }

    public ApiRequest moveRequest(ApiCollection sourceCollection,
                                  ApiRequest request,
                                  ApiCollection targetCollection,
                                  String targetFolderPath,
                                  int targetIndex) {
        if (sourceCollection == null || request == null || targetCollection == null) {
            return null;
        }

        String normalizedSourceParent = RequestPathResolver.getRequestFolderPath(sourceCollection, request);
        String normalizedTargetParent = RequestTreePathService.normalizeFolderPath(targetFolderPath);
        String requestName = RequestTreeNamingPolicy.normalizeTreeLabel(request.name);
        if (requestName.isBlank()) {
            return null;
        }

        if (sourceCollection == targetCollection && Objects.equals(normalizedSourceParent, normalizedTargetParent)) {
            int sourceSiblingIndex = findRequestSiblingIndex(sourceCollection, request, normalizedSourceParent);
            if (sourceSiblingIndex == targetIndex) {
                return request;
            }
        }

        int sourceSiblingIndex = findRequestSiblingIndex(sourceCollection, request, normalizedSourceParent);
        if (hasSiblingFolderOrRequestNameConflict(targetCollection, normalizedTargetParent, requestName, request)) {
            return null;
        }

        if (sourceCollection.requests != null) {
            sourceCollection.requests.remove(request);
        }

        request.path = normalizedTargetParent;
        request.sourceCollection = targetCollection.name;

        if (sourceCollection == targetCollection && Objects.equals(normalizedSourceParent, normalizedTargetParent)) {
            if (sourceSiblingIndex >= 0 && sourceSiblingIndex < targetIndex) {
                targetIndex--;
            }
        }

        int insertIndex = findRequestInsertionIndex(targetCollection, normalizedTargetParent, targetIndex, request);
        if (targetCollection.requests == null) {
            targetCollection.requests = new ArrayList<>();
        }
        if (insertIndex < 0 || insertIndex > targetCollection.requests.size()) {
            targetCollection.requests.add(request);
        } else {
            targetCollection.requests.add(insertIndex, request);
        }

        AuthInheritanceResolver.recomputeCollectionAuth(sourceCollection);
        if (sourceCollection != targetCollection) {
            AuthInheritanceResolver.recomputeCollectionAuth(targetCollection);
        }
        return request;
    }

    public List<ApiRequest> moveFolder(ApiCollection sourceCollection,
                                       String sourceFolderPath,
                                       ApiCollection targetCollection,
                                       String targetFolderPath,
                                       int targetIndex) {
        List<ApiRequest> movedRequests = new ArrayList<>();
        if (sourceCollection == null || targetCollection == null) {
            return null;
        }

        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourceFolderPath);
        String normalizedTargetParent = RequestTreePathService.normalizeFolderPath(targetFolderPath);
        if (normalizedSource.isBlank()) {
            return null;
        }
        if (normalizedTargetParent.startsWith(normalizedSource + "/") || Objects.equals(normalizedTargetParent, normalizedSource)) {
            return null;
        }

        String sourceLeaf = RequestTreePathService.leafFolderName(normalizedSource);
        String targetPrefix = RequestTreePathService.joinFolderPath(normalizedTargetParent, sourceLeaf);

        List<String> sourceSubtreePaths = collectFolderSubtreePaths(sourceCollection, normalizedSource);
        List<ApiRequest> sourceSubtreeRequests = collectRequestSubtreeRequests(sourceCollection, normalizedSource);
        int sourceSiblingIndex = findFolderSiblingIndex(sourceCollection, normalizedSource);
        Set<String> sourceSubtreePathSet = new LinkedHashSet<>();
        for (String path : sourceSubtreePaths) {
            if (path != null && !path.isBlank()) {
                sourceSubtreePathSet.add(RequestTreePathService.normalizeFolderPath(path));
            }
        }
        Set<ApiRequest> sourceSubtreeRequestSet = new LinkedHashSet<>(sourceSubtreeRequests);
        Map<ApiRequest, String> requestOriginalFolderPaths = new IdentityHashMap<>();
        if (sourceCollection.requests != null) {
            for (ApiRequest request : sourceSubtreeRequests) {
                if (request != null) {
                    requestOriginalFolderPaths.put(request, RequestPathResolver.getRequestFolderPath(sourceCollection, request));
                }
            }
        }
        Map<String, String> movedFolderAuthModes = new LinkedHashMap<>();
        Map<String, ApiRequest.Auth> movedFolderAuth = new LinkedHashMap<>();
        Map<String, Map<String, String>> movedFolderVars = new LinkedHashMap<>();
        if (sourceCollection.folderAuthModes != null && !sourceCollection.folderAuthModes.isEmpty()) {
            for (Map.Entry<String, String> entry : sourceCollection.folderAuthModes.entrySet()) {
                String normalized = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (!sourceSubtreePathSet.contains(normalized)) {
                    continue;
                }
                String rewritten = RequestTreePathService.rewriteFolderPathPrefix(normalized, normalizedSource, targetPrefix);
                if (!rewritten.isBlank()) {
                    movedFolderAuthModes.put(rewritten, entry.getValue());
                }
            }
        }
        if (sourceCollection.folderAuth != null && !sourceCollection.folderAuth.isEmpty()) {
            for (Map.Entry<String, ApiRequest.Auth> entry : sourceCollection.folderAuth.entrySet()) {
                String normalized = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (!sourceSubtreePathSet.contains(normalized)) {
                    continue;
                }
                String rewritten = RequestTreePathService.rewriteFolderPathPrefix(normalized, normalizedSource, targetPrefix);
                if (!rewritten.isBlank()) {
                    movedFolderAuth.put(rewritten, AuthInheritanceResolver.copyAuth(entry.getValue()));
                }
            }
        }
        if (sourceCollection.folderVars != null && !sourceCollection.folderVars.isEmpty()) {
            for (Map.Entry<String, Map<String, String>> entry : sourceCollection.folderVars.entrySet()) {
                String normalized = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (!sourceSubtreePathSet.contains(normalized)) {
                    continue;
                }
                String rewritten = RequestTreePathService.rewriteFolderPathPrefix(normalized, normalizedSource, targetPrefix);
                if (!rewritten.isBlank()) {
                    movedFolderVars.put(rewritten, entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
                }
            }
        }

        if (hasFolderSubtreeConflict(targetCollection,
                sourceSubtreePaths,
                sourceSubtreePathSet,
                sourceSubtreeRequestSet,
                normalizedSource,
                targetPrefix)) {
            return null;
        }

        if (sourceCollection == targetCollection && Objects.equals(RequestTreePathService.getParentFolderPath(normalizedSource), normalizedTargetParent)) {
            if (sourceSiblingIndex >= 0 && sourceSiblingIndex < targetIndex) {
                targetIndex--;
            }
        }

        if (sourceCollection.folderPaths != null) {
            sourceCollection.folderPaths.removeIf(path -> RequestTreePathService.isFolderPathInSubtree(path, normalizedSource));
            sourceCollection.folderPaths = normalizeFolderPaths(sourceCollection.folderPaths);
        }
        if (sourceCollection.requests != null) {
            sourceCollection.requests.removeIf(sourceSubtreeRequestSet::contains);
        }
        if (sourceCollection.folderAuthModes != null) {
            sourceCollection.folderAuthModes.keySet().removeIf(key -> RequestTreePathService.isFolderPathInSubtree(key, normalizedSource));
        }
        if (sourceCollection.folderAuth != null) {
            sourceCollection.folderAuth.keySet().removeIf(key -> RequestTreePathService.isFolderPathInSubtree(key, normalizedSource));
        }
        if (sourceCollection.folderVars != null) {
            sourceCollection.folderVars.keySet().removeIf(key -> RequestTreePathService.isFolderPathInSubtree(key, normalizedSource));
        }

        List<String> rewrittenPaths = new ArrayList<>();
        for (String path : sourceSubtreePaths) {
            String rewritten = RequestTreePathService.rewriteFolderPathPrefix(path, normalizedSource, targetPrefix);
            if (!rewritten.isBlank()) {
                rewrittenPaths.add(rewritten);
            }
        }

        int folderInsertIndex = findFolderInsertionIndex(targetCollection, normalizedTargetParent, targetIndex);
        insertFolderPaths(targetCollection, rewrittenPaths, folderInsertIndex);

        if (targetCollection.folderAuthModes == null) {
            targetCollection.folderAuthModes = new LinkedHashMap<>();
        }
        if (targetCollection.folderAuth == null) {
            targetCollection.folderAuth = new LinkedHashMap<>();
        }
        if (targetCollection.folderVars == null) {
            targetCollection.folderVars = new LinkedHashMap<>();
        }
        targetCollection.folderAuthModes.putAll(movedFolderAuthModes);
        targetCollection.folderAuth.putAll(movedFolderAuth);
        targetCollection.folderVars.putAll(movedFolderVars);

        for (ApiRequest request : sourceSubtreeRequests) {
            if (request == null) {
                continue;
            }
            String requestFolderPath = requestOriginalFolderPaths.getOrDefault(request,
                    RequestPathResolver.getRequestFolderPath(sourceCollection, request));
            request.path = RequestTreePathService.rewriteFolderPathPrefix(requestFolderPath, normalizedSource, targetPrefix);
            if (request.path == null) {
                request.path = "";
            }
            request.sourceCollection = targetCollection.name;
            movedRequests.add(request);
        }

        int requestInsertIndex = findRequestInsertionIndexAfterSubtree(targetCollection, targetPrefix);
        if (targetCollection.requests == null) {
            targetCollection.requests = new ArrayList<>();
        }
        if (requestInsertIndex < 0 || requestInsertIndex > targetCollection.requests.size()) {
            targetCollection.requests.addAll(movedRequests);
        } else {
            targetCollection.requests.addAll(requestInsertIndex, movedRequests);
        }

        AuthInheritanceResolver.recomputeCollectionAuth(sourceCollection);
        if (sourceCollection != targetCollection) {
            AuthInheritanceResolver.recomputeCollectionAuth(targetCollection);
        }
        return movedRequests;
    }

    public String renameCollection(ApiCollection collection, String newName) {
        if (collection == null) {
            return null;
        }
        String normalizedName = RequestTreeNamingPolicy.normalizeTreeLabel(newName);
        if (normalizedName.isBlank()) {
            return collection.name;
        }
        if (Objects.equals(collection.name, normalizedName)) {
            return normalizedName;
        }
        collection.name = normalizedName;
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request != null) {
                    request.sourceCollection = normalizedName;
                }
            }
        }
        AuthInheritanceResolver.recomputeCollectionAuth(collection);
        return normalizedName;
    }

    public String renameFolder(ApiCollection collection, String oldFolderPath, String newLeafName) {
        if (collection == null) {
            return RequestTreePathService.normalizeFolderPath(oldFolderPath);
        }
        String normalizedOldPath = RequestTreePathService.normalizeFolderPath(oldFolderPath);
        String normalizedLeaf = RequestTreeNamingPolicy.normalizeTreeLabel(newLeafName);
        if (normalizedOldPath.isBlank() || normalizedLeaf.isBlank()) {
            return normalizedOldPath;
        }
        if (normalizedLeaf.indexOf('/') >= 0 || normalizedLeaf.indexOf('\\') >= 0) {
            return normalizedOldPath;
        }
        String parentPath = RequestTreePathService.getParentFolderPath(normalizedOldPath);
        String newPath = RequestTreePathService.joinFolderPath(parentPath, normalizedLeaf);
        if (Objects.equals(normalizedOldPath, newPath)) {
            return newPath;
        }
        rewriteCollectionFolderMetadata(collection, normalizedOldPath, newPath);
        return newPath;
    }

    public String renameRequest(ApiCollection collection, ApiRequest request, String newName) {
        if (collection == null || request == null) {
            return null;
        }
        String normalizedName = RequestTreeNamingPolicy.normalizeTreeLabel(newName);
        if (normalizedName.isBlank()) {
            return request.name;
        }
        if (Objects.equals(request.name, normalizedName)) {
            return normalizedName;
        }
        String parentFolderPath = RequestPathResolver.getRequestFolderPath(collection, request);
        request.name = normalizedName;
        request.path = parentFolderPath;
        AuthInheritanceResolver.resolveRequestAuth(collection, request);
        return normalizedName;
    }

    public List<ApiRequest> removeFolderSubtree(ApiCollection collection, String sourceFolderPath) {
        List<ApiRequest> removedRequests = new ArrayList<>();
        if (collection == null) {
            return removedRequests;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourceFolderPath);
        if (normalizedSource.isBlank()) {
            return removedRequests;
        }

        if (collection.folderPaths != null) {
            collection.folderPaths.removeIf(path -> RequestTreePathService.isFolderPathInSubtree(path, normalizedSource));
            collection.folderPaths = normalizeFolderPaths(collection.folderPaths);
        }
        if (collection.requests != null) {
            List<ApiRequest> remaining = new ArrayList<>();
            for (ApiRequest request : collection.requests) {
                if (request != null && isRequestInFolderSubtree(collection, request, normalizedSource)) {
                    removedRequests.add(request);
                } else {
                    remaining.add(request);
                }
            }
            collection.requests = remaining;
        }
        if (collection.folderAuthModes != null) {
            collection.folderAuthModes.keySet().removeIf(key -> RequestTreePathService.isFolderPathInSubtree(key, normalizedSource));
        }
        if (collection.folderAuth != null) {
            collection.folderAuth.keySet().removeIf(key -> RequestTreePathService.isFolderPathInSubtree(key, normalizedSource));
        }
        if (collection.folderVars != null) {
            collection.folderVars.keySet().removeIf(key -> RequestTreePathService.isFolderPathInSubtree(key, normalizedSource));
        }
        AuthInheritanceResolver.recomputeCollectionAuth(collection);
        return removedRequests;
    }

    public List<ApiRequest> removeRequest(ApiCollection collection, ApiRequest request) {
        List<ApiRequest> removed = new ArrayList<>();
        if (collection == null || request == null || collection.requests == null) {
            return removed;
        }
        if (collection.requests.remove(request)) {
            removed.add(request);
        }
        return removed;
    }

    private static List<String> collectFolderSubtreePaths(ApiCollection collection, String sourcePrefix) {
        List<String> subtree = new ArrayList<>();
        if (collection == null) {
            return subtree;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        if (normalizedSource.isBlank() || collection.folderPaths == null) {
            return subtree;
        }
        for (String folderPath : collection.folderPaths) {
            String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
            if (RequestTreePathService.isFolderPathInSubtree(normalized, normalizedSource)) {
                subtree.add(normalized);
            }
        }
        return subtree;
    }

    private static List<ApiRequest> collectRequestSubtreeRequests(ApiCollection collection, String sourcePrefix) {
        List<ApiRequest> subtree = new ArrayList<>();
        if (collection == null || collection.requests == null) {
            return subtree;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        if (normalizedSource.isBlank()) {
            return subtree;
        }
        for (ApiRequest request : collection.requests) {
            if (request == null) {
                continue;
            }
            String requestFolderPath = RequestPathResolver.getRequestFolderPath(collection, request);
            if (RequestTreePathService.isFolderPathInSubtree(requestFolderPath, normalizedSource)) {
                subtree.add(request);
            }
        }
        return subtree;
    }

    private static int findRequestSiblingIndex(ApiCollection collection, ApiRequest request, String parentFolderPath) {
        if (collection == null || request == null || collection.requests == null) {
            return -1;
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        int index = 0;
        for (ApiRequest existing : collection.requests) {
            if (existing == null) {
                continue;
            }
            String existingParent = RequestPathResolver.getRequestFolderPath(collection, existing);
            if (!Objects.equals(existingParent, normalizedParent)) {
                continue;
            }
            if (existing == request) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int findFolderSiblingIndex(ApiCollection collection, String folderPath) {
        if (collection == null || collection.folderPaths == null) {
            return -1;
        }
        String normalizedFolder = RequestTreePathService.normalizeFolderPath(folderPath);
        if (normalizedFolder.isBlank()) {
            return -1;
        }
        String parentPath = RequestTreePathService.getParentFolderPath(normalizedFolder);
        int index = 0;
        for (String existing : collection.folderPaths) {
            String normalized = RequestTreePathService.normalizeFolderPath(existing);
            if (normalized.isBlank()) {
                continue;
            }
            if (!Objects.equals(RequestTreePathService.getParentFolderPath(normalized), parentPath)) {
                continue;
            }
            if (Objects.equals(normalized, normalizedFolder)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static boolean hasRequestSiblingConflict(ApiCollection collection,
                                                     String parentFolderPath,
                                                     String candidateName,
                                                     ApiRequest excludedRequest,
                                                     Set<String> excludedFolderPaths,
                                                     Set<ApiRequest> excludedRequests) {
        if (collection == null) {
            return false;
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        String candidateKey = normalizeKey(candidateName);
        if (candidateKey.isBlank()) {
            return false;
        }

        if (collection.folderPaths != null) {
            for (String folderPath : collection.folderPaths) {
                String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
                if (normalized.isBlank()) {
                    continue;
                }
                if (excludedFolderPaths != null && excludedFolderPaths.contains(normalized)) {
                    continue;
                }
                if (!Objects.equals(RequestTreePathService.getParentFolderPath(normalized), normalizedParent)) {
                    continue;
                }
                if (normalizeKey(RequestTreePathService.leafFolderName(normalized)).equals(candidateKey)) {
                    return true;
                }
            }
        }

        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null || request == excludedRequest) {
                    continue;
                }
                if (excludedRequests != null && excludedRequests.contains(request)) {
                    continue;
                }
                if (!Objects.equals(RequestPathResolver.getRequestFolderPath(collection, request), normalizedParent)) {
                    continue;
                }
                if (normalizeKey(request.name).equals(candidateKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasSiblingFolderOrRequestNameConflict(ApiCollection collection,
                                                                 String parentFolderPath,
                                                                 String candidateLeaf,
                                                                 ApiRequest excludedRequest) {
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        String candidateKey = normalizeKey(candidateLeaf);
        if (collection == null || candidateKey.isBlank()) {
            return false;
        }

        if (collection.folderPaths != null) {
            for (String folderPath : collection.folderPaths) {
                String normalizedFolderPath = RequestTreePathService.normalizeFolderPath(folderPath);
                if (normalizedFolderPath.isBlank()) {
                    continue;
                }
                if (!Objects.equals(RequestTreePathService.getParentFolderPath(normalizedFolderPath), normalizedParent)) {
                    continue;
                }
                if (normalizeKey(RequestTreePathService.leafFolderName(normalizedFolderPath)).equals(candidateKey)) {
                    return true;
                }
            }
        }

        if (collection.requests == null) {
            return false;
        }
        for (ApiRequest request : collection.requests) {
            if (request == null || request == excludedRequest) {
                continue;
            }
            if (!Objects.equals(RequestPathResolver.getRequestFolderPath(collection, request), normalizedParent)) {
                continue;
            }
            if (normalizeKey(request.name).equals(candidateKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFolderSubtreeConflict(ApiCollection collection,
                                                    List<String> sourceSubtreePaths,
                                                    Set<String> excludedFolderPaths,
                                                    Set<ApiRequest> excludedRequests,
                                                    String normalizedSourcePrefix,
                                                    String targetPrefix) {
        if (collection == null || sourceSubtreePaths == null || sourceSubtreePaths.isEmpty()) {
            return false;
        }
        for (String sourcePath : sourceSubtreePaths) {
            String candidate = RequestTreePathService.rewriteFolderPathPrefix(sourcePath, normalizedSourcePrefix, targetPrefix);
            String candidateParent = RequestTreePathService.getParentFolderPath(candidate);
            String candidateLeaf = RequestTreePathService.leafFolderName(candidate);
            if (hasRequestSiblingConflict(collection, candidateParent, candidateLeaf, null, excludedFolderPaths, excludedRequests)) {
                return true;
            }
            if (collection.folderPaths != null) {
                for (String folderPath : collection.folderPaths) {
                    String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
                    if (normalized.isBlank()) {
                        continue;
                    }
                    if (excludedFolderPaths != null && excludedFolderPaths.contains(normalized)) {
                        continue;
                    }
                    if (Objects.equals(normalized, candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void copyFolderSubtree(ApiCollection collection, String sourcePrefix, String targetPrefix) {
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        String normalizedTarget = RequestTreePathService.normalizeFolderPath(targetPrefix);
        if (collection == null || normalizedSource.isBlank() || normalizedTarget.isBlank()) {
            return;
        }

        List<String> copiedFolderPaths = new ArrayList<>();
        if (collection.folderPaths != null) {
            for (String folderPath : collection.folderPaths) {
                String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
                if (RequestTreePathService.isFolderPathInSubtree(normalized, normalizedSource)) {
                    copiedFolderPaths.add(RequestTreePathService.rewriteFolderPathPrefix(normalized, normalizedSource, normalizedTarget));
                }
            }
        }
        if (!copiedFolderPaths.isEmpty()) {
            int insertIndex = findFolderPathInsertionIndexAfterSubtree(collection, normalizedSource);
            collection.folderPaths = normalizeFolderPaths(collection.folderPaths);
            insertFolderPaths(collection, copiedFolderPaths, insertIndex);
        } else if (collection.folderPaths != null) {
            collection.folderPaths = normalizeFolderPaths(collection.folderPaths);
        }

        List<ApiRequest> copiedRequests = new ArrayList<>();
        int insertRequestIndex = findRequestInsertionIndexAfterSubtree(collection, normalizedSource);
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null || !isRequestInFolderSubtree(collection, request, normalizedSource)) {
                    continue;
                }
                String targetPath = RequestTreePathService.rewriteFolderPathPrefix(
                        RequestPathResolver.getRequestFolderPath(collection, request),
                        normalizedSource,
                        normalizedTarget
                );
                if (targetPath.isBlank()) {
                    targetPath = normalizedTarget;
                }
                ApiRequest copy = copyRequestForDuplicate(request, collection.name, request.name, targetPath);
                if (copy != null) {
                    copiedRequests.add(copy);
                }
            }
        }
        if (!copiedRequests.isEmpty()) {
            if (collection.requests == null) {
                collection.requests = new ArrayList<>();
            }
            if (insertRequestIndex < 0 || insertRequestIndex > collection.requests.size()) {
                collection.requests.addAll(copiedRequests);
            } else {
                collection.requests.addAll(insertRequestIndex, copiedRequests);
            }
        }

        if (collection.folderAuthModes != null && !collection.folderAuthModes.isEmpty()) {
            Map<String, String> copiedModes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : collection.folderAuthModes.entrySet()) {
                String key = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (RequestTreePathService.isFolderPathInSubtree(key, normalizedSource)) {
                    copiedModes.put(RequestTreePathService.rewriteFolderPathPrefix(key, normalizedSource, normalizedTarget), entry.getValue());
                }
            }
            collection.folderAuthModes.putAll(copiedModes);
        }

        if (collection.folderAuth != null && !collection.folderAuth.isEmpty()) {
            Map<String, ApiRequest.Auth> copiedAuth = new LinkedHashMap<>();
            for (Map.Entry<String, ApiRequest.Auth> entry : collection.folderAuth.entrySet()) {
                String key = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (RequestTreePathService.isFolderPathInSubtree(key, normalizedSource)) {
                    copiedAuth.put(RequestTreePathService.rewriteFolderPathPrefix(key, normalizedSource, normalizedTarget), AuthInheritanceResolver.copyAuth(entry.getValue()));
                }
            }
            collection.folderAuth.putAll(copiedAuth);
        }

        if (collection.folderVars != null && !collection.folderVars.isEmpty()) {
            Map<String, Map<String, String>> copiedVars = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : collection.folderVars.entrySet()) {
                String key = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (RequestTreePathService.isFolderPathInSubtree(key, normalizedSource)) {
                    copiedVars.put(RequestTreePathService.rewriteFolderPathPrefix(key, normalizedSource, normalizedTarget),
                            entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
                }
            }
            collection.folderVars.putAll(copiedVars);
        }

        AuthInheritanceResolver.recomputeCollectionAuth(collection);
    }

    private void rewriteCollectionFolderMetadata(ApiCollection collection, String sourcePrefix, String targetPrefix) {
        if (collection == null) {
            return;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        String normalizedTarget = RequestTreePathService.normalizeFolderPath(targetPrefix);
        if (normalizedSource.isBlank() || normalizedTarget.isBlank()) {
            return;
        }

        if (collection.folderPaths != null) {
            LinkedHashSet<String> rewritten = new LinkedHashSet<>();
            for (String folderPath : collection.folderPaths) {
                String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
                if (RequestTreePathService.isFolderPathInSubtree(normalized, normalizedSource)) {
                    rewritten.add(RequestTreePathService.rewriteFolderPathPrefix(normalized, normalizedSource, normalizedTarget));
                } else if (!normalized.isEmpty()) {
                    rewritten.add(normalized);
                }
            }
            collection.folderPaths = normalizeFolderPaths(new ArrayList<>(rewritten));
        }

        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                if (isRequestInFolderSubtree(collection, request, normalizedSource)) {
                    String requestPath = RequestPathResolver.getRequestFolderPath(collection, request);
                    request.path = RequestTreePathService.rewriteFolderPathPrefix(requestPath, normalizedSource, normalizedTarget);
                    if (request.path.isBlank() && request.name != null) {
                        request.path = normalizedTarget;
                    }
                }
            }
        }

        if (collection.folderAuthModes != null && !collection.folderAuthModes.isEmpty()) {
            Map<String, String> rewrittenModes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : collection.folderAuthModes.entrySet()) {
                String key = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (RequestTreePathService.isFolderPathInSubtree(key, normalizedSource)) {
                    rewrittenModes.put(RequestTreePathService.rewriteFolderPathPrefix(key, normalizedSource, normalizedTarget), entry.getValue());
                } else if (!key.isEmpty()) {
                    rewrittenModes.put(key, entry.getValue());
                }
            }
            collection.folderAuthModes = rewrittenModes;
        }

        if (collection.folderAuth != null && !collection.folderAuth.isEmpty()) {
            Map<String, ApiRequest.Auth> rewrittenAuth = new LinkedHashMap<>();
            for (Map.Entry<String, ApiRequest.Auth> entry : collection.folderAuth.entrySet()) {
                String key = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (RequestTreePathService.isFolderPathInSubtree(key, normalizedSource)) {
                    rewrittenAuth.put(RequestTreePathService.rewriteFolderPathPrefix(key, normalizedSource, normalizedTarget), AuthInheritanceResolver.copyAuth(entry.getValue()));
                } else if (!key.isEmpty()) {
                    rewrittenAuth.put(key, AuthInheritanceResolver.copyAuth(entry.getValue()));
                }
            }
            collection.folderAuth = rewrittenAuth;
        }

        if (collection.folderVars != null && !collection.folderVars.isEmpty()) {
            Map<String, Map<String, String>> rewrittenVars = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : collection.folderVars.entrySet()) {
                String key = RequestTreePathService.normalizeFolderPath(entry.getKey());
                if (RequestTreePathService.isFolderPathInSubtree(key, normalizedSource)) {
                    rewrittenVars.put(RequestTreePathService.rewriteFolderPathPrefix(key, normalizedSource, normalizedTarget),
                            entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
                } else if (!key.isEmpty()) {
                    rewrittenVars.put(key, entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
                }
            }
            collection.folderVars = rewrittenVars;
        }

        AuthInheritanceResolver.recomputeCollectionAuth(collection);
    }

    private static boolean isRequestInFolderSubtree(ApiCollection collection, ApiRequest request, String folderPrefix) {
        if (request == null) {
            return false;
        }
        String normalizedPrefix = RequestTreePathService.normalizeFolderPath(folderPrefix);
        if (normalizedPrefix.isEmpty()) {
            return true;
        }
        String requestFolderPath = RequestPathResolver.getRequestFolderPath(collection, request);
        return RequestTreePathService.isFolderPathInSubtree(requestFolderPath, normalizedPrefix);
    }

    private static void addCollectionFolderPath(ApiCollection collection, String folderPath, String parentFolderPath, String afterFolderPath) {
        if (collection == null) {
            return;
        }
        if (collection.folderPaths == null) {
            collection.folderPaths = new ArrayList<>();
        }
        String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
        if (normalized.isEmpty()) {
            return;
        }
        collection.folderPaths.removeIf(path -> Objects.equals(RequestTreePathService.normalizeFolderPath(path), normalized));
        int index = findFolderPathInsertionIndex(collection, parentFolderPath, afterFolderPath);
        if (index < 0 || index > collection.folderPaths.size()) {
            collection.folderPaths.add(normalized);
        } else {
            collection.folderPaths.add(index, normalized);
        }
        collection.folderPaths = normalizeFolderPaths(collection.folderPaths);
    }

    private static void insertFolderPaths(ApiCollection collection, List<String> folderPaths, int index) {
        if (collection == null || folderPaths == null || folderPaths.isEmpty()) {
            return;
        }
        if (collection.folderPaths == null) {
            collection.folderPaths = new ArrayList<>();
        }
        List<String> normalized = normalizeFolderPaths(folderPaths);
        if (normalized.isEmpty()) {
            return;
        }
        if (index < 0 || index > collection.folderPaths.size()) {
            collection.folderPaths.addAll(normalized);
        } else {
            collection.folderPaths.addAll(index, normalized);
        }
        collection.folderPaths = normalizeFolderPaths(collection.folderPaths);
    }

    private static void insertRequestForParentPath(ApiCollection collection, ApiRequest request, String parentFolderPath, ApiRequest afterRequest) {
        if (collection == null || request == null) {
            return;
        }
        if (collection.requests == null) {
            collection.requests = new ArrayList<>();
        }
        int index = findRequestInsertionIndex(collection, parentFolderPath, afterRequest);
        if (index < 0 || index > collection.requests.size()) {
            collection.requests.add(request);
        } else {
            collection.requests.add(index, request);
        }
        request.sourceCollection = collection.name;
    }

    private static int findFolderPathInsertionIndex(ApiCollection collection, String parentFolderPath, String afterFolderPath) {
        if (collection == null || collection.folderPaths == null) {
            return -1;
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        if (afterFolderPath != null) {
            String normalizedAfter = RequestTreePathService.normalizeFolderPath(afterFolderPath);
            for (int i = 0; i < collection.folderPaths.size(); i++) {
                String existing = RequestTreePathService.normalizeFolderPath(collection.folderPaths.get(i));
                if (Objects.equals(existing, normalizedAfter)) {
                    return i + 1;
                }
            }
        }
        int lastIndex = -1;
        for (int i = 0; i < collection.folderPaths.size(); i++) {
            String existing = RequestTreePathService.normalizeFolderPath(collection.folderPaths.get(i));
            if (existing.isBlank()) {
                continue;
            }
            if (Objects.equals(RequestTreePathService.getParentFolderPath(existing), normalizedParent)) {
                lastIndex = i;
            }
        }
        return lastIndex + 1;
    }

    private static int findFolderPathInsertionIndexAfterSubtree(ApiCollection collection, String sourcePrefix) {
        if (collection == null || collection.folderPaths == null) {
            return -1;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        int lastIndex = -1;
        for (int i = 0; i < collection.folderPaths.size(); i++) {
            String existing = RequestTreePathService.normalizeFolderPath(collection.folderPaths.get(i));
            if (RequestTreePathService.isFolderPathInSubtree(existing, normalizedSource)) {
                lastIndex = i;
            }
        }
        return lastIndex + 1;
    }

    private static int findRequestInsertionIndex(ApiCollection collection, String parentFolderPath, ApiRequest afterRequest) {
        if (collection == null || collection.requests == null) {
            return -1;
        }
        if (afterRequest != null) {
            for (int i = 0; i < collection.requests.size(); i++) {
                if (collection.requests.get(i) == afterRequest) {
                    return i + 1;
                }
            }
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        int lastIndex = -1;
        for (int i = 0; i < collection.requests.size(); i++) {
            ApiRequest request = collection.requests.get(i);
            if (request == null) {
                continue;
            }
            if (Objects.equals(RequestPathResolver.getRequestFolderPath(collection, request), normalizedParent)) {
                lastIndex = i;
            }
        }
        return lastIndex + 1;
    }

    private static int findRequestInsertionIndex(ApiCollection collection,
                                                 String parentFolderPath,
                                                 int siblingIndex,
                                                 ApiRequest excludedRequest) {
        if (collection == null || collection.requests == null) {
            return -1;
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        int index = 0;
        int siblingCount = 0;
        for (ApiRequest request : collection.requests) {
            if (request == null || request == excludedRequest) {
                continue;
            }
            if (!Objects.equals(RequestPathResolver.getRequestFolderPath(collection, request), normalizedParent)) {
                index++;
                continue;
            }
            if (siblingIndex >= 0 && siblingCount == siblingIndex) {
                return index;
            }
            siblingCount++;
            index++;
        }
        return index;
    }

    private static int findRequestInsertionIndexAfterSubtree(ApiCollection collection, String sourcePrefix) {
        if (collection == null || collection.requests == null) {
            return -1;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        int lastIndex = -1;
        for (int i = 0; i < collection.requests.size(); i++) {
            ApiRequest request = collection.requests.get(i);
            if (request != null && isRequestInFolderSubtree(collection, request, normalizedSource)) {
                lastIndex = i;
            }
        }
        return lastIndex + 1;
    }

    private static int findFolderInsertionIndex(ApiCollection collection, String parentFolderPath, int siblingIndex) {
        if (collection == null || collection.folderPaths == null) {
            return -1;
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        int index = 0;
        int siblingCount = 0;
        for (String folderPath : collection.folderPaths) {
            String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
            if (normalized.isBlank()) {
                continue;
            }
            if (Objects.equals(RequestTreePathService.getParentFolderPath(normalized), normalizedParent)) {
                if (siblingIndex >= 0 && siblingCount == siblingIndex) {
                    return index;
                }
                siblingCount++;
            }
            index++;
        }
        return index;
    }

    private static List<String> normalizeFolderPaths(List<String> folderPaths) {
        List<String> normalized = new ArrayList<>();
        if (folderPaths == null) {
            return normalized;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String folderPath : folderPaths) {
            String value = RequestTreePathService.normalizeFolderPath(folderPath);
            if (!value.isEmpty() && seen.add(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static String normalizeKey(String value) {
        String normalized = RequestTreeNamingPolicy.normalizeTreeLabel(value);
        return normalized.isEmpty() ? "" : normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static ApiRequest copyRequestForDuplicate(ApiRequest source, String targetCollectionName, String targetName, String targetPath) {
        if (source == null) {
            return null;
        }
        ApiRequest copy = new ApiRequest();
        copy.id = UUID.randomUUID().toString();
        copy.name = RequestTreeNamingPolicy.normalizeTreeLabel(targetName);
        copy.path = targetPath != null ? targetPath : source.path;
        copy.sourceCollection = targetCollectionName != null ? targetCollectionName : source.sourceCollection;
        copy.description = source.description;
        copy.method = source.method;
        copy.url = source.url;
        copy.headers = copyHeaders(source.headers);
        copy.body = copyBody(source.body);
        copy.auth = AuthInheritanceResolver.copyAuth(source.auth);
        copy.editorMaterialized = source.editorMaterialized;
        copy.buildMode = source.buildMode;
        copy.suppressedAutoHeaders = source.suppressedAutoHeaders != null
                ? new LinkedHashSet<>(source.suppressedAutoHeaders)
                : new LinkedHashSet<>();
        copy.variables = copyVariables(source.variables);
        copy.preRequestScripts = copyScripts(source.preRequestScripts);
        copy.postResponseScripts = copyScripts(source.postResponseScripts);
        copy.disabled = source.disabled;
        copy.sequenceOrder = source.sequenceOrder;
        copy.authInherited = source.authInherited;
        copy.authExplicitlyDisabled = source.authExplicitlyDisabled;
        copy.authSource = source.authSource;
        copy.authOverrideMode = source.authOverrideMode;
        copy.explicitAuth = AuthInheritanceResolver.copyAuth(source.explicitAuth);
        return copy;
    }

    private static ApiCollection copyCollectionForDuplicate(ApiCollection source, String targetName) {
        if (source == null) {
            return null;
        }
        ApiCollection copy = new ApiCollection();
        copy.name = RequestTreeNamingPolicy.normalizeTreeLabel(targetName);
        copy.description = source.description;
        copy.format = source.format;
        copy.version = source.version;
        copy.auth = AuthInheritanceResolver.copyAuth(source.auth);
        copy.folderPaths = normalizeFolderPaths(source.folderPaths != null ? source.folderPaths : List.of());
        copy.variables = copyVariables(source.variables);
        copy.folderVars = copyNestedStringMap(source.folderVars);
        copy.environment = copyStringMap(source.environment);
        copy.folderAuthModes = copyStringMap(source.folderAuthModes);
        copy.folderAuth = copyAuthMap(source.folderAuth);
        copy.requests = new ArrayList<>();
        if (source.requests != null) {
            for (ApiRequest request : source.requests) {
                if (request == null) {
                    continue;
                }
                ApiRequest requestCopy = copyRequestForDuplicate(
                        request,
                        copy.name,
                        request.name,
                        RequestPathResolver.getRequestFolderPath(source, request)
                );
                if (requestCopy != null) {
                    copy.requests.add(requestCopy);
                }
            }
        }
        copy.runtimeVars = new LinkedHashMap<>();
        copy.runtimeOAuth2 = new LinkedHashMap<>();
        AuthInheritanceResolver.recomputeCollectionAuth(copy);
        return copy;
    }

    private static List<ApiRequest.Header> copyHeaders(List<ApiRequest.Header> src) {
        List<ApiRequest.Header> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Header header : src) {
            if (header == null) {
                out.add(null);
                continue;
            }
            out.add(new ApiRequest.Header(header.key, header.value, header.disabled));
        }
        return out;
    }

    private static ApiRequest.Body copyBody(ApiRequest.Body src) {
        if (src == null) {
            return null;
        }
        ApiRequest.Body copy = new ApiRequest.Body();
        copy.mode = src.mode;
        copy.raw = src.raw;
        copy.contentType = src.contentType;
        copy.formdata = copyFormFields(src.formdata);
        copy.urlencoded = copyFormFields(src.urlencoded);
        copy.graphql = copyGraphQL(src.graphql);
        return copy;
    }

    private static List<ApiRequest.Body.FormField> copyFormFields(List<ApiRequest.Body.FormField> src) {
        List<ApiRequest.Body.FormField> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Body.FormField field : src) {
            if (field == null) {
                out.add(null);
                continue;
            }
            ApiRequest.Body.FormField copy = new ApiRequest.Body.FormField(field.key, field.value);
            copy.type = field.type;
            copy.fileUpload = field.fileUpload;
            copy.filePath = field.filePath;
            copy.disabled = field.disabled;
            out.add(copy);
        }
        return out;
    }

    private static ApiRequest.Body.GraphQL copyGraphQL(ApiRequest.Body.GraphQL src) {
        if (src == null) {
            return null;
        }
        ApiRequest.Body.GraphQL copy = new ApiRequest.Body.GraphQL();
        copy.query = src.query;
        copy.variables = src.variables;
        return copy;
    }

    private static List<ApiRequest.Variable> copyVariables(List<ApiRequest.Variable> src) {
        List<ApiRequest.Variable> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Variable variable : src) {
            if (variable == null) {
                out.add(null);
                continue;
            }
            ApiRequest.Variable copy = new ApiRequest.Variable();
            copy.key = variable.key;
            copy.value = variable.value;
            copy.type = variable.type;
            copy.enabled = variable.enabled;
            out.add(copy);
        }
        return out;
    }

    private static List<ApiRequest.Script> copyScripts(List<ApiRequest.Script> src) {
        List<ApiRequest.Script> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (ApiRequest.Script script : src) {
            if (script == null) {
                out.add(null);
                continue;
            }
            out.add(new ApiRequest.Script(script.type, script.exec));
        }
        return out;
    }

    private static Map<String, String> copyStringMap(Map<String, String> src) {
        return src != null ? new LinkedHashMap<>(src) : new LinkedHashMap<>();
    }

    private static Map<String, Map<String, String>> copyNestedStringMap(Map<String, Map<String, String>> src) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (Map.Entry<String, Map<String, String>> entry : src.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(entry.getKey(), entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
        }
        return out;
    }

    private static Map<String, ApiRequest.Auth> copyAuthMap(Map<String, ApiRequest.Auth> src) {
        Map<String, ApiRequest.Auth> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (Map.Entry<String, ApiRequest.Auth> entry : src.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(entry.getKey(), AuthInheritanceResolver.copyAuth(entry.getValue()));
        }
        return out;
    }
}
