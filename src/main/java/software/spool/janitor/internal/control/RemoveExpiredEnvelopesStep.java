package software.spool.janitor.internal.control;

import software.spool.janitor.api.builder.PollingJanitorBuilder;
import software.spool.core.port.idempotency.ProcessedKeyWindow;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.pipeline.Step;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.metrics.SpoolMetrics;
import software.spool.core.utils.routing.ErrorRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class RemoveExpiredEnvelopesStep implements Step<PipelineContext, PipelineContext> {
    private final ErrorRouter errorRouter;
    private final Duration ttl;
    private final InboxEnvelopeRemover remover;
    private final InboxStatusQuery reader;
    private final MetricsRegistry.CounterMetric recordsCleaned;
    private final EnvelopeStatus status;
    private final String reason;
    private final Optional<ProcessedKeyWindow> deduplication;

    public RemoveExpiredEnvelopesStep(ErrorRouter errorRouter, Duration ttl, InboxEnvelopeRemover remover, InboxStatusQuery reader, MetricsRegistry.CounterMetric recordsCleaned) {
        this(errorRouter, ttl, remover, reader, recordsCleaned, Optional.empty());
    }

    /**
     * Removes the persisted envelopes past their TTL. With a deduplication window, it first remembers the key of each
     * one from the moment it was processed, so the same record is still a duplicate after its envelope is gone, and
     * forgets the keys processed longer than the window ago (ADR-033).
     */
    public RemoveExpiredEnvelopesStep(ErrorRouter errorRouter, Duration ttl, InboxEnvelopeRemover remover, InboxStatusQuery reader,
                                      MetricsRegistry.CounterMetric recordsCleaned, Optional<ProcessedKeyWindow> deduplication) {
        this(errorRouter, ttl, remover, reader, recordsCleaned, EnvelopeStatus.PERSISTED, "expired", deduplication);
    }

    public RemoveExpiredEnvelopesStep(ErrorRouter errorRouter, Duration ttl, InboxEnvelopeRemover remover, InboxStatusQuery reader, MetricsRegistry.CounterMetric recordsCleaned, EnvelopeStatus status, String reason) {
        this(errorRouter, ttl, remover, reader, recordsCleaned, status, reason, Optional.empty());
    }

    private RemoveExpiredEnvelopesStep(ErrorRouter errorRouter, Duration ttl, InboxEnvelopeRemover remover, InboxStatusQuery reader,
                                       MetricsRegistry.CounterMetric recordsCleaned, EnvelopeStatus status, String reason,
                                       Optional<ProcessedKeyWindow> deduplication) {
        this.errorRouter = errorRouter;
        this.ttl = Objects.requireNonNullElse(ttl, PollingJanitorBuilder.DEFAULT_TTL);
        this.remover = remover;
        this.reader = reader;
        this.recordsCleaned = recordsCleaned;
        this.status = status;
        this.reason = reason;
        this.deduplication = deduplication;
    }

    @Override
    public PipelineContext apply(PipelineContext context) {
        try {
            long[] count = {0};
            reader.findByStatusModifiedBefore(status, Instant.now().minus(ttl)).stream()
                    .peek(this::rememberIfDeduplicating)
                    .map(Envelope::idempotencyKey)
                    .peek(k -> count[0]++)
                    .forEach(remover::remove);
            deduplication.ifPresent(ProcessedKeyWindow::forgetExpired);
            if (count[0] > 0) {
                recordsCleaned.add(count[0], Map.of(SpoolMetrics.Attributes.REASON, reason));
            }
            return context;
        } catch (Exception e) {
            errorRouter.dispatch(e);
            throw e;
        }
    }

    private void rememberIfDeduplicating(Envelope envelope) {
        deduplication.ifPresent(window -> window.remember(envelope.idempotencyKey(),
                Optional.ofNullable(envelope.updatedAt()).orElse(envelope.capturedAt())));
    }
}
