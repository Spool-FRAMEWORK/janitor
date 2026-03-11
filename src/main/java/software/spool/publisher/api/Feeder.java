package software.spool.publisher.api;

import software.spool.core.utils.ErrorRouter;
import software.spool.publisher.api.strategy.FeederStrategy;
import software.spool.core.port.Subscription;

public class Feeder {
    private final FeederStrategy strategy;
    private Subscription subscription;
    private final ErrorRouter errorRouter;

    public Feeder(FeederStrategy strategy, ErrorRouter errorRouter) {
        this.strategy = strategy;
        this.subscription = Subscription.NULL;
        this.errorRouter = errorRouter;
    }

    public void startPublishing() {
        if (subscription.isActive()) return;
        try {
            subscription = strategy.start();
        } catch (Exception e) {
            errorRouter.dispatch(e);
        }
    }

    public void stopPublishing() {
        if (!subscription.isActive()) return;
        try {
            subscription = strategy.stop();
        } catch (Exception e) {
            errorRouter.dispatch(e);
        }
    }
}
