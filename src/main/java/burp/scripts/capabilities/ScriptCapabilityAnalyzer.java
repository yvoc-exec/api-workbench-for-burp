package burp.scripts.capabilities;

import burp.scripts.ScriptBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic, non-executing capability scanner for imported JavaScript.
 * Comments and quoted literals are replaced with whitespace for executable API
 * matching. A separate comment-free view retains quoted variable names solely
 * for credential-key classification.
 */
public final class ScriptCapabilityAnalyzer {
    private record Rule(Pattern pattern,
                        ScriptCapability capability,
                        String apiName,
                        ScriptRiskLevel risk,
                        boolean supported,
                        String message) {
    }

    private static final List<Rule> RULES = rules();
    private static final Pattern SENSITIVE_KEY_ACCESS = Pattern.compile(
            "\\b(?:pm\\.(?:environment|collectionVariables|variables|globals)|"
                    + "bru\\.(?:getEnvVar|getVar|getCollectionVar|getFolderVar|getRequestVar)|"
                    + "insomnia\\.(?:environment|baseEnvironment|collectionVariables|variables)|"
                    + "awb\\.(?:environment|collection|folder|request|local|global))"
                    + "\\s*\\.?\\s*(?:get|has)?\\s*\\(\\s*(['\"])"
                    + "(?:password|passphrase|secret|client_secret|access_token|refresh_token|id_token|token|api[_-]?key|authorization|cookie)"
                    + "\\1",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern COMPUTED_AD_HOC_NETWORK = Pattern.compile(
            "\\b(?:awb|execution)\\s*\\[\\s*(['\"])sendAdHocRequest\\1\\s*]\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    public ScriptCapabilityReport analyze(ScriptBlock block) {
        ScriptCapabilityReport report = new ScriptCapabilityReport();
        if (block == null) {
            return report;
        }
        report.dialect = block.dialect;
        report.phase = block.phase;
        report.scope = block.scope;
        report.sourcePath = block.sourcePath != null ? block.sourcePath : "";
        String source = block.source != null ? block.source : "";
        String searchable = stripCommentsAndStrings(source);
        String commentFree = stripComments(source);
        Map<String, ScriptCapabilityFinding> unique = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            if (rule.pattern.matcher(searchable).find()) {
                addFinding(unique, report, rule);
            }
        }
        if (SENSITIVE_KEY_ACCESS.matcher(commentFree).find()) {
            addFinding(unique, report, new Rule(
                    SENSITIVE_KEY_ACCESS,
                    ScriptCapability.SENSITIVE_DATA_ACCESS,
                    "sensitive variable access",
                    ScriptRiskLevel.HIGH,
                    true,
                    "Script may read credential-like variable values."));
        }
        if (COMPUTED_AD_HOC_NETWORK.matcher(commentFree).find()) {
            addFinding(unique, report, new Rule(
                    COMPUTED_AD_HOC_NETWORK,
                    ScriptCapability.AD_HOC_NETWORK,
                    "sendAdHocRequest",
                    ScriptRiskLevel.CRITICAL,
                    true,
                    "Script can execute an ad-hoc HTTP request through the Runner runtime."));
        }
        report.findings.addAll(unique.values());
        report.findings.sort(Comparator
                .comparing((ScriptCapabilityFinding finding) -> finding.capability().ordinal())
                .thenComparing(ScriptCapabilityFinding::apiName, String.CASE_INSENSITIVE_ORDER));
        return report;
    }

    public ScriptCapabilityReport analyzeAndAnnotate(ScriptBlock block) {
        ScriptCapabilityReport report = analyze(block);
        if (block != null) {
            if (block.metadata == null) {
                block.metadata = new LinkedHashMap<>();
            }
            block.metadata.put("capabilitySummary", report.capabilitySummary());
            block.metadata.put("capabilityRisk", report.riskLevel.name());
            block.metadata.put("unsupportedCapabilities", report.unsupportedSummary());
        }
        return report;
    }

