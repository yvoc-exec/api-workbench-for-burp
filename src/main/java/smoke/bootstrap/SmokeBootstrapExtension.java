package smoke.bootstrap;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Thin developer-mode bootstrap that loads the real API Workbench extension from the same jar
 * via an isolated class loader. This keeps Burp's own classpath separate from the extension's
 * burp.* package while still enabling command-line smoke launches.
 */
public final class SmokeBootstrapExtension implements BurpExtension {
    private URLClassLoader delegateClassLoader;
    private BurpExtension delegate;

    @Override
    public void initialize(MontoyaApi api) {
        try {
            writeWrapperMarker();
            URL codeSource = SmokeBootstrapExtension.class.getProtectionDomain().getCodeSource().getLocation();
            delegateClassLoader = new URLClassLoader(new URL[]{codeSource}, SmokeBootstrapExtension.class.getClassLoader());
            Class<?> delegateType = Class.forName("burp.BurpExtender", true, delegateClassLoader);
            Object instance = delegateType.getDeclaredConstructor().newInstance();
            if (!(instance instanceof BurpExtension extension)) {
                throw new IllegalStateException("Loaded delegate does not implement BurpExtension.");
            }
            delegate = extension;
            api.logging().logToOutput("Smoke bootstrap extension loaded; delegating to API Workbench.");
            delegate.initialize(api);
        } catch (Throwable t) {
            api.logging().logToError("Smoke bootstrap extension failed: " + t.getMessage());
        }
    }

    private void writeWrapperMarker() {
        try {
            String configured = System.getProperty("apiWorkbench.smoke.config");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("API_WORKBENCH_SMOKE_CONFIG");
            }
            if (configured == null || configured.isBlank()) {
                return;
            }
            Path markerPath = Path.of(configured).toAbsolutePath().normalize().resolveSibling("bootstrap-wrapper.marker");
            Path parent = markerPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    markerPath,
                    "Smoke bootstrap wrapper loaded at " + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Throwable ignored) {
            // Best-effort diagnostic marker only.
        }
    }
}
