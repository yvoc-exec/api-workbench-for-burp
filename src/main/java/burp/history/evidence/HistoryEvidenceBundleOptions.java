package burp.history.evidence;

import java.nio.file.Path;
import java.time.Clock;

public final class HistoryEvidenceBundleOptions {
    public final Path destination;
    public final boolean redactCommonSecrets;
    public final Clock clock;
    public final String extensionVersion;

    public HistoryEvidenceBundleOptions(Path destination,
                                        boolean redactCommonSecrets,
                                        Clock clock,
                                        String extensionVersion) {
        this.destination = destination;
        this.redactCommonSecrets = redactCommonSecrets;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.extensionVersion = extensionVersion != null ? extensionVersion : "unknown";
    }

    public HistoryEvidenceBundleOptions(Path destination, boolean redactCommonSecrets) {
        this(destination, redactCommonSecrets, Clock.systemUTC(), "2.0.0");
    }
}
