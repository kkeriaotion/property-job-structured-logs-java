package example.property.logging;

import java.io.IOException;

public final class InfraiException extends IOException {
    private final int statusCode;
    private final String code;

    public InfraiException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int statusCode() { return statusCode; }
    public String code() { return code; }
}
