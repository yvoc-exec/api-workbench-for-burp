package burp.scripts;

import burp.scripts.capabilities.ScriptRiskLevel;

public record ScriptUnsupportedCapability(
        String blockId,
        ScriptDialect dialect,
        ScriptPhase phase,
        ScriptScope scope,
        String capabilityName,
        String safeMessage,
        ScriptRiskLevel riskLevel,
        String sourcePath) {

    public ScriptUnsupportedCapability {
        blockId = blockId != null ? blockId : "";
        capabilityName = capabilityName != null ? capabilityName : "";
        safeMessage = safeMessage != null ? safeMessage : "";
        riskLevel = riskLevel != null ? riskLevel : ScriptRiskLevel.CRITICAL;
        sourcePath = sourcePath != null ? sourcePath : "";
    }
}
