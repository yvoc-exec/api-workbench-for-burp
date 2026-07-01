package burp.models;

public enum RedirectCrossOriginMode {
    STRIP_SENSITIVE("Strip sensitive headers"),
    TRUSTED_ORIGINS_ONLY("Forward selected headers to trusted origins"),
    PRESERVE_ANY_HTTPS_TARGET("Preserve sensitive headers to any HTTPS redirect target (Dangerous)");

    private final String displayLabel;

    RedirectCrossOriginMode(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }

    @Override
    public String toString() {
        return displayLabel;
    }
}
