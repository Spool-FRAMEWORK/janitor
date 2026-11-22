package software.spool.janitor.api.builder;

import software.spool.core.adapter.watchdog.HttpWatchdogClient;
import software.spool.core.model.watchdog.ModuleIdentity;
import software.spool.core.port.watchdog.ModuleHeartBeat;
import software.spool.core.utils.polling.PollingHeartbeat;

import java.util.Objects;

public class JanitorBuilderFactory {
    public static PollingJanitorBuilder polling() {
        return new Configuration().polling();
    }

    public static Configuration watchdog(String url, String moduleId) {
        return new Configuration(url, moduleId);
    }

    public static final class Configuration {
        private final String watchdogUrl;
        private final String moduleId;

        private Configuration(String watchdogUrl, String moduleId) {
            this.watchdogUrl = watchdogUrl;
            this.moduleId = moduleId;
        }

        private Configuration() {
            this(null, "feeder");
        }

        public PollingJanitorBuilder polling() {
            return new PollingJanitorBuilder(buildHeartbeat(watchdogUrl, moduleId));
        }
    }

    private static ModuleHeartBeat buildHeartbeat(String watchdogUrl, String moduleId) {
        return Objects.isNull(watchdogUrl) ?
                ModuleHeartBeat.silent(ModuleIdentity.of(moduleId)) : new PollingHeartbeat(
                new HttpWatchdogClient(watchdogUrl),
                ModuleIdentity.of(moduleId)
        );
    }
}
