package burp.scripts.capabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScriptTrustReviewModel {
    public enum Decision {
        KEEP_ALL_DISABLED,
        TRUST_SELECTED,
        TRUST_ALL,
        CANCEL_IMPORT
    }

    private final List<ScriptTrustReviewItem> items = new ArrayList<>();
    private Decision decision = Decision.KEEP_ALL_DISABLED;

    public ScriptTrustReviewModel(List<ScriptTrustReviewItem> source) {
        if (source != null) {
            for (ScriptTrustReviewItem item : source) {
                if (item != null) {
                    items.add(item.copy());
                }
            }
        }
    }

    public List<ScriptTrustReviewItem> items() {
        List<ScriptTrustReviewItem> copy = new ArrayList<>();
        for (ScriptTrustReviewItem item : items) {
            copy.add(item.copy());
        }
        return Collections.unmodifiableList(copy);
    }

    public int totalScriptCount() {
        return items.size();
    }

    public ScriptRiskLevel highestRisk() {
        ScriptRiskLevel risk = ScriptRiskLevel.LOW;
        for (ScriptTrustReviewItem item : items) {
            if (item != null && item.capabilityReport != null) {
                risk = ScriptRiskLevel.max(risk, item.capabilityReport.riskLevel);
            }
        }
        return risk;
    }

    public int unsupportedCount() {
        int count = 0;
        for (ScriptTrustReviewItem item : items) {
            if (item != null && item.capabilityReport != null && item.capabilityReport.hasUnsupportedCapabilities()) {
                count++;
            }
        }
        return count;
    }

    public Map<String, Long> dialectCounts() {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (ScriptTrustReviewItem item : items) {
            String key = item != null && item.dialect != null ? item.dialect.name() : "UNKNOWN";
            counts.put(key, counts.getOrDefault(key, 0L) + 1L);
        }
        return Collections.unmodifiableMap(counts);
    }

    public void setSelectedForTrust(String blockId, boolean selected) {
        if (blockId == null) {
            return;
        }
        for (ScriptTrustReviewItem item : items) {
            if (item != null && blockId.equals(item.blockId)) {
                item.selectedForTrust = selected;
            }
        }
    }

    public List<String> selectedBlockIds() {
        List<String> ids = new ArrayList<>();
        for (ScriptTrustReviewItem item : items) {
            if (item != null && item.selectedForTrust && item.blockId != null && !item.blockId.isBlank()) {
                ids.add(item.blockId);
            }
        }
        return List.copyOf(ids);
    }

    public Decision decision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision != null ? decision : Decision.KEEP_ALL_DISABLED;
    }

    public boolean requiresHighRiskTrustConfirmation() {
        if (decision != Decision.TRUST_ALL) {
            return false;
        }
        return highestRisk().ordinal() >= ScriptRiskLevel.HIGH.ordinal();
    }
}
