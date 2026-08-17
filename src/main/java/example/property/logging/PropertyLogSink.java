package example.property.logging;

import java.io.IOException;
import java.util.Map;

public interface PropertyLogSink {
    String ingest(Map<String, String> record, String idempotencyKey) throws IOException, InterruptedException;
}
