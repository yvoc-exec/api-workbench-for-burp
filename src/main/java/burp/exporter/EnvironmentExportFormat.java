package burp.exporter;

public enum EnvironmentExportFormat {
    API_WORKBENCH_JSON("API Workbench Environment JSON", ".api-workbench.environment.json"),
    POSTMAN_JSON("Postman Environment JSON", ".postman_environment.json"),
    DOTENV("dotenv .env", ".env"),
    JSON_OBJECT("Generic JSON Object", ".env.json"),
    INSOMNIA_JSON("Insomnia Environment JSON", ".insomnia.environment.json"),
    BRUNO_BRU("Bruno Environment .bru", ".bru");

    private final String displayName;
    private final String defaultExtension;

    EnvironmentExportFormat(String displayName, String defaultExtension) {
        this.displayName = displayName;
        this.defaultExtension = defaultExtension;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultExtension() {
        return defaultExtension;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
