package burp.utils;

@FunctionalInterface
public interface PreflightDecisionHandler {
    boolean confirm(ExecutionPreflightResult preflight);
}