    public boolean hasBlockingUnsupportedCapability(ScriptBlock block) {
        return analyze(block).hasUnsupportedCapabilities();
    }

    private static void addFinding(Map<String, ScriptCapabilityFinding> unique,
                                   ScriptCapabilityReport report,
                                   Rule rule) {
        ScriptCapabilityFinding finding = new ScriptCapabilityFinding(
                rule.capability,
                rule.apiName,
                rule.risk,
                rule.supported,
                rule.message);
        unique.putIfAbsent(finding.stableKey(), finding);
        report.riskLevel = ScriptRiskLevel.max(report.riskLevel, finding.riskLevel());
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(rule("\\b(?:pm\\.(?:environment|collectionVariables|variables|globals)|bru\\.(?:setEnvVar|deleteEnvVar|setVar|deleteVar)|insomnia\\.(?:environment|variables)|awb\\.(?:environment|collection|folder|request|local|global))\\s*\\.?(?:set|unset|replace|add|remove|delete)?\\s*\\(",
                ScriptCapability.VARIABLE_MUTATION, "variable mutation", ScriptRiskLevel.MEDIUM, true,
                "Script can modify variable state."));
        rules.add(rule("\\b(?:pm\\.request|request|req|awb\\.request)\\s*\\.\\s*(?:url|setUrl|setName)\\b",
                ScriptCapability.REQUEST_URL_MUTATION, "request URL mutation", ScriptRiskLevel.HIGH, true,
                "Script can change the request destination."));
        rules.add(rule("\\b(?:pm\\.request|request|req|awb\\.request)\\s*\\.\\s*(?:method|setMethod)\\b",
                ScriptCapability.REQUEST_METHOD_MUTATION, "request method mutation", ScriptRiskLevel.MEDIUM, true,
                "Script can change the request method."));
        rules.add(rule("\\b(?:pm\\.request|request|req|awb\\.request)\\s*\\.\\s*(?:headers?|setHeader|addHeader|removeHeader|upsertHeader)\\b",
                ScriptCapability.REQUEST_HEADER_MUTATION, "request header mutation", ScriptRiskLevel.MEDIUM, true,
                "Script can modify request headers."));
        rules.add(rule("\\b(?:pm\\.request|request|req|awb\\.request)\\s*\\.\\s*(?:body|setBody)\\b",
                ScriptCapability.REQUEST_BODY_MUTATION, "request body mutation", ScriptRiskLevel.MEDIUM, true,
                "Script can modify the request body."));
        rules.add(rule("\\b(?:pm\\.request|request|req|awb\\.request)\\s*\\.\\s*(?:auth|setAuth)\\b",
                ScriptCapability.REQUEST_AUTH_MUTATION, "request auth mutation", ScriptRiskLevel.HIGH, true,
                "Script can modify request authentication."));
        rules.add(rule("\\b(?:query|queryParams|searchParams|addQueryParam|setQueryParam|removeQueryParam)\\b",
                ScriptCapability.REQUEST_QUERY_MUTATION, "request query mutation", ScriptRiskLevel.MEDIUM, true,
                "Script can modify request query parameters."));
        rules.add(rule("\\b(?:setNextRequest|skipRequest|stopExecution|stopRun|skip|setNext)\\s*\\(",
                ScriptCapability.FLOW_CONTROL, "runner flow control", ScriptRiskLevel.HIGH, true,
                "Script can alter Runner control flow."));
        rules.add(rule("\\b(?:runRequest|executeRequest)\\s*\\(",
                ScriptCapability.DEPENDENT_REQUEST, "dependent request", ScriptRiskLevel.HIGH, true,
                "Script can execute another collection request through the supported runtime."));
        rules.add(rule("\\bsendAdHocRequest\\s*\\(",
                ScriptCapability.AD_HOC_NETWORK, "sendAdHocRequest", ScriptRiskLevel.CRITICAL, true,
                "Script can execute an ad-hoc HTTP request through the Runner runtime."));
        rules.add(rule("\\b(?:pm\\.sendRequest|bru\\.sendRequest|insomnia\\.sendRequest|awb\\.sendRequest|sendRequest)\\s*\\(",
                ScriptCapability.AD_HOC_NETWORK, "sendRequest", ScriptRiskLevel.CRITICAL, false,
                "Ad-hoc network requests are not supported by the sandbox."));
        rules.add(rule("\\bfetch\\s*\\(",
                ScriptCapability.AD_HOC_NETWORK, "fetch", ScriptRiskLevel.CRITICAL, false,
                "fetch is not supported by the sandbox."));
        rules.add(rule("\\b(?:new\\s+)?XMLHttpRequest\\b",
                ScriptCapability.AD_HOC_NETWORK, "XMLHttpRequest", ScriptRiskLevel.CRITICAL, false,
                "XMLHttpRequest is not supported by the sandbox."));
        rules.add(rule("\\b(?:Java\\s*\\.\\s*type|JavaImporter|Packages(?:\\.|\\[)|Polyglot(?:\\.|\\[)|load\\s*\\(|loadWithNewGlobal\\s*\\()",
                ScriptCapability.HOST_INTEROP, "host interop", ScriptRiskLevel.CRITICAL, false,
                "Host-language interop is blocked by the sandbox."));
        rules.add(rule("\\b(?:child_process|ProcessBuilder|Runtime\\s*\\.\\s*getRuntime|process\\s*\\.\\s*(?:exec|spawn|mainModule)|require\\s*\\()",
                ScriptCapability.HOST_INTEROP, "process execution", ScriptRiskLevel.CRITICAL, false,
                "Process and module access are blocked by the sandbox."));
        rules.add(rule("\\b(?:cookie|cookies|authorization|bearer|access_token|refresh_token|id_token|oauth|client_secret|password|api[_-]?key)\\b",
                ScriptCapability.SENSITIVE_DATA_ACCESS, "sensitive data access", ScriptRiskLevel.HIGH, true,
                "Script may read or modify credential-like data."));
        return List.copyOf(rules);
    }

