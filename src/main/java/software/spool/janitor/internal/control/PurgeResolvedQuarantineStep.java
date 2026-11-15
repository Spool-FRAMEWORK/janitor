package software.spool.janitor.internal.control;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.quarantine.QuarantineStatus;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Step;
import software.spool.core.port.logging.Logger;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.metrics.SpoolMetrics;
import software.spool.core.port.quarantine.QuarantineRepository;
import software.spool.core.utils.routing.ErrorRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Deletes the quarantine records that were resolved longer ago than the retention. Pending records are never deleted.
 */
public class PurgeResolvedQuarantineStep implements Step<PipelineContext, PipelineContext> {
    private static final Logger LOG = LoggerFactory.getLogger(PurgeResolvedQuarantineStep.class);

    private final ErrorRouter errorRouter;
    private final Duration retention;
    private final QuarantineRepository quarantineRepository;
    private final MetricsRegistry.CounterMetric recordsCleaned;

    public PurgeResolvedQuarantineStep(ErrorRouter errorRouter, Duration retention, QuarantineRepository quarantineRepository,
                                       MetricsRegistry.CounterMetric recordsCleaned) {
        this.errorRouter = errorRouter;
        this.retention = retention;
        this.quarantineRepository = quarantineRepository;
        this.recordsCleaned = recordsCleaned;
    }

    @Override
    public PipelineContext apply(PipelineContext context) {
        try {
            Instant limit = Instant.now().minus(retention);
            List<QuarantinedRecord> expired = Arrays.stream(QuarantineStatus.values())
                    .filter(status -> status != QuarantineStatus.PENDING)
                    .flatMap(status -> quarantineRepository.findByStatus(status).stream())
                    .filter(quarantinedRecord -> quarantinedRecord.resolution().resolvedAt()
                            .map(resolvedAt -> resolvedAt.isBefore(limit))
                            .orElse(false))
                    .toList();
            if (expired.isEmpty()) return context;
            expired.forEach(quarantinedRecord -> quarantineRepository.delete(quarantinedRecord.idempotencyKey()));
            recordsCleaned.add(expired.size(), Map.of(SpoolMetrics.Attributes.REASON, "quarantine_resolved"));
            LOG.info("Deleted {} quarantine records resolved before {}", expired.size(), limit);
            return context;
        } catch (Exception e) {
            errorRouter.dispatch(e);
            throw e;
        }
    }
}
