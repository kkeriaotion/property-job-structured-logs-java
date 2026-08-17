package example.property.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import example.property.domain.PropertyJob;
import example.property.logging.InfraiException;
import example.property.logging.InfraiLogsClient;
import example.property.service.PropertyJobService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public final class PropertyJobController {
    private final PropertyJobService jobs;
    private final InfraiLogsClient logs;
    private final HttpServer server;

    public PropertyJobController(PropertyJobService jobs, InfraiLogsClient logs, int port) throws IOException {
        this.jobs = jobs;
        this.logs = logs;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/jobs", this::acceptJob);
        server.createContext("/logs", this::searchLogs);
    }

    public void start() { server.start(); }

    private void acceptJob(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        try {
            Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            PropertyJob job = new PropertyJob(PropertyJob.Kind.parse(required(form, "kind")), required(form, "propertyId"),
                    required(form, "subjectId"), required(form, "summary"), LocalDate.parse(required(form, "dueDate")));
            PropertyJobService.Decision decision = jobs.accept(job);
            respond(exchange, 202, "{\"accepted\":" + decision.accepted() + ",\"priority\":\"" + decision.priority() + "\"}");
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, "{\"error\":\"invalid_job\"}");
        } catch (InfraiException exception) {
            int status = exception.statusCode() >= 400 && exception.statusCode() < 500 ? exception.statusCode() : 502;
            respond(exchange, status, "{\"error\":\"log_request_rejected\"}");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, "{\"error\":\"interrupted\"}");
        } catch (IOException exception) {
            respond(exchange, 502, "{\"error\":\"log_transport\"}");
        }
    }

    private void searchLogs(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { respond(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        try {
            String query = form(exchange.getRequestURI().getRawQuery()).getOrDefault("q", "");
            if (query.isBlank()) { respond(exchange, 400, "{\"error\":\"q_required\"}"); return; }
            respond(exchange, 200, logs.search(query));
        } catch (InfraiException exception) {
            int status = exception.statusCode() >= 400 && exception.statusCode() < 500 ? exception.statusCode() : 502;
            respond(exchange, status, "{\"error\":\"log_search_rejected\"}");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, "{\"error\":\"interrupted\"}");
        } catch (IOException exception) {
            respond(exchange, 502, "{\"error\":\"log_transport\"}");
        }
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> values = new HashMap<>();
        if (encoded == null || encoded.isBlank()) return values;
        for (String pair : encoded.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String required(Map<String, String> form, String name) {
        String value = form.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
