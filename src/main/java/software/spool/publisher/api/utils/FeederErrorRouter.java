package software.spool.publisher.api.utils;

import software.spool.core.exception.*;
import software.spool.core.model.*;
import software.spool.core.port.EventBusEmitter;
import software.spool.core.utils.ErrorRouter;

public class FeederErrorRouter {
    public static ErrorRouter defaults(EventBusEmitter bus) {
        return new ErrorRouter()
                .on(InboxReadException.class,
                        (e, cause) -> bus.emit(InboxItemConsumptionFailed.builder()
                                .errorMessage(e.getMessage()).build()))
                .on(InboxUpdateException.class,
                        (e, cause) -> bus.emit(InboxItemStoreFailed.builder()
                                .from(cause).errorMessage(e.getMessage()).build()));
    }
}
