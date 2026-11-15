package software.spool.janitor.api.quarantine;

import software.spool.core.model.vo.IdempotencyKey;

import java.util.Collection;
import java.util.Optional;

/**
 * Sends envelopes in quarantine back to the inbox so the ingester processes them again.
 *
 * <p>
 * Each republished envelope's quarantine record is marked as republished or corrected, and an
 * {@code EnvelopeRepublished} event is published so there is a trace of it. A key without a pending quarantine record
 * is skipped and its envelope is not touched.
 * </p>
 */
public interface QuarantinedEnvelopeRepublisher {
    /**
     * Returns envelopes to the inbox as they were, with their retries back to zero, even if the inbox had already
     * removed them.
     *
     * @param idempotencyKeys the keys of the envelopes to republish
     * @return the keys that were republished
     */
    Collection<IdempotencyKey> republish(Collection<IdempotencyKey> idempotencyKeys);

    /**
     * Replaces an envelope with a new one that carries the corrected payload, under the key calculated from that
     * payload. The new envelope keeps the original key in its {@code CORRECTED_FROM} metadata.
     *
     * @param idempotencyKey   the key of the envelope to correct
     * @param correctedPayload the payload that replaces the original one
     * @return the key of the new envelope, or empty if the envelope was not in quarantine
     */
    Optional<IdempotencyKey> republish(IdempotencyKey idempotencyKey, byte[] correctedPayload);
}
