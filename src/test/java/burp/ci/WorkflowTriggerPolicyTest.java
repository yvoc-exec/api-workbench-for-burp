package burp.ci;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTriggerPolicyTest {
    private static final List<String> FORBIDDEN_TRIGGERS = List.of(
            "push",
            "pull_request",
            "pull_request_target",
            "schedule",
            "workflow_run"
    );

    @Test
    void allGithubActionsWorkflowsAreManualOnly() throws Exception {
        Path workflowDir = Path.of(".github", "workflows");
        assertThat(Files.isDirectory(workflowDir))
                .as("workflow directory %s", workflowDir)
                .isTrue();

        List<Path> workflowFiles = new ArrayList<>();
        try (var stream = Files.list(workflowDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .forEach(workflowFiles::add);
        }

        assertThat(workflowFiles).isNotEmpty();

        for (Path file : workflowFiles) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int onBlockIndex = -1;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isTopLevelOnDeclaration(line) && !isBlockStyleOn(line)) {
                    throw new AssertionError("Forbidden non-block trigger in " + file + ": " + line.trim());
                }
                if (onBlockIndex < 0 && isBlockStyleOn(line)) {
                    onBlockIndex = i;
                }
            }

            assertThat(onBlockIndex)
                    .as("Missing block-style top-level on section in %s", file)
                    .isGreaterThanOrEqualTo(0);

            String onSection = collectOnSection(lines, onBlockIndex + 1);
            assertThat(onSection)
                    .as("Missing workflow_dispatch in %s", file)
                    .contains("workflow_dispatch:");

            for (String trigger : FORBIDDEN_TRIGGERS) {
                if (containsTrigger(onSection, trigger)) {
                    throw new AssertionError("Forbidden automatic trigger '" + trigger + "' remains in " + file);
                }
            }

            assertThat(text)
                    .as("Manual-only workflow verified: %s", file.getFileName())
                    .isNotBlank();
        }
    }

    private static boolean isTopLevelOnDeclaration(String line) {
        return line != null && line.matches("^(?:['\"])?on(?:['\"])?:\\s*.*$");
    }

    private static boolean isBlockStyleOn(String line) {
        return line != null && line.matches("^(?:['\"])?on(?:['\"])?:\\s*(?:#.*)?$");
    }

    private static String collectOnSection(List<String> lines, int startIndex) {
        StringBuilder section = new StringBuilder();
        for (int i = startIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            if (line.matches("^[^\\s#][^:]*:\\s*.*$")) {
                break;
            }
            section.append(line).append('\n');
        }
        return section.toString();
    }

    private static boolean containsTrigger(String onSection, String trigger) {
        return onSection != null && Pattern.compile("(?m)^\\s*['\"]?" + Pattern.quote(trigger) + "['\"]?:\\s*")
                .matcher(onSection)
                .find();
    }
}
