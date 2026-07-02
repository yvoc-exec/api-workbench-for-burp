package burp.scripts;

import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraalJsSandboxEngineSecurityTest {
    @Test
    void filteredNashornFallbackDeniesJavaType() throws Exception {
        try (GraalJsSandboxEngine engine = new GraalJsSandboxEngine()) {
            ScriptEngine nashorn = filteredFallback(engine);
            if (nashorn != null) {
                assertThatThrownBy(() -> nashorn.eval("Java.type('java.lang.System')"))
                        .hasMessageContaining("java");
            }
        }
    }

    @Test
    void filteredNashornFallbackDeniesPackagesAndReflection() throws Exception {
        try (GraalJsSandboxEngine engine = new GraalJsSandboxEngine()) {
            ScriptEngine nashorn = filteredFallback(engine);
            if (nashorn != null) {
                assertThatThrownBy(() -> nashorn.eval("Packages.java.lang.System.getProperty('user.home')"))
                        .hasMessageContaining("java");
                assertThatThrownBy(() -> engine.execute("Java.type('java.lang.Class')", Map.of()))
                        .hasMessageContaining("not allowed");
            }
        }
    }

    @Test
    void fallbackNeverUsesUnfilteredScriptEngineManagerEngine() throws Exception {
        String source = Files.readString(Path.of("src/main/java/burp/scripts/GraalJsSandboxEngine.java"));
        assertThat(source).doesNotContain("ScriptEngineManager");
        assertThat(source).doesNotContain("getEngineByName");
        assertThat(source).contains("createFilteredNashornFallback");
        assertThat(source).contains("new DenyAllClassFilter()");
    }

    private ScriptEngine filteredFallback(GraalJsSandboxEngine engine) throws Exception {
        Method method = GraalJsSandboxEngine.class.getDeclaredMethod("createFilteredNashornFallback");
        method.setAccessible(true);
        return (ScriptEngine) method.invoke(engine);
    }
}
