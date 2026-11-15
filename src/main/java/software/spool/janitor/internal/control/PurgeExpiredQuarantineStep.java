package software.spool.janitor.internal.control;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Step;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.logging.Logger;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.metrics.SpoolMetrics;
import software.spool.core.utils.routing.ErrorRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Removes from the inbox the envelopes that have been in quarantine for longer than the TTL. Their quarantine records
 * stay pending: the inbox only stops holding a copy, and the record can still be resolved.
 */
public class PurgeExpiredQuarantineStep implements Step<PipelineContext, PipelineContext> {
    private static final Logger LOG = LoggerFactory.getLogger(PurgeExpiredQuarantineStep.class);

    private final ErrorRouter errorRouter;
    private final Duration ttl;
    private final InboxStatusQuery reader;
    private final InboxEnvelopeRemover remover;
    private final MetricsRegistry.CounterMetric recordsCleaned;

    public PurgeExpiredQuarantineStep(ErrorRouter errorRouter, Duration ttl, InboxStatusQuery reader,
                                      InboxEnvelopeRemover remover, MetricsRegistry.CounterMetric recordsCleaned) {
        this.errorRouter = errorRouter;
        this.ttl = ttl;
        this.reader = reader;
        this.remover = remover;
        this.recordsCleaned = recordsCleaned;
    }

    @Override
    public PipelineContext apply(PipelineContext context) {
        try {
            List<IdempotencyKey> expired = reader.findByStatusModifiedBefore(EnvelopeStatus.QUARANTINED, Instant.now().minus(ttl))
                    .stream()
                    .map(Envelope::idempotencyKey)
                    .toList();
            if (expired.isEmpty()) return context;
            Collection<Envelope> removed = remover.remove(expired);
            if (!removed.isEmpty()) {
                recordsCleaned.add(removed.size(), Map.of(SpoolMetrics.Attributes.REASON, "quarantine_expired"));
                LOG.info("Removed {} envelopes from the inbox after their quarantine TTL; their quarantine records stay pending",
                        removed.size());
            }
            return context;
        } catch (Exception e) {
            errorRouter.dispatch(e);
            throw e;
        }
    }
}
