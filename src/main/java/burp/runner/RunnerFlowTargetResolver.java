package burp.runner;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.utils.RequestPathResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RunnerFlowTargetResolver {
    private record Candidate(ApiRequest request,
                             ApiCollection collection,
                             int orderedIndex,
                             String requestId,
                             String collectionId,
                             String collectionName,
                             String folderPath,
                             String requestName,
                             String qualifiedPath) {
    }

    public FlowTargetResolution resolve(
            List<ApiCollection> collections,
            List<ApiRequest> allowedRequests,
            String rawTarget,
            String explicitCollectionId,
            String explicitRequestId,
            String explicitCollectionName,
            String explicitFolderPath,
            String explicitRequestName) {
        List<Candidate> candidates = buildCandidates(collections, allowedRequests);
        String target = firstNonBlank(explicitRequestId, rawTarget, explicitRequestName);

        FlowTargetResolution resolved = resolveExactRequestId(candidates, explicitRequestId);
        if (resolved != null) return resolved;
        resolved = resolveExactRequestId(candidates, rawTarget);
        if (resolved != null) return resolved;
        resolved = resolveCollectionAndRequestId(candidates, explicitCollectionId, explicitRequestId, rawTarget);
        if (resolved != null) return resolved;
        resolved = resolveQualifiedPath(candidates, rawTarget);
        if (resolved != null) return resolved;
        resolved = resolveCollectionFolderAndName(candidates, explicitCollectionName, explicitFolderPath, explicitRequestName);
        if (resolved != null) return resolved;
        resolved = resolveUniqueName(candidates, explicitRequestName, rawTarget);
        if (resolved != null) return resolved;
        resolved = resolveCaseInsensitiveRequestId(candidates, explicitRequestId);
        if (resolved != null) return resolved;
        resolved = resolveCaseInsensitiveRequestId(candidates, rawTarget);
        if (resolved != null) return resolved;
        resolved = resolveCaseInsensitiveQualifiedPath(candidates, rawTarget);
        if (resolved != null) return resolved;
        resolved = resolveCaseInsensitiveName(candidates, explicitRequestName, rawTarget);
        if (resolved != null) return resolved;
        return FlowTargetResolution.notFound("Flow target was not found: " + safeTargetLabel(target) + ".");
    }

    private List<Candidate> buildCandidates(List<ApiCollection> collections, List<ApiRequest> allowedRequests) {
        List<Candidate> candidates = new ArrayList<>();
        if (allowedRequests == null || allowedRequests.isEmpty()) {
            return candidates;
        }
        Map<ApiRequest, ApiCollection> requestToCollection = new LinkedHashMap<>();
        if (collections != null) {
            for (ApiCollection collection : collections) {
                if (collection == null || collection.requests == null) continue;
                for (ApiRequest request : collection.requests) {
                    if (request != null && !requestToCollection.containsKey(request)) {
                        requestToCollection.put(request, collection);
                    }
                }
            }
        }
        for (int i = 0; i < allowedRequests.size(); i++) {
            ApiRequest request = allowedRequests.get(i);
            if (request == null) continue;
            ApiCollection collection = requestToCollection.get(request);
            if (collection == null && collections != null) {
                collection = findCollectionByName(collections, request.sourceCollection);
            }
            String collectionName = collection != null && collection.name != null ? collection.name : request.sourceCollection;
            String collectionId = collection != null ? collection.id : null;
            String folderPath = collection != null ? normalizeFolderPath(RequestPathResolver.getRequestFolderPath(collection, request)) : normalizeFolderPath(request.path);
            String requestName = request.name != null ? request.name : "";
            String qualifiedPath = buildQualifiedPath(collectionName, folderPath, requestName);
            candidates.add(new Candidate(request, collection, i, normalizeText(request.id), normalizeText(collectionId), normalizeText(collectionName), folderPath, requestName, qualifiedPath));
        }
        return candidates;
    }

    private FlowTargetResolution resolveExactRequestId(List<Candidate> candidates, String target) {
        if (target == null) return null;
        List<Candidate> matches = matches(candidates, c -> target.equals(c.requestId));
        return resolveMatches(matches, FlowTargetResolutionForm.REQUEST_ID, target, null);
    }

    private FlowTargetResolution resolveCollectionAndRequestId(List<Candidate> candidates, String explicitCollectionId, String explicitRequestId, String rawTarget) {
        if (isBlank(explicitCollectionId) || isBlank(explicitRequestId)) return null;
        List<Candidate> matches = matches(candidates, c -> explicitCollectionId.equals(c.collectionId) && explicitRequestId.equals(c.requestId));
        return resolveMatches(matches, FlowTargetResolutionForm.COLLECTION_AND_REQUEST_ID, explicitCollectionId + "/" + explicitRequestId, null);
    }

    private FlowTargetResolution resolveQualifiedPath(List<Candidate> candidates, String rawTarget) {
        if (isBlank(rawTarget)) return null;
        List<Candidate> matches = matches(candidates, c -> rawTarget.equals(c.qualifiedPath));
        return resolveMatches(matches, FlowTargetResolutionForm.QUALIFIED_PATH, rawTarget, null);
    }

    private FlowTargetResolution resolveCollectionFolderAndName(List<Candidate> candidates, String explicitCollectionName, String explicitFolderPath, String explicitRequestName) {
        if (isBlank(explicitCollectionName) || isBlank(explicitRequestName)) return null;
        String folder = normalizeFolderPath(explicitFolderPath);
        List<Candidate> matches = matches(candidates, c -> explicitCollectionName.equals(c.collectionName)
                && Objects.equals(folder, c.folderPath)
                && explicitRequestName.equals(c.requestName));
        return resolveMatches(matches, FlowTargetResolutionForm.COLLECTION_FOLDER_AND_NAME, buildQualifiedPath(explicitCollectionName, folder, explicitRequestName), null);
    }

    private FlowTargetResolution resolveUniqueName(List<Candidate> candidates, String explicitRequestName, String rawTarget) {
        String target = firstNonBlank(explicitRequestName, rawTarget);
        if (isBlank(target)) return null;
        List<Candidate> matches = matches(candidates, c -> target.equals(c.requestName));
        return resolveMatches(matches, FlowTargetResolutionForm.UNIQUE_NAME, target, true);
    }

    private FlowTargetResolution resolveCaseInsensitiveRequestId(List<Candidate> candidates, String target) {
        if (isBlank(target)) return null;
        List<Candidate> matches = matches(candidates, c -> target.equalsIgnoreCase(c.requestId));
        return resolveMatches(matches, FlowTargetResolutionForm.CASE_INSENSITIVE_REQUEST_ID, target, true);
    }

    private FlowTargetResolution resolveCaseInsensitiveQualifiedPath(List<Candidate> candidates, String rawTarget) {
        if (isBlank(rawTarget)) return null;
        List<Candidate> matches = matches(candidates, c -> rawTarget.equalsIgnoreCase(c.qualifiedPath));
        return resolveMatches(matches, FlowTargetResolutionForm.CASE_INSENSITIVE_QUALIFIED_PATH, rawTarget, true);
    }

    private FlowTargetResolution resolveCaseInsensitiveName(List<Candidate> candidates, String explicitRequestName, String rawTarget) {
        String target = firstNonBlank(explicitRequestName, rawTarget);
        if (isBlank(target)) return null;
        List<Candidate> matches = matches(candidates, c -> target.equalsIgnoreCase(c.requestName));
        return resolveMatches(matches, FlowTargetResolutionForm.CASE_INSENSITIVE_NAME, target, true);
    }

    private FlowTargetResolution resolveMatches(List<Candidate> matches,
                                                FlowTargetResolutionForm form,
                                                String target,
                                                Boolean uniqueOnly) {
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        List<Candidate> enabled = matches.stream().filter(c -> c.request != null && !c.request.disabled).toList();
        List<Candidate> disabled = matches.stream().filter(c -> c.request != null && c.request.disabled).toList();
        if (matches.size() > 1) {
            return ambiguous(target, matches);
        }
        Candidate candidate = matches.get(0);
        if (candidate.request != null && candidate.request.disabled) {
            return FlowTargetResolution.disabled(candidate.request, candidate.collection, candidate.orderedIndex, candidate.qualifiedPath,
                    "Flow target is disabled: " + candidate.qualifiedPath + ".");
        }
        return FlowTargetResolution.resolved(form, candidate.request, candidate.collection, candidate.orderedIndex, candidate.qualifiedPath);
    }

    private FlowTargetResolution ambiguous(String target, List<Candidate> candidates) {
        List<String> ids = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.requestId != null && !candidate.requestId.isBlank()) {
                ids.add(candidate.requestId);
            }
            if (candidate.qualifiedPath != null && !candidate.qualifiedPath.isBlank()) {
                paths.add(candidate.qualifiedPath);
            }
        }
        ids = ids.stream().distinct().sorted().toList();
        paths = paths.stream().distinct().sorted().toList();
        return FlowTargetResolution.ambiguous("Flow target is ambiguous: " + safeTargetLabel(target) + ". Candidates: " + String.join(", ", paths) + ".", ids, paths);
    }

    private List<Candidate> matches(List<Candidate> candidates, java.util.function.Predicate<Candidate> predicate) {
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate != null && predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static ApiCollection findCollectionByName(List<ApiCollection> collections, String name) {
        if (collections == null || isBlank(name)) return null;
        for (ApiCollection collection : collections) {
            if (collection != null && Objects.equals(name, collection.name)) {
                return collection;
            }
        }
        return null;
    }

    private static String buildQualifiedPath(String collectionName, String folderPath, String requestName) {
        String collection = normalizeText(collectionName);
        String request = requestName != null ? requestName : "";
        if (folderPath == null || folderPath.isBlank()) {
            return collection + "/" + request;
        }
        return collection + "/" + folderPath + "/" + request;
    }

    private static String normalizeFolderPath(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace('\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (String part : normalized.split("/+")) {
            if (part == null || part.isBlank()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part.trim());
        }
        return sb.toString();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeTargetLabel(String value) {
        return value != null && !value.isBlank() ? value : "unknown";
    }
}
