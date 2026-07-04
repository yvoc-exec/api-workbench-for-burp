package burp.scripts.capabilities;

import java.util.Objects;

public record ScriptCapabilityFinding(
        ScriptCapability capability,
        String apiName,
        ScriptRiskLevel riskLevel,
        boolean supported,
        String safeMessage) {

    public ScriptCapabilityFinding {
        capability = capability != null ? capability : ScriptCapability.UNSUPPORTED_API;
        apiName = apiName != null ? apiName : "";
        riskLevel = riskLevel != null ? riskLevel : ScriptRiskLevel.LOW;
        safeMessage = safeMessage != null ? safeMessage : "";
    }

    public String stableKey() {
        return capability.name() + "\u0000" + apiName + "\u0000" + supported;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScriptCapabilityFinding finding)) {
            return false;
        }
        return supported == finding.supported
                && capability == finding.capability
                && Objects.equals(apiName, finding.apiName)
                && riskLevel == finding.riskLevel
                && Objects.equals(safeMessage, finding.safeMessage);
    }
}
