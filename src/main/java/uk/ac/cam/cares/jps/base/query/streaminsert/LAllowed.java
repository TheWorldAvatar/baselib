package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;

import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfLiteral;

public interface LAllowed {

    public L l(String literal) throws IOException;

    public L l(Number literal) throws IOException;

    public L l(Boolean literal) throws IOException;

    public L l(RdfLiteral<?> literal) throws IOException;
}