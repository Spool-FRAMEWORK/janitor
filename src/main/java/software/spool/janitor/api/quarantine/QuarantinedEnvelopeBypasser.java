package software.spool.janitor.api.quarantine;

import software.spool.core.model.vo.IdempotencyKey;

import java.util.Optional;

/**
 * Gives an envelope in quarantine as valid although a validator rejected it.
 *
 * <p>
 * The envelope goes back to where it stayed, as it was, with the {@code VALIDATION_BYPASS} metadata that makes
 * validation let it through from then on. Only that envelope is affected: others of the same source are still
 * validated. Its quarantine record is marked as bypassed with the reason, and an {@code EnvelopeRepublished} event is
 * published. A key without a pending quarantine record is skipped.
 * </p>
 */
public interface QuarantinedEnvelopeBypasser {
    /**
     * @param idempotencyKey the key of the envelope to let through
     * @param reason         why the envelope is valid although a validator rejected it; required
     * @return the key of the envelope, or empty if it was not pending in quarantine
     * @throws IllegalArgumentException if the reason is empty
     */
    Optional<IdempotencyKey> bypass(IdempotencyKey idempotencyKey, String reason);
}
