package burp.scripts;

public enum ScriptFlowControl {
    CONTINUE,
    SKIP_REQUEST,
    STOP_RUN,
    SET_NEXT_REQUEST,
    RUN_REQUEST,
    SEND_AD_HOC_REQUEST
}
