package software.spool.janitor.api.quarantine;

import software.spool.core.exception.SpoolException;
import software.spool.core.model.vo.IdempotencyKey;

/**
 * Thrown when a quarantine record cannot be resolved the way it was asked, such as letting a mount result through
 * validation. The record stays pending.
 */
public class ResolutionNotAllowedException extends SpoolException {
    private final IdempotencyKey idempotencyKey;

    public ResolutionNotAllowedException(IdempotencyKey idempotencyKey, String message) {
        super(message);
        this.idempotencyKey = idempotencyKey;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }
}
