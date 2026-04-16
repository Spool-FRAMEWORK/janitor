package software.spool.feeder.api;

import software.spool.core.model.spool.SpoolModule;
import software.spool.core.model.spool.SpoolNode;
import software.spool.core.port.health.ModuleHealthPayload;
import software.spool.core.port.watchdog.ModuleHeartBeat;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.feeder.api.strategy.FeederStrategy;

import java.util.Objects;

/**
 * Main API entry point for the publishing lifecycle.
 *
 * <p>
 * A {@code Feeder} wraps a {@link FeederStrategy} and manages start/stop
 * semantics with built-in error routing. It acts as the bridge between the
 * inbox and downstream event channels.
 * </p>
 *
 * <p>
 * Use the fluent builders in
 * {@link software.spool.feeder.api.builder.FeederBuilderFactory}
 * to construct instances:
 * </p>
 *
 * <pre>{@code
 * Feeder feeder = FeederBuilderFactory.reactive()
 *         .from(eventBusListener)
 *         .with(inboxUpdater)
 *         .on(eventBusEmitter)
 *         .withErrorRouter(errorRouter)
 *         .create();
 *
 * feeder.startPublishing();
 * }</pre>
 *
 * @see FeederStrategy
 * @see software.spool.feeder.api.builder.FeederBuilderFactory
 */
public class Feeder implements SpoolModule {
    private final FeederStrategy strategy;
    private CancellationToken token;
    private final ErrorRouter errorRouter;
    private final ModuleHeartBeat heartBeat;

    /**
     * Creates a new {@code Feeder} with the given strategy and error router.
     *
     * @param strategy    the publishing strategy to use; must not be {@code null}
     * @param errorRouter the error router for handling exceptions; must not be
     *                    {@code null}
     */
    public Feeder(FeederStrategy strategy, ErrorRouter errorRouter, ModuleHeartBeat heartBeat) {
        this.strategy = Objects.requireNonNull(strategy);
        this.errorRouter = Objects.requireNonNull(errorRouter);
        this.heartBeat = heartBeat;
        this.token  = CancellationToken.NOOP;
    }

    /**
     * Starts the publishing process.
     *
     * <p>
     *
     * the resulting subscription. Calling this method when publishing is
     * already active has no effect. Any exceptions are routed through the
     * configured {@link ErrorRouter}.
     * </p>
     */
    @Override
    public void start(SpoolNode.StartPermit permit) {
        if (token.isActive()) return;
        Objects.requireNonNull(permit);
        token = CancellationToken.create();
        try {
            strategy.execute(token);
            heartBeat.start();
        } catch (Exception e) {
            errorRouter.dispatch(e);
        }
    }

    /**
     * Stops the publishing process.
     *
     * <p>
     * the subscription. Calling this method when publishing is already
     * stopped has no effect. Any exceptions are routed through the
     * configured {@link ErrorRouter}.
     * </p>
     */
    @Override
    public void stop(SpoolNode.StartPermit permit) {
        if (!token.isActive()) return;
        Objects.requireNonNull(permit);
        try {
            token.cancel();
            heartBeat.stop();
            token = CancellationToken.NOOP;
        } catch (Exception e) {
            errorRouter.dispatch(e);
        }
    }

    @Override
    public ModuleHealthPayload checkHealth() {
        return token.isActive() ? ModuleHealthPayload.healthy(heartBeat.identity().moduleId()) : ModuleHealthPayload.degraded(heartBeat.identity().moduleId(), null);
    }
}
