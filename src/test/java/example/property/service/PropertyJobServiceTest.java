package example.property.service;

import example.property.domain.PropertyJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

public final class PropertyJobServiceTest {
    public static void main(String[] args) throws Exception {
        RecordingSink sink = new RecordingSink();
        Clock clock = Clock.fixed(Instant.parse("2026-08-16T09:00:00Z"), ZoneOffset.UTC);
        PropertyJobService service = new PropertyJobService(sink, clock);
        PropertyJob job = new PropertyJob(PropertyJob.Kind.MAINTENANCE_REQUEST, "building-7", "repair-204",
                "Boiler pressure check", LocalDate.parse("2026-08-15"));

        PropertyJobService.Decision result = service.accept(job);

        check(result.accepted(), "job should be accepted");
        check("urgent".equals(result.priority()), "overdue maintenance should be urgent");
        check("building-7".equals(sink.record.get("property_id")), "property must be searchable");
        check("repair-204".equals(sink.record.get("subject_id")), "request must be searchable");
        check("property-job:maintenance_request:repair-204".equals(sink.key), "retry key must be stable");
        System.out.println("PASS overdue maintenance becomes an urgent searchable record");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingSink implements example.property.logging.PropertyLogSink {
        private Map<String, String> record;
        private String key;

        @Override
        public String ingest(Map<String, String> record, String idempotencyKey) {
            this.record = Map.copyOf(record);
            this.key = idempotencyKey;
            return "{}";
        }
    }
}
