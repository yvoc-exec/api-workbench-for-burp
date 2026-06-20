package burp.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class TestResourceLoader {
    private TestResourceLoader() {
    }

    public static String read(String resourcePath) {
        try (InputStream in = TestResourceLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read test resource: " + resourcePath, e);
        }
    }
}
