package software.spool.feeder.internal.control;

import software.spool.core.exception.SpoolException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.utils.routing.ErrorRouter;

import java.util.Collection;

public class QuarantineEnvelopesHandler implements Handler<Collection<EnvelopeQuarantined>> {
    private final InboxUpdater updater;
    private final ErrorRouter errorRouter;

    public QuarantineEnvelopesHandler(InboxUpdater updater, ErrorRouter errorRouter) {
        this.updater = updater;
        this.errorRouter = errorRouter;
    }

    @Override
    public void handle(Collection<EnvelopeQuarantined> envelopeQuarantinedEvents) throws SpoolException {
        try {
            envelopeQuarantinedEvents
                    .forEach(e -> updater.update(e.idempotencyKey(), EnvelopeStatus.QUARANTINED));
        } catch (Exception e) {
            errorRouter.dispatch(e);
        }
    }
}
