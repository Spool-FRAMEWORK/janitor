package software.spool.janitor.internal.control;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopeStored;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Step;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.logging.Logger;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.metrics.SpoolMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class RepublishStuckEnvelopesStep implements Step<PipelineContext, PipelineContext> {
    private static final Logger LOG = LoggerFactory.getLogger(RepublishStuckEnvelopesStep.class);
    private final InboxStatusQuery reader;
    private final InboxUpdater updater;
    private final EventPublisher publisher;
    private final Duration threshold;
    private final int maxRetries;
    private final MetricsRegistry.CounterMetric recordsCleaned;

    public RepublishStuckEnvelopesStep(InboxStatusQuery reader, InboxUpdater updater, EventPublisher publisher, Duration threshold, int maxRetries, MetricsRegistry.CounterMetric recordsCleaned) {
        this.reader = reader;
        this.updater = updater;
        this.publisher = publisher;
        this.threshold = Objects.requireNonNullElse(threshold, Duration.ofMinutes(3));
        this.maxRetries = maxRetries;
        this.recordsCleaned = recordsCleaned;
    }

    @Override
    public PipelineContext apply(PipelineContext context) {
        reader.findByStatusModifiedBefore(EnvelopeStatus.CAPTURED, Instant.now().minus(threshold)).stream()
                .map(Envelope::retry)
                .forEach(this::handleRetry);
        return context;
    }

    private void handleRetry(Envelope envelope) {
        if (envelope.retries() >= maxRetries) {
            updater.update(envelope.idempotencyKey(), EnvelopeStatus.QUARANTINED);
            LOG.error("Quarantined Envelope {} after {} attempts", envelope, envelope.retries());
            recordsCleaned.add(1, Map.of(SpoolMetrics.Attributes.REASON, "retries_exhausted"));
        } else {
            updater.update(envelope);
            LOG.warn("Republished Envelope {} | current attempt: {}", envelope, envelope.retries());
            publisher.publish(buildEventFrom(envelope));
        }
    }

    private EnvelopeStored buildEventFrom(Envelope envelope) {
        return EnvelopeStored.builder()
                .correlationId(envelope.metadata().get(EventMetadataKey.CORRELATION_ID))
                .idempotencyKey(envelope.idempotencyKey())
                .build();
    }
}
