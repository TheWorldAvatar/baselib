package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;

import org.eclipse.rdf4j.sparqlbuilder.rdf.Iri;

public interface TAllowed {

    public T t(String type) throws IOException;

    public T t(Iri type) throws IOException;
}