package software.spool.janitor.api.builder;

import org.junit.jupiter.api.Test;
import software.spool.core.adapter.memory.InMemoryQuarantineRepository;
import software.spool.core.exception.InboxUpdateException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.Event;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.bus.Subscription;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.core.utils.polling.PollingPolicy;
import software.spool.core.utils.polling.PollingScheduler;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.janitor.api.fixture.FakeInbox;
import software.spool.janitor.api.strategy.PollingJanitorStrategy;
import software.spool.janitor.internal.control.JanitorScheduleHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Runs whole janitor cycles, with the real steps, against an inbox that fails the times a test asks. */
class JanitorInboxFailureTest {

    private final FakeInbox inbox = new FakeInbox();
    private final FlakyInboxUpdater updater = new FlakyInboxUpdater(inbox);
    private final InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
    private final FakeBus bus = new FakeBus();
    private final ManualScheduler scheduler = new ManualScheduler();

    @Test
    void quarantinedEvent_inboxFailsOnce_endsInQuarantineRecordedOnce() {
        inbox.with(captured("quarantined-key"));
        updater.failNext(EnvelopeStatus.QUARANTINED, 1);
        start();

        bus.emit(quarantined("quarantined-key"));
        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(inbox.stored("quarantined-key")).map(Envelope::status).contains(EnvelopeStatus.QUARANTINED);
        assertThat(repository.find(IdempotencyKey.of("quarantined-key")).orElseThrow().history()).hasSize(1);
    }

    @Test
    void persistedEvent_inboxFailsOnce_endsInPersisted() {
        inbox.with(captured("persisted-key"));
        updater.failNext(EnvelopeStatus.PERSISTED, 1);
        start();

        bus.emit(persisted("persisted-key"));
        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(inbox.stored("persisted-key")).map(Envelope::status).contains(EnvelopeStatus.PERSISTED);
    }

    @Test
    void persistedEventFails_quarantinedEventOfTheSameCycle_isStillApplied() {
        inbox.with(captured("persisted-key"), captured("quarantined-key"));
        updater.failNext(EnvelopeStatus.PERSISTED, 1);
        start();

        bus.emit(persisted("persisted-key"));
        bus.emit(quarantined("quarantined-key"));
        scheduler.runCycle();

        assertThat(inbox.stored("quarantined-key")).map(Envelope::status).contains(EnvelopeStatus.QUARANTINED);
        assertThat(inbox.stored("persisted-key")).map(Envelope::status).contains(EnvelopeStatus.CAPTURED);
    }

    private void start() {
        PollingJanitorBuilder builder = JanitorBuilderFactory.watchdog(null, "janitor-test")
                .polling()
                .from(inbox)
                .with(updater)
                .removeWith(inbox)
                .on(mock(EventPublisher.class))
                .subscribeWith(bus)
                .quarantineIn(repository)
                .every(Duration.ofSeconds(1));
        JanitorScheduleHandler handler = new JanitorScheduleHandler(builder.initializePipeline((value, attributes) -> {}),
                new ErrorRouter(), MetricsRegistry.NOOP.counter("completed", "", "1"), MetricsRegistry.NOOP.counter("failed", "", "1"),
                MetricsRegistry.NOOP.timer("duration", "", "ms"));
        new PollingJanitorStrategy(inbox, bus, handler, new PollingConfiguration(scheduler, PollingPolicy.every(Duration.ofSeconds(1))),
                3, new ErrorRouter()).execute(CancellationToken.create());
    }

    private static Envelope captured(String key) {
        Instant capturedAt = Instant.now();
        return new Envelope(IdempotencyKey.of(key), new EventMetadata(), MediaType.of("application/json"), "{}".getBytes(),
                EnvelopeStatus.CAPTURED, 0, capturedAt, capturedAt);
    }

    private static EnvelopePersisted persisted(String key) {
        return EnvelopePersisted.builder().idempotencyKey(IdempotencyKey.of(key)).partitionKey(PartitionKey.ofNow()).build();
    }

    private static EnvelopeQuarantined quarantined(String key) {
        return EnvelopeQuarantined.builder().idempotencyKey(IdempotencyKey.of(key))
                .reason(QuarantineReason.VALIDATION_FAILED).violations(List.of("violation")).build();
    }

    /** Fails the next updates to a status as many times as asked, then updates the fake inbox. */
    private static final class FlakyInboxUpdater implements InboxUpdater {
        private final FakeInbox inbox;
        private final Map<EnvelopeStatus, Integer> failuresLeft = new HashMap<>();

        FlakyInboxUpdater(FakeInbox inbox) {
            this.inbox = inbox;
        }

        void failNext(EnvelopeStatus status, int times) {
            failuresLeft.put(status, times);
        }

        @Override
        public Collection<Envelope> update(Collection<IdempotencyKey> idempotencyKeys, EnvelopeStatus status) {
            int left = failuresLeft.getOrDefault(status, 0);
            if (left > 0) {
                failuresLeft.put(status, left - 1);
                throw new InboxUpdateException(idempotencyKeys, "inbox unavailable");
            }
            return inbox.update(idempotencyKeys, status);
        }
    }

    private static final class ManualScheduler implements PollingScheduler {
        private Runnable task;

        @Override
        public void schedule(Runnable task, PollingPolicy policy, CancellationToken token) {
            this.task = task;
        }

        void runCycle() {
            task.run();
        }
    }

    private static final class FakeBus implements EventSubscriber {
        private final Map<Class<?>, Handler<?>> handlers = new HashMap<>();
        private final List<Event> emitted = new ArrayList<>();

        @Override
        public <E extends Event> Subscription subscribe(Class<E> eventType, Handler<E> handler) {
            handlers.put(eventType, handler);
            return Subscription.NULL;
        }

        @SuppressWarnings("unchecked")
        <E extends Event> void emit(E event) {
            emitted.add(event);
            ((Handler<E>) handlers.get(event.getClass())).handle(event);
        }
    }
}
