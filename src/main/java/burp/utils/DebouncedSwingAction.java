package burp.utils;

import javax.swing.Timer;

public class DebouncedSwingAction {
    private final Timer timer;

    public DebouncedSwingAction(int delayMs, Runnable action) {
        this.timer = new Timer(Math.max(50, delayMs), e -> {
            if (action != null) {
                action.run();
            }
        });
        this.timer.setRepeats(false);
    }

    public void restart() {
        timer.restart();
    }

    public void stop() {
        timer.stop();
    }
}
