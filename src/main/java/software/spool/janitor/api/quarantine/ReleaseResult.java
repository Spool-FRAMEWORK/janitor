package software.spool.janitor.api.quarantine;

import software.spool.core.model.vo.IdempotencyKey;

import java.util.List;

/**
 * What a release did with the keys it was given.
 *
 * @param released the keys that were in quarantine and were requeued or discarded
 * @param skipped  the keys that were not in quarantine, or do not exist, and were left untouched
 */
public record ReleaseResult(List<IdempotencyKey> released, List<IdempotencyKey> skipped) {
}
