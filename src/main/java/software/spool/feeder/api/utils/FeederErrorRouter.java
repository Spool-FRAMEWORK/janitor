package software.spool.feeder.api.utils;

import software.spool.core.exception.*;
import software.spool.core.model.failure.InboxItemConsumptionFailed;
import software.spool.core.model.failure.InboxItemStoreFailed;
import software.spool.core.port.bus.EventBusEmitter;
import software.spool.core.utils.routing.ErrorRouter;

/**
 * Provides the default {@link ErrorRouter} configuration for the publisher.
 *
 * <p>
 * The routing table maps each typed exception to an appropriate failure event
 * emitted on the {@link EventBusEmitter}:
 * </p>
 * <ul>
 * <li>{@link InboxReadException} → {@link InboxItemConsumptionFailed}</li>
 * <li>{@link InboxUpdateException} → {@link InboxItemStoreFailed}</li>
 * </ul>
 *
 * @see ErrorRouter
 */
public class FeederErrorRouter {
        /**
         * Creates the default error router for publisher operations.
         *
         * @param bus the event bus emitter used to publish failure events;
         *            must not be {@code null}
         * @return a pre-configured {@link ErrorRouter}
         */
        public static ErrorRouter defaults(EventBusEmitter bus) {
                return new ErrorRouter()
                        .on(InboxReadException.class, (e, cause) ->
                                bus.emit(InboxItemConsumptionFailed.builder()
                                        .errorMessage(e.getMessage()).build()))
                        .on(InboxUpdateException.class, (e, cause) ->
                                bus.emit(InboxItemStoreFailed.builder()
                                        .from(cause).errorMessage(e.getMessage()).build()))
                        .on(EventBusEmitException.class, (e, cause) ->
                                System.err.println(e.getMessage()));

        }
}
