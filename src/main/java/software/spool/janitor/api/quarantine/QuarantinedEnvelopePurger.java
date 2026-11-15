package software.spool.janitor.api.quarantine;

import software.spool.core.model.vo.IdempotencyKey;

import java.util.Collection;

/**
 * Deletes envelopes in quarantine for good, and marks their quarantine records as purged.
 *
 * <p>
 * Each purge publishes an {@code EnvelopePurged} event so there is a trace of it. A key without a pending quarantine
 * record is skipped and its envelope is not touched.
 * </p>
 */
public interface QuarantinedEnvelopePurger {
    /**
     * @param idempotencyKeys the keys of the envelopes to purge
     * @return the keys that were purged
     */
    Collection<IdempotencyKey> purge(Collection<IdempotencyKey> idempotencyKeys);
}
