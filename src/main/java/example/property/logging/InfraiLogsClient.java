package example.property.logging;

import example.property.config.PropertyLogConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InfraiLogsClient implements PropertyLogSink {
    private static final Pattern OK = Pattern.compile("\\\"ok\\\"\\s*:\\s*(true|false)");
    private static final Pattern DATA = Pattern.compile("\\\"data\\\"\\s*:\\s*(\\{.*}|\\[.*]|null)", Pattern.DOTALL);
    private static final Pattern ERROR_CODE = Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\\\"(?:message|hint)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");

    private final HttpClient http;
    private final PropertyLogConfig config;

    public InfraiLogsClient(HttpClient http, PropertyLogConfig config) {
        this.http = http;
        this.config = config;
    }

    @Override
    public String ingest(Map<String, String> record, String idempotencyKey) throws IOException, InterruptedException {
        // Canonical capability: POST /v1/logs/ingest
        String entry = "{\"level\":\"info\",\"message\":\"property_job_accepted\",\"service\":\"property-operations\",\"metadata\":" + jsonObject(record) + "}";
        String body = "{\"entries\":[" + entry + "],\"idempotency_key\":\"" + escape(idempotencyKey) + "\"}";
        HttpRequest request = request(config.baseUri().resolve("/v1/logs/ingest"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    public String search(String query) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = config.baseUri().resolve("/v1/logs/search?q=" + encoded);
        HttpRequest request = request(uri).GET().build();
        return send(request);
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.apiKey());
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Envelope envelope = decodeEnvelope(response.body(), response.statusCode());
            if (response.statusCode() == 429 && attempt < config.maxRetries()) {
                Thread.sleep(retryDelayMillis(response, attempt));
                continue;
            }
            if (!envelope.ok()) {
                throw new InfraiException(response.statusCode(), envelope.errorCode(), envelope.errorMessage());
            }
            if (response.statusCode() >= 500) {
                throw new IOException("Remote log transport returned HTTP " + response.statusCode());
            }
            return envelope.data();
        }
    }

    private static Envelope decodeEnvelope(String body, int status) throws IOException {
        Matcher ok = OK.matcher(body);
        if (!ok.find()) throw new IOException("Log response is not an Infrai envelope (HTTP " + status + ")");
        Matcher data = DATA.matcher(body);
        Matcher code = ERROR_CODE.matcher(body);
        Matcher message = ERROR_MESSAGE.matcher(body);
        return new Envelope(Boolean.parseBoolean(ok.group(1)), data.find() ? data.group(1) : "null",
                code.find() ? code.group(1) : "request_rejected",
                message.find() ? message.group(1) : "Log request was rejected");
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try { return Math.max(1L, Long.parseLong(value)) * 1000L; }
        catch (NumberFormatException ignored) { return 250L * (1L << attempt); }
    }

    private static String jsonObject(Map<String, String> values) {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private record Envelope(boolean ok, String data, String errorCode, String errorMessage) {}
}