    private static Rule rule(String expression,
                             ScriptCapability capability,
                             String apiName,
                             ScriptRiskLevel risk,
                             boolean supported,
                             String message) {
        return new Rule(Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                capability, apiName, risk, supported, message);
    }

    static String stripCommentsAndStrings(String source) {
        return scan(source, true);
    }

    static String stripComments(String source) {
        return scan(source, false);
    }

    private static String scan(String source, boolean stripStrings) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(source.length());
        State state = State.CODE;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        out.append(' ').append(' ');
                        i++;
                        state = State.LINE_COMMENT;
                    } else if (current == '/' && next == '*') {
                        out.append(' ').append(' ');
                        i++;
                        state = State.BLOCK_COMMENT;
                    } else if (current == '\'') {
                        out.append(stripStrings ? ' ' : current);
                        state = State.SINGLE_QUOTE;
                        escaped = false;
                    } else if (current == '"') {
                        out.append(stripStrings ? ' ' : current);
                        state = State.DOUBLE_QUOTE;
                        escaped = false;
                    } else if (current == '`') {
                        out.append(stripStrings ? ' ' : current);
                        state = State.TEMPLATE;
                        escaped = false;
                    } else {
                        out.append(current);
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') {
                        out.append(current);
                        state = State.CODE;
                    } else {
                        out.append(' ');
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        out.append(' ').append(' ');
                        i++;
                        state = State.CODE;
                    } else {
                        out.append(current == '\n' || current == '\r' ? current : ' ');
                    }
                }
                case SINGLE_QUOTE, DOUBLE_QUOTE, TEMPLATE -> {
                    char terminator = state == State.SINGLE_QUOTE ? '\'' : state == State.DOUBLE_QUOTE ? '"' : '`';
                    if (stripStrings && current != '\n' && current != '\r') {
                        out.append(' ');
                    } else {
                        out.append(current);
                    }
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == terminator) {
                        state = State.CODE;
                    }
                }
            }
        }
        return out.toString();
    }

    private enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        TEMPLATE
    }
}
