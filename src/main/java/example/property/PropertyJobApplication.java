package example.property;

import example.property.config.PropertyLogConfig;
import example.property.http.PropertyJobController;
import example.property.logging.InfraiLogsClient;
import example.property.service.PropertyJobService;
import java.net.http.HttpClient;
import java.time.Clock;

public final class PropertyJobApplication {
    private PropertyJobApplication() {}

    public static void main(String[] args) throws Exception {
        PropertyLogConfig config = PropertyLogConfig.fromEnvironment();
        InfraiLogsClient logs = new InfraiLogsClient(HttpClient.newHttpClient(), config);
        PropertyJobService jobs = new PropertyJobService(logs, Clock.systemUTC());
        PropertyJobController controller = new PropertyJobController(jobs, logs, config.port());
        controller.start();
        System.out.println("Property job service listening on http://localhost:" + config.port());
    }
}
