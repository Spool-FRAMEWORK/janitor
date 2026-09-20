package software.spool.janitor.api.strategy;

import org.junit.jupiter.api.Test;
import software.spool.core.model.Event;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.bus.Subscription;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.core.utils.polling.PollingPolicy;
import software.spool.core.utils.polling.PollingScheduler;
import software.spool.janitor.internal.control.EventsDTO;

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

    @Test
    void execute_concurrentPublishers_cyclesReadTheEventsWithoutFailing() throws Exception {
        start(dto -> {});

        publishConcurrently(4, 5_000);

        assertThat(failures).isEmpty();
    }

    @Test
    void execute_eventsBeforeCycle_areDeliveredOnceAndNotRepeated() {
        start(dto -> {});

        bus.emit(persisted("a"));
        bus.emit(persisted("b"));
        scheduler.runCycle();
        scheduler.runCycle();

        assertThat(cycles.get(0).persisted).containsExactly("a", "b");
        assertThat(cycles.get(1).persisted).isEmpty();
    }

    @Test
    void execute_persistedEventEmittedDuringCycle_isDeliveredInNextCycle() {
        // While the janitor handles a cycle it republishes stuck envelopes, and the ingester
        // answers with EnvelopePersisted in the same call. That event must not be lost.
        start(dto -> {
            if (cycles.size() == 1) bus.emit(persisted("during"));
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
        start(dto -> {});

        publishConcurrently(publishers, perPublisher);

        List<String> delivered = cycles.stream().flatMap(c -> c.persisted.stream()).toList();
        assertThat(delivered).hasSize(publishers * perPublisher).doesNotHaveDuplicates();
    }

    private void start(Handler<EventsDTO> extra) {
        Handler<EventsDTO> recording = dto -> {
            try {
                cycles.add(new Cycle(dto));
            } catch (RuntimeException e) {
                failures.add(e);
                throw e;
            }
            extra.handle(dto);
        };
        PollingConfiguration configuration =
                new PollingConfiguration(scheduler, PollingPolicy.every(Duration.ofSeconds(1)));
        new PollingJanitorStrategy(status -> List.of(), bus, recording, configuration)
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
        public void schedule(Runnable task, PollingPolicy policy, CancellationToken token) {
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
