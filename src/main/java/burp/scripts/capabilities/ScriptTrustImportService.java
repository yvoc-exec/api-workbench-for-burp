package burp.scripts.capabilities;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.scripts.ScriptBlock;
import burp.ui.tree.RequestTreePathService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central post-parse script trust finalizer. Call this only on detached parser
 * results before collections are committed to the live workspace.
 */
public final class ScriptTrustImportService {
    private final ScriptCapabilityAnalyzer analyzer;

    public ScriptTrustImportService() {
        this(new ScriptCapabilityAnalyzer());
    }

    public ScriptTrustImportService(ScriptCapabilityAnalyzer analyzer) {
        this.analyzer = analyzer != null ? analyzer : new ScriptCapabilityAnalyzer();
    }

    public ScriptTrustReviewModel prepare(List<ApiCollection> collections, ScriptImportOrigin origin) {
        ScriptImportOrigin effectiveOrigin = origin != null ? origin : ScriptImportOrigin.FILE_IMPORT;
        List<BlockLocation> locations = enumerate(collections);
        List<ScriptTrustReviewItem> items = new ArrayList<>();
        for (BlockLocation location : locations) {
            ScriptBlock block = location.block;
            if (block == null) {
                continue;
            }
            if (block.metadata == null) {
                block.metadata = new LinkedHashMap<>();
            }
            ScriptCapabilityReport report = analyzer.analyzeAndAnnotate(block);
            switch (effectiveOrigin) {
                case FILE_IMPORT -> {
                    block.enabled = false;
                    block.metadata.put("trustState", "disabled");
                }
                case WORKSPACE_RESTORE -> block.metadata.putIfAbsent("trustState", "native");
                case BURP_TRAFFIC -> {
                    block.enabled = false;
                    block.metadata.put("trustState", "disabled");
                }
            }
            if (effectiveOrigin == ScriptImportOrigin.FILE_IMPORT) {
                items.add(toItem(location, report));
            }
        }
        return new ScriptTrustReviewModel(items);
    }

    public boolean applyDecision(List<ApiCollection> collections, ScriptTrustReviewModel model) {
        if (model == null || model.decision() == ScriptTrustReviewModel.Decision.CANCEL_IMPORT) {
            return false;
        }
        Map<String, ScriptBlock> blocks = blocksById(collections);
        Set<String> selected = new LinkedHashSet<>(model.selectedBlockIds());
        for (ScriptTrustReviewItem item : model.items()) {
            if (item == null || item.blockId == null) {
                continue;
            }
            ScriptBlock block = blocks.get(item.blockId);
            if (block == null) {
                continue;
            }
            if (block.metadata == null) {
                block.metadata = new LinkedHashMap<>();
            }
            boolean supported = item.capabilityReport == null || !item.capabilityReport.hasUnsupportedCapabilities();
            boolean trust = switch (model.decision()) {
                case KEEP_ALL_DISABLED -> false;
                case TRUST_SELECTED -> selected.contains(item.blockId) && supported;
                case TRUST_ALL -> supported;
                case CANCEL_IMPORT -> false;
            };
            block.enabled = trust;
            block.metadata.put("trustState", trust ? "trusted" : "disabled");
        }
        return true;
    }

    public List<ScriptBlock> enumerateBlocks(List<ApiCollection> collections) {
        List<ScriptBlock> blocks = new ArrayList<>();
        for (BlockLocation location : enumerate(collections)) {
            if (location.block != null) {
                blocks.add(location.block);
            }
        }
        return List.copyOf(blocks);
    }

