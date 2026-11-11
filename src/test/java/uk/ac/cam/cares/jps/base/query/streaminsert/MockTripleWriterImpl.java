package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

public class MockTripleWriterImpl extends TripleWriterImpl {

    private final OntModel kb;
    private final StringWriter stringWriter;

    public MockTripleWriterImpl(OntModel kb) {
        this(kb, new StringWriter());
    }

    private MockTripleWriterImpl(OntModel kb, StringWriter stringWriter) {
        super(stringWriter);
        this.kb = kb;
        this.stringWriter = stringWriter;
    }

    @Override
    public void close() throws IOException {
        super.close();
        String turtle = stringWriter.toString()
                .replaceAll("PREFIX\\s+([A-Za-z0-9_-]+):\\s*<([^>]+)>", "@prefix $1: <$2> .\n")
                .replaceAll(">(?=<|[A-Za-z_][A-Za-z0-9_-]*:|\"|[-+0-9])", "> ")
                .replaceAll("((?:ts|kb|time):[A-Za-z0-9_-]+)(?=<|(?:ts|kb|time):|\"|[-+0-9])",
                        "$1 ")
                .replaceAll("\\.(?=<)", ".\n");
        RDFDataMgr.read(kb, new ByteArrayInputStream(turtle.getBytes()), Lang.TURTLE);
    }
}
