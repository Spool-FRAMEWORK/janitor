package software.spool.janitor.api.quarantine;

import software.spool.core.port.http.HttpRoute;

import java.util.Collection;
import java.util.List;

/**
 * Serves the review and the resolution of the quarantine as routes that the node registers in its HTTP server
 * (ADR-014). A janitor without one serves nothing.
 */
@FunctionalInterface
public interface QuarantineServer {
    Collection<HttpRoute> routes();

    static QuarantineServer none() {
        return List::of;
    }
}
