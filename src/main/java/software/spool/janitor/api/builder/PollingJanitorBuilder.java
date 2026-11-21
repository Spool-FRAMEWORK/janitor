package software.spool.janitor.api.builder;

import software.spool.core.pipeline.ObservedStep;
import software.spool.core.pipeline.Pipeline;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.decorator.SafeEventPublisher;
import software.spool.core.port.decorator.SafeEventSubscriber;
import software.spool.core.port.decorator.SafeInboxUpdater;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.datalake.BronzeWriter;
import software.spool.core.port.quarantine.QuarantineRepository;
import software.spool.janitor.api.quarantine.QuarantinedEnvelopes;
import software.spool.core.adapter.otel.OpenTelemetryMetricsRegistry;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.metrics.SpoolMetrics;
import software.spool.core.port.watchdog.ModuleHeartBeat;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.janitor.api.Janitor;
import software.spool.janitor.api.strategy.PollingJanitorStrategy;
import software.spool.janitor.api.utils.JanitorErrorRouter;
import software.spool.janitor.internal.control.*;
import software.spool.janitor.internal.port.decorator.SafeInboxStatusQuery;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class PollingJanitorBuilder {
    private final ModuleHeartBeat heartBeat;
    private InboxStatusQuery reader;
    private InboxUpdater updater;
    private InboxEnvelopeRemover remover;
    private QuarantineRepository quarantineRepository;
    private BronzeWriter bronzeWriter;
    private EventPublisher publisher;
    private EventSubscriber subscriber;
    private PollingConfiguration pollingConfiguration;
    private ErrorRouter errorRouter;
    private Integer millisecondsThreshold;
    private Integer millisecondsTtl;
    private Integer millisecondsQuarantineTtl;
    private Long millisecondsQuarantineRetention;
    private Integer maxRetries;
    private Integer maxInboxUpdateAttempts;
    private final MetricsRegistry metricsRegistry = new OpenTelemetryMetricsRegistry();

    PollingJanitorBuilder(ModuleHeartBeat heartBeat) {
        this.heartBeat = heartBeat;
    }

    public PollingJanitorBuilder from(InboxStatusQuery reader) {
        this.reader = SafeInboxStatusQuery.of(reader);
        return this;
    }

    public PollingJanitorBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    public PollingJanitorBuilder removeWith(InboxEnvelopeRemover remover) {
        this.remover = remover;
        return this;
    }

    /**
     * Sets where the janitor keeps the record of each envelope it sends to quarantine. Required.
     *
     * @param quarantineRepository the quarantine records
     * @return this builder
     */
    public PollingJanitorBuilder quarantineIn(QuarantineRepository quarantineRepository) {
        this.quarantineRepository = quarantineRepository;
        return this;
    }

    /**
     * Sets the bronze layer where envelopes rejected by the mounter are written back when their quarantine is
     * resolved. Without it, those quarantine records cannot be resolved by this janitor.
     *
     * @param bronzeWriter the bronze layer of the data lake
     * @return this builder
     */
    public PollingJanitorBuilder rewriteBronzeIn(BronzeWriter bronzeWriter) {
        this.bronzeWriter = bronzeWriter;
        return this;
    }

    public PollingJanitorBuilder on(EventPublisher publisher) {
        this.publisher = SafeEventPublisher.of(publisher);
        return this;
    }

    public PollingJanitorBuilder subscribeWith(EventSubscriber subscriber) {
        this.subscriber = SafeEventSubscriber.of(subscriber);
        return this;
    }

    public PollingJanitorBuilder every(Duration interval) {
        this.pollingConfiguration = PollingConfiguration.every(interval);
        return this;
    }

    public PollingJanitorBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    public PollingJanitorBuilder withMillisecondsThreshold(Integer millisecondsThreshold) {
        this.millisecondsThreshold = millisecondsThreshold;
        return this;
    }

    public PollingJanitorBuilder withMillisecondsTtl(Integer millisecondsTtl) {
        this.millisecondsTtl = millisecondsTtl;
        return this;
    }

    /**
     * Sets how long an envelope stays in quarantine in the inbox before the janitor removes it from there. Its
     * quarantine record stays pending, so it can still be resolved.
     *
     * <p>Without a value the inbox keeps every quarantined envelope.</p>
     *
     * @param millisecondsQuarantineTtl the time in quarantine after which an envelope leaves the inbox, or {@code null} to keep them
     * @return this builder
     */
    public PollingJanitorBuilder withMillisecondsQuarantineTtl(Integer millisecondsQuarantineTtl) {
        this.millisecondsQuarantineTtl = millisecondsQuarantineTtl;
        return this;
    }

    /**
     * Sets how long a resolved quarantine record is kept before the janitor deletes it. Pending records are never
     * deleted.
     *
     * <p>Without a value every resolved record is kept.</p>
     *
     * @param millisecondsQuarantineRetention the time since a record was resolved after which it is deleted, or {@code null} to keep them
     * @return this builder
     */
    public PollingJanitorBuilder withMillisecondsQuarantineRetention(Long millisecondsQuarantineRetention) {
        this.millisecondsQuarantineRetention = millisecondsQuarantineRetention;
        return this;
    }

    public PollingJanitorBuilder withMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * How many rounds an event is tried when the inbox fails to apply it, before the janitor stops retrying it and
     * reports it to the error router. At least 1; 3 by default.
     *
     * @param maxInboxUpdateAttempts the attempts, or {@code null} for the default
     * @return this builder
     */
    public PollingJanitorBuilder withMaxInboxUpdateAttempts(Integer maxInboxUpdateAttempts) {
        this.maxInboxUpdateAttempts = maxInboxUpdateAttempts;
        return this;
    }

    public Janitor create() {
        Objects.requireNonNull(quarantineRepository, "QuarantineRepository required");
        return new Janitor(initializeStrategy(), getErrorRouter(), heartBeat, quarantinedEnvelopes());
    }

    private QuarantinedEnvelopes quarantinedEnvelopes() {
        return Optional.ofNullable(bronzeWriter)
                .map(bronze -> QuarantinedEnvelopes.of(updater, remover, bronze, quarantineRepository, publisher))
                .orElseGet(() -> QuarantinedEnvelopes.of(updater, remover, quarantineRepository, publisher));
    }

    private ErrorRouter getErrorRouter() {
        return Objects.requireNonNullElse(errorRouter, JanitorErrorRouter.defaults(publisher));
    }

    private PollingJanitorStrategy initializeStrategy() {
        return new PollingJanitorStrategy(reader, subscriber, initializeJanitorScheduleHandler(), pollingConfiguration,
                Objects.requireNonNullElse(maxInboxUpdateAttempts, 3), getErrorRouter());
    }

    private JanitorCycle initializeJanitorScheduleHandler() {
        MetricsRegistry.CounterMetric cyclesCompleted = metricsRegistry.counter(SpoolMetrics.Janitor.CYCLES_COMPLETED_TOTAL, SpoolMetrics.Janitor.CYCLES_COMPLETED_TOTAL_DESC, "1");
        MetricsRegistry.CounterMetric cyclesFailed = metricsRegistry.counter(SpoolMetrics.Janitor.CYCLES_FAILED_TOTAL, SpoolMetrics.Janitor.CYCLES_FAILED_TOTAL_DESC, "1");
        MetricsRegistry.TimerMetric cycleDuration = metricsRegistry.timer(SpoolMetrics.Janitor.CYCLE_DURATION, SpoolMetrics.Janitor.CYCLE_DURATION_DESC, "s");
        MetricsRegistry.CounterMetric recordsCleaned = metricsRegistry.counter(SpoolMetrics.Janitor.RECORDS_CLEANED_TOTAL, SpoolMetrics.Janitor.RECORDS_CLEANED_TOTAL_DESC, "1");
        return new JanitorScheduleHandler(initializePipeline(recordsCleaned), getErrorRouter(), cyclesCompleted, cyclesFailed, cycleDuration);
    }

    Pipeline<PipelineContext, PipelineContext> initializePipeline(MetricsRegistry.CounterMetric recordsCleaned) {
        Pipeline<PipelineContext, PipelineContext> expiry = Pipeline.<PipelineContext>start()
                .add(new ObservedStep<>("update-persisted", new UpdatePersistedEnvelopesStep(updater)))
                .add(new ObservedStep<>("quarantine-envelopes", new QuarantineFailedEnvelopesStep(updater, quarantineRepository, recordsCleaned)))
                .add(new ObservedStep<>("expired-envelopes",
                        new RemoveExpiredEnvelopesStep(getErrorRouter(), millis(millisecondsTtl), remover, reader, recordsCleaned)));
        Pipeline<PipelineContext, PipelineContext> withQuarantineExpiry = millisecondsQuarantineTtl == null ? expiry : expiry
                .add(new ObservedStep<>("expired-quarantine",
                        new PurgeExpiredQuarantineStep(getErrorRouter(), millis(millisecondsQuarantineTtl), reader, remover, recordsCleaned)));
        Pipeline<PipelineContext, PipelineContext> withQuarantineRetention = millisecondsQuarantineRetention == null ? withQuarantineExpiry : withQuarantineExpiry
                .add(new ObservedStep<>("resolved-quarantine",
                        new PurgeResolvedQuarantineStep(getErrorRouter(), Duration.ofMillis(millisecondsQuarantineRetention),
                                quarantineRepository, recordsCleaned)));
        return withQuarantineRetention
                .add(new ObservedStep<>("handle-stuck-envelopes",
                        new RepublishStuckEnvelopesStep(reader, updater, publisher, millis(millisecondsThreshold), Objects.requireNonNullElse(maxRetries, 3), recordsCleaned)));
    }

    /** A value that was not set stays null, and each step falls back to its own default. */
    private static Duration millis(Integer value) {
        return value == null ? null : Duration.ofMillis(value);
    }
}
