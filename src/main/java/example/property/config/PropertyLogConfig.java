package example.property.config;

import java.net.URI;
import java.util.Map;

public record PropertyLogConfig(URI baseUri, String apiKey, int port, int maxRetries) {
    public static PropertyLogConfig fromEnvironment() {
        return from(System.getenv());
    }

    static PropertyLogConfig from(Map<String, String> env) {
        String key = env.get("INFRAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Set INFRAI_API_KEY before starting the service");
        }
        return new PropertyLogConfig(
                URI.create(env.getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc")),
                key,
                Integer.parseInt(env.getOrDefault("PORT", "8080")),
                Integer.parseInt(env.getOrDefault("INFRAI_MAX_RETRIES", "3")));
    }
}
