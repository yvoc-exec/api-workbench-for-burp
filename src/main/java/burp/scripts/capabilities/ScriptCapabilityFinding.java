package burp.scripts.capabilities;

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
}
