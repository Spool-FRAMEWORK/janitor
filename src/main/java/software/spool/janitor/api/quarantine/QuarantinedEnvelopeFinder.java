package software.spool.janitor.api.quarantine;

import software.spool.core.model.quarantine.QuarantinePage;
import software.spool.core.model.quarantine.QuarantinePageRequest;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.vo.IdempotencyKey;

import java.util.Optional;

/**
 * Finds what is in quarantine, to review it before resolving it.
 */
public interface QuarantinedEnvelopeFinder {
    Optional<QuarantinedRecord> find(IdempotencyKey idempotencyKey);

    /**
     * One page of the records in a status, in the order of their keys.
     */
    QuarantinePage list(QuarantinePageRequest request);
}
