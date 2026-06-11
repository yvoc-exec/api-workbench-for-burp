package burp.ui.tree;

import burp.models.ApiCollection;
import burp.models.ApiRequest;

import java.util.Objects;

/**
 * JVM-local payload for request-tree drag operations.
 */
public final class RequestTreeDragPayload {
    public enum NodeType {
        COLLECTION,
        FOLDER,
        REQUEST
    }

    public final NodeType type;
    public final ApiCollection collection;
    public final ApiRequest request;
    public final String folderPath;

    public RequestTreeDragPayload(NodeType type, ApiCollection collection, ApiRequest request, String folderPath) {
        this.type = type;
        this.collection = collection;
        this.request = request;
        this.folderPath = RequestTreePathService.normalizeFolderPath(folderPath);
    }

    public static RequestTreeDragPayload forCollection(ApiCollection collection) {
        return new RequestTreeDragPayload(NodeType.COLLECTION, collection, null, null);
    }

    public static RequestTreeDragPayload forFolder(ApiCollection collection, String folderPath) {
        return new RequestTreeDragPayload(NodeType.FOLDER, collection, null, folderPath);
    }

    public static RequestTreeDragPayload forRequest(ApiCollection collection, ApiRequest request) {
        return new RequestTreeDragPayload(NodeType.REQUEST, collection, request, null);
    }

    public boolean isCollection() {
        return type == NodeType.COLLECTION;
    }

    public boolean isFolder() {
        return type == NodeType.FOLDER;
    }

    public boolean isRequest() {
        return type == NodeType.REQUEST;
    }

    @Override
    public String toString() {
        return "RequestTreeDragPayload{"
                + "type=" + type
                + ", collection=" + (collection != null ? collection.name : "null")
                + ", request=" + (request != null ? request.name : "null")
                + ", folderPath='" + folderPath + '\''
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestTreeDragPayload)) return false;
        RequestTreeDragPayload that = (RequestTreeDragPayload) o;
        return type == that.type
                && Objects.equals(collection, that.collection)
                && Objects.equals(request, that.request)
                && Objects.equals(folderPath, that.folderPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, collection, request, folderPath);
    }
}
