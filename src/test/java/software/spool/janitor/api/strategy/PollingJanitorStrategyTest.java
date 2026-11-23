package software.spool.janitor.api.strategy;

import org.junit.jupiter.api.Test;
import software.spool.core.model.Event;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.event.QuarantinedEnvelopeReplaced;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.schedule.Schedule;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.bus.Subscription;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.core.utils.polling.PollingScheduler;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.janitor.internal.control.EventsDTO;
import software.spool.janitor.internal.control.JanitorCycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PollingJanitorStrategyTest {

    private final FakeBus bus = new FakeBus();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<Cycle> cycles = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final List<Exception> reported = new ArrayList<>();

    @Test
    void execute_concurrentPublishers_cyclesReadTheEventsWithoutFailing() throws Exception {
        start(dto -> EventsDTO.none());

        publishConcurrently(4, 5_000);

        assertThat(failures).isEmpty();
    }

    @Test
    void execute_eventsBeforeCycle_areDeliveredOnceAndNotRepeated() {
        start(dto -> EventsDTO.none());

        bus.emit(persisted("a"));
        bus.emit(persisted("b"));
        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(cycles.get(0).persisted).containsExactly("a", "b");
        assertThat(cycles.get(1).persisted).isEmpty();
    }

    @Test
    void execute_persistedEventEmittedDuringCycle_isDeliveredInNextCycle() {
        start(dto -> {
            if (cycles.size() == 1) bus.emit(persisted("during"));
            return EventsDTO.none();
        });

        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(cycles.get(0).persisted).isEmpty();
        assertThat(cycles.get(1).persisted).containsExactly("during");
    }

    @Test
    void execute_quarantinedEventEmittedDuringCycle_isDeliveredInNextCycle() {
        start(dto -> {
            if (cycles.size() == 1) bus.emit(quarantined("during"));
            return EventsDTO.none();
        });

        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(cycles.get(0).quarantined).isEmpty();
        assertThat(cycles.get(1).quarantined).containsExactly("during");
    }

    @Test
    void execute_concurrentPublishers_noEventIsLostOrDuplicated() throws Exception {
        int publishers = 4;
        int perPublisher = 5_000;
        start(dto -> EventsDTO.none());

        publishConcurrently(publishers, perPublisher);

        List<String> delivered = cycles.stream().flatMap(c -> c.persisted.stream()).toList();
        assertThat(delivered).hasSize(publishers * perPublisher).doesNotHaveDuplicates();
    }

    @Test
    void execute_eventsTheCycleCouldNotApply_areDeliveredAgainInTheNextCycle() {
        start(dto -> cycles.size() == 1 ? dto : EventsDTO.none(), 3);

        bus.emit(persisted("persisted-key"));
        bus.emit(quarantined("quarantined-key"));
        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(cycles.get(1).persisted).containsExactly("persisted-key");
        assertThat(cycles.get(1).quarantined).containsExactly("quarantined-key");
        assertThat(reported).isEmpty();
    }

    @Test
    void execute_eventStillNotAppliedAfterTheMaximumAttempts_isReportedAndNotRetried() {
        start(dto -> dto, 2);

        bus.emit(persisted("persisted-key"));
        scheduler.runCycle();
        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(cycles.get(1).persisted).containsExactly("persisted-key");
        assertThat(cycles.get(2).persisted).isEmpty();
        assertThat(reported).singleElement().satisfies(exception -> assertThat(exception)
                .hasMessageContaining("persisted-key")
                .hasMessageContaining("2 attempts"));
    }

    @Test
    void execute_eventAppliedOnItsSecondAttempt_isNotReported() {
        start(dto -> cycles.size() == 1 ? dto : EventsDTO.none(), 2);

        bus.emit(persisted("persisted-key"));
        scheduler.runCycle();
        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(reported).isEmpty();
    }

    @Test
    void execute_aCaptureThatReplacedAQuarantinedEnvelope_marksItsQuarantineAsReplaced() {
        List<IdempotencyKey> replaced = new ArrayList<>();
        new PollingJanitorStrategy(status -> List.of(), bus, dto -> EventsDTO.none(),
                new PollingConfiguration(scheduler, Schedule.every(Duration.ofSeconds(1))), 3,
                new ErrorRouter().orElse((exception, context) -> reported.add(exception)), replaced::add)
                .execute(CancellationToken.create());

        bus.emit(QuarantinedEnvelopeReplaced.of(IdempotencyKey.of("boe-a-1"), "corr-1"));

        assertThat(replaced).containsExactly(IdempotencyKey.of("boe-a-1"));
    }

    @Test
    void execute_markingAQuarantineAsReplacedThatFails_isReported() {
        new PollingJanitorStrategy(status -> List.of(), bus, dto -> EventsDTO.none(),
                new PollingConfiguration(scheduler, Schedule.every(Duration.ofSeconds(1))), 3,
                new ErrorRouter().orElse((exception, context) -> reported.add(exception)),
                key -> { throw new IllegalStateException("the quarantine could not be read"); })
                .execute(CancellationToken.create());

        bus.emit(QuarantinedEnvelopeReplaced.of(IdempotencyKey.of("boe-a-1"), "corr-1"));

        assertThat(reported).singleElement().satisfies(exception ->
                assertThat(exception).hasMessageContaining("the quarantine could not be read"));
    }

    private void start(JanitorCycle extra) {
        start(extra, 3);
    }

    private void start(JanitorCycle extra, int maxInboxUpdateAttempts) {
        JanitorCycle recording = dto -> {
            try {
                cycles.add(new Cycle(dto));
            } catch (RuntimeException e) {
                failures.add(e);
                throw e;
            }
            return extra.run(dto);
        };
        PollingConfiguration configuration =
                new PollingConfiguration(scheduler, Schedule.every(Duration.ofSeconds(1)));
        ErrorRouter errorRouter = new ErrorRouter().orElse((exception, context) -> reported.add(exception));
        new PollingJanitorStrategy(status -> List.of(), bus, recording, configuration, maxInboxUpdateAttempts, errorRouter)
                .execute(CancellationToken.create());
    }

    /** Several threads publish while the test thread keeps running cycles, then one last cycle. */
    private void publishConcurrently(int publishers, int perPublisher) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(publishers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(publishers);
        for (int p = 0; p < publishers; p++) {
            int publisher = p;
            pool.submit(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perPublisher; i++) bus.emit(persisted(publisher + "-" + i));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        go.countDown();
        while (!done.await(1, TimeUnit.MILLISECONDS)) scheduler.runCycle();
        scheduler.runCycle();
        pool.shutdownNow();
    }

    private static EnvelopePersisted persisted(String key) {
        return EnvelopePersisted.builder()
                .idempotencyKey(IdempotencyKey.of(key))
                .partitionKey(PartitionKey.ofNow())
                .build();
    }

    private static EnvelopeQuarantined quarantined(String key) {
        return EnvelopeQuarantined.builder()
                .idempotencyKey(IdempotencyKey.of(key))
                .reason(QuarantineReason.VALIDATION_FAILED)
                .violations(List.of("violation"))
                .build();
    }

    /** What one cycle received, copied on the spot because the janitor may reuse its collections. */
    private static final class Cycle {
        final List<String> persisted;
        final List<String> quarantined;

        Cycle(EventsDTO dto) {
            this.persisted = dto.envelopesPersisted().stream().map(e -> e.idempotencyKey().value()).toList();
            this.quarantined = dto.envelopesQuarantined().stream().map(e -> e.idempotencyKey().value()).toList();
        }
    }

    /** Keeps the scheduled task so a test decides exactly when each cycle runs. */
    private static final class ManualScheduler implements PollingScheduler {
        private Runnable task;

        @Override
        public void schedule(Runnable task, Schedule schedule, CancellationToken token) {
            this.task = task;
        }

        void runCycle() {
            task.run();
        }
    }

    /** In-memory bus that hands each event to the handler in the thread that emits it. */
    private static final class FakeBus implements EventSubscriber {
        private final Map<Class<?>, Handler<?>> handlers = new ConcurrentHashMap<>();

        @Override
        public <E extends Event> Subscription subscribe(Class<E> eventType, Handler<E> handler) {
            handlers.put(eventType, handler);
            return Subscription.NULL;
        }

        @SuppressWarnings("unchecked")
        <E extends Event> void emit(E event) {
            ((Handler<E>) handlers.get(event.getClass())).handle(event);
        }
    }
}
