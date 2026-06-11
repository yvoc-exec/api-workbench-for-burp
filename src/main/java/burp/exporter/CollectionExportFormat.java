package burp.exporter;

public enum CollectionExportFormat {
    API_WORKBENCH_JSON("API Workbench Collection JSON", ".api-workbench.collection.json", false),
    POSTMAN_JSON("Postman Collection v2.1 JSON", ".postman_collection.json", false),
    OPENAPI_JSON("OpenAPI 3.0 JSON", ".openapi.json", false),
    OPENAPI_YAML("OpenAPI 3.0 YAML", ".openapi.yaml", false),
    INSOMNIA_JSON("Insomnia JSON", ".insomnia.json", false),
    BRUNO_ZIP("Bruno ZIP", ".bruno.zip", true),
    HAR_JSON("HAR 1.2 JSON", ".har", false);

    private final String displayName;
    private final String defaultExtension;
    private final boolean archive;

    CollectionExportFormat(String displayName, String defaultExtension, boolean archive) {
        this.displayName = displayName;
        this.defaultExtension = defaultExtension;
        this.archive = archive;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultExtension() {
        return defaultExtension;
    }

    public boolean archive() {
        return archive;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
