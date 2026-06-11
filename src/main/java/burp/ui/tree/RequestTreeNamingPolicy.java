package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.RequestPathResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Naming and rename-validation policy for request-tree nodes.
 */
public final class RequestTreeNamingPolicy {
    private RequestTreeNamingPolicy() {
    }

    public static String normalizeTreeLabel(String value) {
        return value != null ? value.trim() : "";
    }

    public static String uniqueCollectionName(List<ApiCollection> collections, String baseName) {
        return uniqueName(existingRootCollectionNames(collections), baseName);
    }

    public static String uniqueCollectionCopyName(List<ApiCollection> collections, String sourceName) {
        return uniqueName(existingRootCollectionNames(collections), canonicalCopyBase(sourceName));
    }

    public static String uniqueChildName(ApiCollection collection, String parentFolderPath, String baseName) {
        return uniqueName(existingChildNames(collection, parentFolderPath), baseName);
    }

    public static String uniqueChildCopyName(ApiCollection collection, String parentFolderPath, String sourceName) {
        return uniqueName(existingChildNames(collection, parentFolderPath), canonicalCopyBase(sourceName));
    }

    public static RenameValidation validateCollectionRename(List<ApiCollection> collections,
                                                            ApiCollection target,
                                                            String newName) {
        String normalizedName = normalizeTreeLabel(newName);
        if (normalizedName.isBlank()) {
            return RenameValidation.invalid("A collection name is required.");
        }
        String currentName = target != null ? normalizeTreeLabel(target.name) : "";
        if (Objects.equals(currentName, normalizedName)) {
            return RenameValidation.ok(normalizedName);
        }
        for (ApiCollection collection : collections != null ? collections : List.<ApiCollection>of()) {
            if (collection == null || collection == target) {
                continue;
            }
            if (normalizeKey(collection.name).equals(normalizeKey(normalizedName))) {
                return RenameValidation.invalid("A collection named '" + normalizedName + "' already exists.");
            }
        }
        return RenameValidation.ok(normalizedName);
    }

    public static RenameValidation validateFolderRename(ApiCollection collection,
                                                        String oldFolderPath,
                                                        String newLeafName) {
        String normalizedOldPath = RequestTreePathService.normalizeFolderPath(oldFolderPath);
        String normalizedLeaf = normalizeTreeLabel(newLeafName);
        if (normalizedOldPath.isBlank() || normalizedLeaf.isBlank()) {
            return RenameValidation.invalid("A folder name is required.");
        }
        if (containsPathSeparator(normalizedLeaf)) {
            return RenameValidation.invalid("Folder names cannot contain '/' or '\\'. Create a nested folder instead.");
        }
        String parentPath = RequestTreePathService.getParentFolderPath(normalizedOldPath);
        String newPath = RequestTreePathService.joinFolderPath(parentPath, normalizedLeaf);
        if (hasSiblingFolderOrRequestNameConflict(collection, parentPath, normalizedLeaf, normalizedOldPath)) {
            return RenameValidation.invalid("A folder or request named '" + normalizedLeaf + "' already exists in this location.");
        }
        if (hasFolderPathCollision(collection, normalizedOldPath, newPath)) {
            return RenameValidation.invalid("A folder or request named '" + normalizedLeaf + "' already exists in this location.");
        }
        return RenameValidation.ok(normalizedLeaf);
    }

    public static RenameValidation validateRequestRename(ApiCollection collection,
                                                         ApiRequest target,
                                                         String newName) {
        String normalizedName = normalizeTreeLabel(newName);
        if (normalizedName.isBlank()) {
            return RenameValidation.invalid("A request name is required.");
        }
        String currentName = target != null ? normalizeTreeLabel(target.name) : "";
        if (Objects.equals(currentName, normalizedName)) {
            return RenameValidation.ok(normalizedName);
        }
        String parentPath = RequestPathResolver.getRequestFolderPath(collection, target);
        if (hasSiblingFolderOrRequestNameConflict(collection, parentPath, normalizedName, null)) {
            return RenameValidation.invalid("A request or folder named '" + normalizedName + "' already exists in this location.");
        }
        return RenameValidation.ok(normalizedName);
    }

