package software.spool.publisher.api.strategy;

import software.spool.core.exception.SpoolException;
import software.spool.core.port.Subscription;

public interface FeederStrategy {
    Subscription start() throws SpoolException;
    default Subscription stop() {
        return Subscription.NULL;
    }
}
