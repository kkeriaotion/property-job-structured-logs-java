package example.property.domain;

import java.time.LocalDate;

public record PropertyJob(Kind kind, String propertyId, String subjectId, String summary, LocalDate dueDate) {
    public enum Kind {
        MAINTENANCE_REQUEST("maintenance_request"),
        TENANT_DOCUMENT("tenant_document"),
        INSPECTION_REMINDER("inspection_reminder");

        private final String wireName;

        Kind(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }

        public static Kind parse(String value) {
            for (Kind kind : values()) if (kind.wireName.equals(value)) return kind;
            throw new IllegalArgumentException("kind must be maintenance_request, tenant_document, or inspection_reminder");
        }
    }

    public PropertyJob {
        if (propertyId.isBlank() || subjectId.isBlank() || summary.isBlank()) {
            throw new IllegalArgumentException("propertyId, subjectId, and summary are required");
        }
    }
}
