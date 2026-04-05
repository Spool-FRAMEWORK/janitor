package software.spool.feeder.api.strategy;

import software.spool.core.exception.SpoolException;
import software.spool.core.utils.polling.CancellationToken;

/**
 * Strategy interface that defines how the publisher discovers and processes
 * inbox items.
 *
 * <p>
 * Two built-in implementations are provided:
 * </p>
 * <ul>
 * <li>{@link ReactiveFeederStrategy} — listens for {@code InboxItemStored} events
 * on the event bus.</li>
 * <li>{@link PollingFeederStrategy} — polls the inbox at a fixed interval.</li>
 * </ul>
 *
 * @see ReactiveFeederStrategy
 * @see PollingFeederStrategy
 */
public interface FeederStrategy {
    /**
     * Starts the strategy and returns a subscription that can be cancelled.
     *
     * @throws SpoolException if the strategy could not be started
     */
    void execute(CancellationToken token) throws SpoolException;
}
