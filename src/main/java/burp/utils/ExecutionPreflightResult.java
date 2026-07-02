package burp.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExecutionPreflightResult {
    public final ExecutionPreflightStatus status;
    public final boolean maySend;
    public final String safeMessage;
    public final List<String> unresolvedVariables;
    public final String originalResolvedUrl;
    public final String effectiveResolvedUrl;
    public final String originalHost;
    public final String effectiveHost;
    public final boolean targetChanged;
    public final boolean oauth2Required;
    public final boolean oauth2Ready;
    public final boolean scriptFailed;
    public final boolean scriptTimedOut;
    public final boolean confirmationRequired;
    public final boolean confirmationAccepted;
    public final List<ExecutionPreflightStatus> reasons;
    public final List<String> policyOverridesApplied;

    private ExecutionPreflightResult(ExecutionPreflightStatus status,
                                     boolean maySend,
                                     String safeMessage,
                                     List<String> unresolvedVariables,
                                     String originalResolvedUrl,
                                     String effectiveResolvedUrl,
                                     String originalHost,
                                     String effectiveHost,
                                     boolean targetChanged,
                                     boolean oauth2Required,
                                     boolean oauth2Ready,
                                     boolean scriptFailed,
                                     boolean scriptTimedOut,
                                     boolean confirmationRequired,
                                     boolean confirmationAccepted,
                                     List<ExecutionPreflightStatus> reasons,
                                     List<String> policyOverridesApplied) {
        this.status = status != null ? status : ExecutionPreflightStatus.READY;
        this.maySend = maySend;
        this.safeMessage = safeMessage != null ? safeMessage : "";
        this.unresolvedVariables = unresolvedVariables != null ? List.copyOf(new ArrayList<>(unresolvedVariables)) : List.of();
        this.originalResolvedUrl = originalResolvedUrl != null ? originalResolvedUrl : "";
        this.effectiveResolvedUrl = effectiveResolvedUrl != null ? effectiveResolvedUrl : "";
        this.originalHost = originalHost != null ? originalHost : "";
        this.effectiveHost = effectiveHost != null ? effectiveHost : "";
        this.targetChanged = targetChanged;
        this.oauth2Required = oauth2Required;
        this.oauth2Ready = oauth2Ready;
        this.scriptFailed = scriptFailed;
        this.scriptTimedOut = scriptTimedOut;
        this.confirmationRequired = confirmationRequired;
        this.confirmationAccepted = confirmationAccepted;
        this.reasons = reasons != null ? List.copyOf(new ArrayList<>(reasons)) : List.of();
        this.policyOverridesApplied = policyOverridesApplied != null ? List.copyOf(new ArrayList<>(policyOverridesApplied)) : List.of();
    }

    public static ExecutionPreflightResult ready(String safeMessage,
                                                 List<String> unresolvedVariables,
                                                 String originalResolvedUrl,
                                                 String effectiveResolvedUrl,
                                                 String originalHost,
                                                 String effectiveHost,
                                                 boolean targetChanged,
                                                 boolean oauth2Required,
                                                 boolean oauth2Ready,
                                                 boolean scriptFailed,
                                                 boolean scriptTimedOut,
                                                 boolean confirmationRequired,
                                                 boolean confirmationAccepted,
                                                 List<ExecutionPreflightStatus> reasons,
                                                 List<String> policyOverridesApplied) {
        return new ExecutionPreflightResult(ExecutionPreflightStatus.READY, true, safeMessage, unresolvedVariables,
                originalResolvedUrl, effectiveResolvedUrl, originalHost, effectiveHost, targetChanged, oauth2Required,
                oauth2Ready, scriptFailed, scriptTimedOut, confirmationRequired, confirmationAccepted, reasons, policyOverridesApplied);
    }

    public static ExecutionPreflightResult preview(String safeMessage,
                                                   List<String> unresolvedVariables,
                                                   String originalResolvedUrl,
                                                   String effectiveResolvedUrl,
                                                   String originalHost,
                                                   String effectiveHost,
                                                   List<ExecutionPreflightStatus> reasons) {
        return new ExecutionPreflightResult(ExecutionPreflightStatus.PREVIEW_ONLY, false, safeMessage, unresolvedVariables,
                originalResolvedUrl, effectiveResolvedUrl, originalHost, effectiveHost, false, false, false,
                false, false, false, false, reasons, List.of());
    }

    public static ExecutionPreflightResult blocked(ExecutionPreflightStatus status,
                                                   String safeMessage,
                                                   List<String> unresolvedVariables,
                                                   String originalResolvedUrl,
                                                   String effectiveResolvedUrl,
                                                   String originalHost,
                                                   String effectiveHost,
                                                   boolean targetChanged,
                                                   boolean oauth2Required,
                                                   boolean oauth2Ready,
                                                   boolean scriptFailed,
                                                   boolean scriptTimedOut,
                                                   boolean confirmationRequired,
                                                   boolean confirmationAccepted,
                                                   List<ExecutionPreflightStatus> reasons,
                                                   List<String> policyOverridesApplied) {
        return new ExecutionPreflightResult(status, false, safeMessage, unresolvedVariables,
                originalResolvedUrl, effectiveResolvedUrl, originalHost, effectiveHost, targetChanged, oauth2Required,
                oauth2Ready, scriptFailed, scriptTimedOut, confirmationRequired, confirmationAccepted, reasons, policyOverridesApplied);
    }

    public static ExecutionPreflightResult cancelled(String safeMessage,
                                                     String originalResolvedUrl,
                                                     String effectiveResolvedUrl) {
        return new ExecutionPreflightResult(ExecutionPreflightStatus.CANCELLED, false, safeMessage, List.of(),
                originalResolvedUrl, effectiveResolvedUrl, "", "", false, false, false,
                false, false, false, false, List.of(ExecutionPreflightStatus.CANCELLED), List.of());
    }
}
