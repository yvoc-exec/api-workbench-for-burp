package burp.testsupport;

import burp.UniversalImporter;
import burp.ui.ActiveEnvironmentVariableBridge;

public final class UiBridgeInstaller {
    private UiBridgeInstaller() {
    }

    public static UniversalImporter install(UniversalImporter importer) {
        if (importer != null) {
            ActiveEnvironmentVariableBridge.install(importer.getUI());
        }
        return importer;
    }
}
