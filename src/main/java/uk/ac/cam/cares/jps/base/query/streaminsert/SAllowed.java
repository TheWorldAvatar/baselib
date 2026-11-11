package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;

import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfSubject;

public interface SAllowed {

    public S s(String subject) throws IOException;

    public S s(RdfSubject subject) throws IOException;
}