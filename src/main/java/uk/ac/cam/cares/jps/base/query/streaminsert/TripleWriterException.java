package uk.ac.cam.cares.jps.base.query.streaminsert;

public class TripleWriterException extends RuntimeException {

    public TripleWriterException() {
    }

    public TripleWriterException(String message) {
        super(message);
    }

    public TripleWriterException(Throwable cause) {
        super(cause);
    }

    public TripleWriterException(String message, Throwable cause) {
        super(message, cause);
    }

    public TripleWriterException(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}