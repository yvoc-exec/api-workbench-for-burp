package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FlowTargetResolution {
    public final FlowTargetResolutionStatus status;
    public final FlowTargetResolutionForm form;
    public final ApiRequest request;
    public final ApiCollection collection;
    public final int orderedIndex;
    public final String qualifiedPath;
    public final List<String> candidateRequestIds;
    public final List<String> candidateQualifiedPaths;
    public final String safeMessage;

    private FlowTargetResolution(FlowTargetResolutionStatus status,
                                 FlowTargetResolutionForm form,
                                 ApiRequest request,
                                 ApiCollection collection,
                                 int orderedIndex,
                                 String qualifiedPath,
                                 List<String> candidateRequestIds,
                                 List<String> candidateQualifiedPaths,
                                 String safeMessage) {
        this.status = status;
        this.form = form != null ? form : FlowTargetResolutionForm.NONE;
        this.request = request;
        this.collection = collection;
        this.orderedIndex = orderedIndex;
        this.qualifiedPath = qualifiedPath;
        this.candidateRequestIds = unmodifiableCopy(candidateRequestIds);
        this.candidateQualifiedPaths = unmodifiableCopy(candidateQualifiedPaths);
        this.safeMessage = safeMessage;
    }

    public static FlowTargetResolution resolved(
            FlowTargetResolutionForm form,
            ApiRequest request,
            ApiCollection collection,
            int orderedIndex,
            String qualifiedPath) {
        return new FlowTargetResolution(FlowTargetResolutionStatus.RESOLVED, form, request, collection, orderedIndex, qualifiedPath, List.of(), List.of(), null);
    }

    public static FlowTargetResolution notFound(String safeMessage) {
        return new FlowTargetResolution(FlowTargetResolutionStatus.NOT_FOUND, FlowTargetResolutionForm.NONE, null, null, -1, null, List.of(), List.of(), safeMessage);
    }

    public static FlowTargetResolution ambiguous(
            String safeMessage,
            List<String> candidateRequestIds,
            List<String> candidateQualifiedPaths) {
        return new FlowTargetResolution(FlowTargetResolutionStatus.AMBIGUOUS, FlowTargetResolutionForm.NONE, null, null, -1, null, candidateRequestIds, candidateQualifiedPaths, safeMessage);
    }

    public static FlowTargetResolution disabled(
            ApiRequest request,
            ApiCollection collection,
            int orderedIndex,
            String qualifiedPath,
            String safeMessage) {
        return new FlowTargetResolution(FlowTargetResolutionStatus.DISABLED, FlowTargetResolutionForm.NONE, request, collection, orderedIndex, qualifiedPath, List.of(), List.of(), safeMessage);
    }

    private static List<String> unmodifiableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
