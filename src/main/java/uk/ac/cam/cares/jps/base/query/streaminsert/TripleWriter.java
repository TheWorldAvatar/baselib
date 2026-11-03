package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;

public interface TripleWriter extends Closeable, PrefixAllowed, SAllowed {
    public static TripleWriter create(Writer writer) {
        return new TripleWriterImpl(writer);
    }

    public void end() throws IOException;
}
