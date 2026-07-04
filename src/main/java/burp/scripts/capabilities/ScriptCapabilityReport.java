package burp.scripts.capabilities;

import burp.scripts.ScriptDialect;
import burp.scripts.ScriptPhase;
import burp.scripts.ScriptScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ScriptCapabilityReport {
    public ScriptDialect dialect;
    public ScriptPhase phase;
    public ScriptScope scope;
    public String sourcePath = "";
    public final List<ScriptCapabilityFinding> findings = new ArrayList<>();
    public ScriptRiskLevel riskLevel = ScriptRiskLevel.LOW;

    public List<ScriptCapabilityFinding> findings() {
        return Collections.unmodifiableList(findings);
    }

    public Set<ScriptCapability> capabilities() {
        LinkedHashSet<ScriptCapability> out = new LinkedHashSet<>();
        for (ScriptCapabilityFinding finding : findings) {
            if (finding != null) {
                out.add(finding.capability());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public List<String> unsupportedApiNames() {
        return findings.stream()
                .filter(finding -> finding != null && !finding.supported())
                .map(ScriptCapabilityFinding::apiName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public boolean hasUnsupportedCapabilities() {
        return findings.stream().anyMatch(finding -> finding != null && !finding.supported());
    }

    public String capabilitySummary() {
        if (findings.isEmpty()) {
            return "No elevated capabilities detected";
        }
        return findings.stream()
                .map(finding -> finding.capability().name())
                .distinct()
                .collect(Collectors.joining(","));
    }

    public String unsupportedSummary() {
        return String.join(",", unsupportedApiNames());
    }

    public String safeSummary() {
        return "Risk=" + riskLevel.name()
                + "; Capabilities=" + capabilitySummary()
                + "; Unsupported=" + (hasUnsupportedCapabilities() ? unsupportedSummary() : "none");
    }
}
