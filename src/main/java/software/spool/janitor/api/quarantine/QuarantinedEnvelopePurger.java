package software.spool.janitor.api.quarantine;

import software.spool.core.model.quarantine.Resolver;
import software.spool.core.model.vo.IdempotencyKey;

import java.util.Collection;

/**
 * Deletes envelopes in quarantine for good, and marks their quarantine records as purged by who did it.
 *
 * <p>
 * Each purge publishes an {@code EnvelopePurged} event so there is a trace of it. A key without a pending quarantine
 * record is skipped and its envelope is not touched.
 * </p>
 */
public interface QuarantinedEnvelopePurger {
    /**
     * @param idempotencyKeys the keys of the envelopes to purge
     * @param resolver        who purges them
     * @return the keys that were purged
     */
    Collection<IdempotencyKey> purge(Collection<IdempotencyKey> idempotencyKeys, Resolver resolver);
}