    private static boolean hasSiblingFolderOrRequestNameConflict(ApiCollection collection,
                                                                 String parentFolderPath,
                                                                 String candidateLeaf,
                                                                 String currentFolderPath) {
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        String candidateKey = normalizeKey(candidateLeaf);
        if (collection == null || candidateKey.isBlank()) {
            return false;
        }

        for (String folderPath : collectAllFolderPaths(collection)) {
            String normalizedFolderPath = RequestTreePathService.normalizeFolderPath(folderPath);
            if (normalizedFolderPath.isBlank()) {
                continue;
            }
            if (currentFolderPath != null && normalizeKey(normalizedFolderPath).equals(normalizeKey(currentFolderPath))) {
                continue;
            }
            if (!Objects.equals(RequestTreePathService.getParentFolderPath(normalizedFolderPath), normalizedParent)) {
                continue;
            }
            if (normalizeKey(RequestTreePathService.leafFolderName(normalizedFolderPath)).equals(candidateKey)) {
                return true;
            }
        }

        if (collection.requests == null) {
            return false;
        }
        for (ApiRequest request : collection.requests) {
            if (request == null) {
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

    private static boolean hasFolderPathCollision(ApiCollection collection,
                                                  String sourcePrefix,
                                                  String targetPrefix) {
        if (collection == null) {
            return false;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        String normalizedTarget = RequestTreePathService.normalizeFolderPath(targetPrefix);
        if (normalizedSource.isBlank() || normalizedTarget.isBlank()) {
            return false;
        }

        Set<String> subtree = collectFolderSubtreePaths(collection, normalizedSource);
        Set<String> subtreeKeys = normalizeKeys(subtree);
        Set<String> existingKeysOutsideSubtree = new LinkedHashSet<>();
        for (String folderPath : collectAllFolderPaths(collection)) {
            String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
            if (normalized.isBlank() || subtreeKeys.contains(normalizeKey(normalized))) {
                continue;
            }
            existingKeysOutsideSubtree.add(normalizeKey(normalized));
        }

        for (String folderPath : subtree) {
            String candidate = RequestTreePathService.rewriteFolderPathPrefix(folderPath, normalizedSource, normalizedTarget);
            if (existingKeysOutsideSubtree.contains(normalizeKey(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> collectFolderSubtreePaths(ApiCollection collection, String sourcePrefix) {
        LinkedHashSet<String> subtree = new LinkedHashSet<>();
        if (collection == null) {
            return subtree;
        }
        String normalizedSource = RequestTreePathService.normalizeFolderPath(sourcePrefix);
        if (normalizedSource.isBlank()) {
            return subtree;
        }
        for (String folderPath : collectAllFolderPaths(collection)) {
            String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
            if (RequestTreePathService.isFolderPathInSubtree(normalized, normalizedSource)) {
                subtree.add(normalized);
            }
        }
        return subtree;
    }

    private static Set<String> collectAllFolderPaths(ApiCollection collection) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (collection == null) {
            return paths;
        }

        if (collection.folderPaths != null) {
            for (String folderPath : collection.folderPaths) {
                addFolderPathWithAncestors(paths, folderPath);
            }
        }

        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                addFolderPathWithAncestors(paths, RequestPathResolver.getRequestFolderPath(collection, request));
            }
        }
        return paths;
    }

    private static void addFolderPathWithAncestors(Set<String> paths, String folderPath) {
        String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
        while (!normalized.isEmpty()) {
            paths.add(normalized);
            normalized = RequestTreePathService.getParentFolderPath(normalized);
        }
    }

    private static Set<String> existingRootCollectionNames(List<ApiCollection> collections) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (collections == null) {
            return names;
        }
        for (ApiCollection collection : collections) {
            if (collection == null) {
                continue;
            }
            String name = normalizeTreeLabel(collection.name);
            if (!name.isEmpty()) {
                names.add(normalizeKey(name));
            }
        }
        return names;
    }

    private static Set<String> existingChildNames(ApiCollection collection, String parentFolderPath) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (collection == null) {
            return names;
        }
        String normalizedParent = RequestTreePathService.normalizeFolderPath(parentFolderPath);
        for (String folderPath : collectAllFolderPaths(collection)) {
            String normalizedFolderPath = RequestTreePathService.normalizeFolderPath(folderPath);
            if (normalizedFolderPath.isEmpty()) {
                continue;
            }
            if (Objects.equals(RequestTreePathService.getParentFolderPath(normalizedFolderPath), normalizedParent)) {
                String leaf = normalizeTreeLabel(RequestTreePathService.leafFolderName(normalizedFolderPath));
                if (!leaf.isEmpty()) {
                    names.add(normalizeKey(leaf));
                }
            }
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                if (!Objects.equals(RequestPathResolver.getRequestFolderPath(collection, request), normalizedParent)) {
                    continue;
                }
                String name = normalizeTreeLabel(request.name);
                if (!name.isEmpty()) {
                    names.add(normalizeKey(name));
                }
            }
        }
        return names;
    }

    private static String uniqueName(Set<String> existingNames, String baseName) {
        String normalizedBase = normalizeTreeLabel(baseName);
        if (normalizedBase.isBlank()) {
            normalizedBase = "Untitled";
        }
        String candidate = normalizedBase;
        if (!existingNames.contains(normalizeKey(candidate))) {
            return candidate;
        }
        int suffix = 2;
        while (existingNames.contains(normalizeKey(candidateWithSuffix(normalizedBase, suffix)))) {
            suffix++;
        }
        return candidateWithSuffix(normalizedBase, suffix);
    }

    private static String candidateWithSuffix(String baseName, int suffix) {
        return baseName + " " + suffix;
    }

    private static String canonicalCopyBase(String sourceName) {
        String normalized = normalizeTreeLabel(sourceName);
        if (normalized.isBlank()) {
            return "Copy";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int copyIndex = lower.lastIndexOf(" copy");
        if (copyIndex >= 0) {
            String trailing = normalized.substring(copyIndex + 5);
            if (trailing.isEmpty() || trailing.matches("\\s+\\d+")) {
                return normalized.substring(0, copyIndex + 5);
            }
        }
        return normalized + " Copy";
    }

    private static boolean containsPathSeparator(String value) {
        return value != null && (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0);
    }

    private static Set<String> normalizeKeys(Set<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String normalized = normalizeKey(value);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static String normalizeKey(String value) {
        String normalized = normalizeTreeLabel(value);
        return normalized.isEmpty() ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    public static final class RenameValidation {
        public final boolean valid;
        public final String normalizedName;
        public final String message;

        private RenameValidation(boolean valid, String normalizedName, String message) {
            this.valid = valid;
            this.normalizedName = normalizedName;
            this.message = message;
        }

        public static RenameValidation ok(String normalizedName) {
            return new RenameValidation(true, normalizedName, null);
        }

        public static RenameValidation invalid(String message) {
            return new RenameValidation(false, null, message);
        }
    }
}