    private List<BlockLocation> enumerate(List<ApiCollection> collections) {
        List<BlockLocation> locations = new ArrayList<>();
        if (collections == null) {
            return locations;
        }
        for (ApiCollection collection : collections) {
            if (collection == null) {
                continue;
            }
            collection.ensureDefaults();
            if (collection.scriptBlocks != null) {
                for (ScriptBlock block : collection.scriptBlocks) {
                    add(locations, collection, null, "", block);
                }
            }
            if (collection.folderScriptBlocks != null && !collection.folderScriptBlocks.isEmpty()) {
                List<String> paths = new ArrayList<>(collection.folderScriptBlocks.keySet());
                paths.sort(String.CASE_INSENSITIVE_ORDER);
                for (String path : paths) {
                    String normalized = RequestTreePathService.normalizeFolderPath(path);
                    List<ScriptBlock> blocks = collection.folderScriptBlocks.get(path);
                    if (blocks == null) {
                        continue;
                    }
                    for (ScriptBlock block : blocks) {
                        add(locations, collection, null, normalized, block);
                    }
                }
            }
            if (collection.requests != null) {
                for (ApiRequest request : collection.requests) {
                    if (request == null || request.scriptBlocks == null) {
                        continue;
                    }
                    for (ScriptBlock block : request.scriptBlocks) {
                        add(locations, collection, request, RequestTreePathService.normalizeFolderPath(request.path), block);
                    }
                }
            }
        }
        locations.sort(Comparator
                .comparingInt((BlockLocation location) -> location.collectionOrder)
                .thenComparingInt(location -> location.scopeOrder)
                .thenComparing(location -> location.folderPath, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(location -> location.requestOrder)
                .thenComparingInt(location -> location.block != null ? location.block.order : 0)
                .thenComparing(location -> location.block != null && location.block.id != null ? location.block.id : ""));
        return locations;
    }

    private void add(List<BlockLocation> out,
                     ApiCollection collection,
                     ApiRequest request,
                     String folderPath,
                     ScriptBlock block) {
        if (block == null || block.source == null || block.source.isBlank()) {
            return;
        }
        int collectionOrder = 0;
        int requestOrder = request != null && collection.requests != null ? Math.max(0, collection.requests.indexOf(request)) : -1;
        int scopeOrder = request != null ? 2 : folderPath != null && !folderPath.isBlank() ? 1 : 0;
        out.add(new BlockLocation(collection, request, folderPath != null ? folderPath : "", block,
                collectionOrder, scopeOrder, requestOrder));
    }

    private ScriptTrustReviewItem toItem(BlockLocation location, ScriptCapabilityReport report) {
        ScriptTrustReviewItem item = new ScriptTrustReviewItem();
        ScriptBlock block = location.block;
        item.blockId = block.id != null ? block.id : "";
        item.collectionId = location.collection.id != null ? location.collection.id : "";
        item.collectionName = location.collection.name != null ? location.collection.name : "";
        item.requestId = location.request != null && location.request.id != null ? location.request.id : "";
        item.requestName = location.request != null && location.request.name != null ? location.request.name : "";
        item.folderPath = location.folderPath;
        item.dialect = block.dialect;
        item.phase = block.phase;
        item.scope = block.scope;
        item.sourceFormat = block.sourceFormat != null ? block.sourceFormat : "";
        item.sourcePath = block.sourcePath != null ? block.sourcePath : "";
        item.sourcePreview = safePreview(block.source);
        item.capabilityReport = report;
        return item;
    }

    private Map<String, ScriptBlock> blocksById(List<ApiCollection> collections) {
        LinkedHashMap<String, ScriptBlock> out = new LinkedHashMap<>();
        for (ScriptBlock block : enumerateBlocks(collections)) {
            if (block != null && block.id != null && !block.id.isBlank()) {
                out.putIfAbsent(block.id, block);
            }
        }
        return out;
    }

    private String safePreview(String source) {
        if (source == null) {
            return "";
        }
        String normalized = source.replace('\u0000', ' ');
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800) + "\n…";
    }

    private record BlockLocation(ApiCollection collection,
                                 ApiRequest request,
                                 String folderPath,
                                 ScriptBlock block,
                                 int collectionOrder,
                                 int scopeOrder,
                                 int requestOrder) {
    }
}
