package software.spool.publisher.api.port;

import software.spool.core.exception.EventBusEmitException;
import software.spool.core.model.Event;

public interface EventBusEmitter {
    void emit(Event event) throws EventBusEmitException;
}
