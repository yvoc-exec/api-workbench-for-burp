package burp.utils;

import javax.swing.Timer;

public class DebouncedSwingAction {
    private final Timer timer;
    private volatile boolean closed;

    public DebouncedSwingAction(int delayMs, Runnable action) {
        this.timer = new Timer(Math.max(50, delayMs), e -> {
            if (!closed && action != null) {
                action.run();
            }
        });
        this.timer.setRepeats(false);
    }

    public void restart() {
        if (!closed) {
            timer.restart();
        }
    }

    public void stop() {
        timer.stop();
    }

    public void close() {
        closed = true;
        timer.stop();
    }

    public boolean isClosed() {
        return closed;
    }
}
