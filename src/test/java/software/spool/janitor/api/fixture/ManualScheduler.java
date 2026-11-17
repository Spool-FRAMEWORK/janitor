package software.spool.janitor.api.fixture;

import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingPolicy;
import software.spool.core.utils.polling.PollingScheduler;

/** Keeps the scheduled task so a test decides exactly when each round runs. */
public final class ManualScheduler implements PollingScheduler {
    private Runnable task;

    @Override
    public void schedule(Runnable task, PollingPolicy policy, CancellationToken token) {
        this.task = task;
    }

    public void runRound() {
        task.run();
    }
}
