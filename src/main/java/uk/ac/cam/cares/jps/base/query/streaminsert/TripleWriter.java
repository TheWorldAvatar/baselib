package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;

import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfSubject;

public interface TripleWriter extends Closeable {
    public static TripleWriter create(Writer writer) {
        return new TripleWriterImpl(writer);
    }

    public S s(String subject) throws IOException;

    public S s(RdfSubject subject) throws IOException;

    public void end() throws IOException;
}
