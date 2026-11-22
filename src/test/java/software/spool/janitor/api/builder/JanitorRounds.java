package software.spool.janitor.api.builder;

import software.spool.core.model.Event;
import software.spool.core.model.schedule.Schedule;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.idempotency.ProcessedKeyRepository;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.quarantine.QuarantineRepository;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.janitor.api.fixture.DirectEventBus;
import software.spool.janitor.api.fixture.FakeInbox;
import software.spool.janitor.api.fixture.ManualScheduler;
import software.spool.janitor.api.strategy.PollingJanitorStrategy;
import software.spool.janitor.internal.control.JanitorScheduleHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;

/**
 * A janitor with the real pipeline whose rounds run when a test asks, built here because the pipeline is only
 * reachable from the builder's package.
 */
public final class JanitorRounds {
    private final DirectEventBus bus = new DirectEventBus();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<Exception> reported = new ArrayList<>();

    public JanitorRounds(FakeInbox inbox, InboxUpdater updater, QuarantineRepository quarantineRepository,
                         int maxInboxUpdateAttempts, Optional<ProcessedKeyRepository> processedKeys) {
        PollingJanitorBuilder builder = JanitorBuilderFactory.watchdog(null, "janitor-test")
                .polling()
                .from(inbox)
                .with(updater)
                .removeWith(inbox)
                .on(mock(EventPublisher.class))
                .subscribeWith(bus)
                .quarantineIn(quarantineRepository)
                .schedule(Schedule.every(Duration.ofSeconds(1)));
        processedKeys.ifPresent(builder::rememberProcessedKeysIn);
        JanitorScheduleHandler handler = new JanitorScheduleHandler(builder.initializePipeline((value, attributes) -> {}),
                new ErrorRouter(), MetricsRegistry.NOOP.counter("completed", "", "1"),
                MetricsRegistry.NOOP.counter("failed", "", "1"), MetricsRegistry.NOOP.timer("duration", "", "ms"));
        ErrorRouter errorRouter = new ErrorRouter().orElse((exception, context) -> reported.add(exception));
        new PollingJanitorStrategy(inbox, bus, handler,
                new PollingConfiguration(scheduler, Schedule.every(Duration.ofSeconds(1))),
                maxInboxUpdateAttempts, errorRouter).execute(CancellationToken.create());
    }

    public <E extends Event> void learn(E event) {
        bus.emit(event);
    }

    public void run(int rounds) {
        for (int round = 0; round < rounds; round++) scheduler.runRound();
    }

    public List<Exception> reported() {
        return List.copyOf(reported);
    }
}
