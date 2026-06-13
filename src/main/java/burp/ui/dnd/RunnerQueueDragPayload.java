package burp.ui.dnd;

import burp.models.ApiRequest;

/**
 * JVM-local payload for reordering the runner queue.
 */
public final class RunnerQueueDragPayload {
    public final ApiRequest request;
    public final int sourceIndex;

    public RunnerQueueDragPayload(ApiRequest request, int sourceIndex) {
        this.request = request;
        this.sourceIndex = sourceIndex;
    }

    @Override
    public String toString() {
        return "RunnerQueueDragPayload{"
                + "request=" + (request != null ? request.name : "null")
                + ", sourceIndex=" + sourceIndex
                + '}';
    }
}
