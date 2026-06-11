package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.ui.tree.RequestTreePathService;
import burp.utils.RequestPathResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class CollectionExportTree {
    private CollectionExportTree() {
    }

    static FolderNode build(ApiCollection collection) {
        FolderNode root = new FolderNode("", "");
        if (collection == null) {
            return root;
        }

        LinkedHashSet<String> explicitFolders = new LinkedHashSet<>();
        if (collection.folderPaths != null) {
            for (String folderPath : collection.folderPaths) {
                String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
                if (!normalized.isBlank()) {
                    explicitFolders.add(normalized);
                }
            }
        }
        for (String folderPath : explicitFolders) {
            ensureFolder(root, folderPath);
        }

        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request == null) {
                    continue;
                }
                String folderPath = RequestPathResolver.getRequestFolderPath(collection, request);
                FolderNode folder = ensureFolder(root, folderPath);
                folder.requests.add(request);
            }
        }
        return root;
    }

    static FolderNode ensureFolder(FolderNode root, String folderPath) {
        if (root == null) {
            root = new FolderNode("", "");
        }
        String normalized = RequestTreePathService.normalizeFolderPath(folderPath);
        if (normalized.isBlank()) {
            return root;
        }
        FolderNode current = root;
        StringBuilder cumulative = new StringBuilder();
        for (String segment : normalized.split("/")) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (cumulative.length() > 0) {
                cumulative.append('/');
            }
            cumulative.append(segment);
            current = current.children.computeIfAbsent(cumulative.toString(), path -> new FolderNode(segment, path));
        }
        return current;
    }

    static final class FolderNode {
        final String name;
        final String path;
        final Map<String, FolderNode> children = new LinkedHashMap<>();
        final List<ApiRequest> requests = new ArrayList<>();

        FolderNode(String name, String path) {
            this.name = name != null ? name : "";
            this.path = path != null ? path : "";
        }
    }
}
