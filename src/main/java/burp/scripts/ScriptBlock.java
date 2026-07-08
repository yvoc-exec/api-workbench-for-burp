package burp.scripts;

import burp.models.ApiRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ScriptBlock {
    public String id = UUID.randomUUID().toString();
    public ScriptDialect dialect = ScriptDialect.LEGACY_JAVASCRIPT;
    public ScriptPhase phase = ScriptPhase.PRE_REQUEST;
    public ScriptScope scope = ScriptScope.REQUEST;
    public String source;
    public boolean enabled = true;
    public String sourceFormat;
    public String sourcePath;
    public int order;
    public Map<String, String> metadata = new LinkedHashMap<>();

    public static ScriptBlock copyOf(ScriptBlock source) {
        if (source == null) {
            return null;
        }
        ScriptBlock copy = new ScriptBlock();
        copy.id = source.id;
        copy.dialect = source.dialect;
        copy.phase = source.phase;
        copy.scope = source.scope;
        copy.source = source.source;
        copy.enabled = source.enabled;
        copy.sourceFormat = source.sourceFormat;
        copy.sourcePath = source.sourcePath;
        copy.order = source.order;
        copy.metadata = source.metadata != null ? new LinkedHashMap<>(source.metadata) : new LinkedHashMap<>();
        return copy;
    }

    public static ScriptBlock of(String source, ScriptDialect dialect, ScriptPhase phase, ScriptScope scope) {
        ScriptBlock block = new ScriptBlock();
        block.source = source;
        block.dialect = dialect != null ? dialect : ScriptDialect.LEGACY_JAVASCRIPT;
        block.phase = phase != null ? phase : ScriptPhase.PRE_REQUEST;
        block.scope = scope != null ? scope : ScriptScope.REQUEST;
        return block;
    }

    public static ScriptBlock fromLegacy(ApiRequest.Script script,
                                         ScriptDialect dialect,
                                         ScriptPhase phase,
                                         ScriptScope scope,
                                         String sourceFormat,
                                         String sourcePath,
                                         int order) {
        if (script == null) {
            return null;
        }
        ScriptBlock block = of(script.exec, dialect, phase, scope);
        block.sourceFormat = sourceFormat;
        block.sourcePath = sourcePath;
        block.order = order;
        if (script.type != null) {
            block.metadata.put("type", script.type);
        }
        return block;
    }

    public ApiRequest.Script toLegacyScript() {
        return new ApiRequest.Script(
                metadata != null && metadata.containsKey("type") ? metadata.get("type") : "js",
                source != null ? source : ""
        );
    }

    public static List<ApiRequest.Script> toLegacyScripts(List<ScriptBlock> blocks) {
        java.util.ArrayList<ApiRequest.Script> out = new java.util.ArrayList<>();
        if (blocks == null) {
            return out;
        }
        for (ScriptBlock block : blocks) {
            if (block == null || !block.enabled || block.source == null) {
                continue;
            }
            out.add(block.toLegacyScript());
        }
        return out;
    }
}
