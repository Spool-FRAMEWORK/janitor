package software.spool.janitor.api.quarantine;

import software.spool.core.model.vo.IdempotencyKey;

import java.util.Collection;

/**
 * Deletes envelopes in quarantine for good, together with their quarantine records.
 *
 * <p>
 * Each purge publishes an {@code EnvelopePurged} event so there is a trace of it. A key that is not in quarantine is
 * skipped and its envelope is not touched.
 * </p>
 */
public interface QuarantinedEnvelopePurger {
    /**
     * @param idempotencyKeys the keys of the envelopes to purge
     * @return the keys that were purged
     */
    Collection<IdempotencyKey> purge(Collection<IdempotencyKey> idempotencyKeys);
}
