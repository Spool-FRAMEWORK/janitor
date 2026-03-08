package software.spool.publisher.internal.util;

import software.spool.publisher.internal.port.Subscription;

public class InMemorySubscription implements Subscription {

    private final Runnable onCancel;
    private volatile boolean active = true;

    public InMemorySubscription(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            onCancel.run();
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }
}
