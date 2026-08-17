package example.property.service;

import example.property.domain.PropertyJob;
import example.property.logging.PropertyLogSink;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PropertyJobService {
    private final PropertyLogSink logs;
    private final Clock clock;

    public PropertyJobService(PropertyLogSink logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    public Decision accept(PropertyJob job) throws IOException, InterruptedException {
        String priority = priorityFor(job, LocalDate.now(clock));
        Map<String, String> record = new LinkedHashMap<>();
        record.put("event", "property_job_accepted");
        record.put("job_kind", job.kind().wireName());
        record.put("property_id", job.propertyId());
        record.put("subject_id", job.subjectId());
        record.put("summary", job.summary());
        record.put("due_date", job.dueDate().toString());
        record.put("priority", priority);
        logs.ingest(record, "property-job:" + job.kind().wireName() + ":" + job.subjectId());
        return new Decision(true, priority);
    }

    static String priorityFor(PropertyJob job, LocalDate today) {
        if (job.kind() == PropertyJob.Kind.MAINTENANCE_REQUEST && job.dueDate().isBefore(today)) return "urgent";
        if (job.kind() == PropertyJob.Kind.INSPECTION_REMINDER && !job.dueDate().isAfter(today)) return "due";
        return "normal";
    }

    public record Decision(boolean accepted, String priority) {}
}
