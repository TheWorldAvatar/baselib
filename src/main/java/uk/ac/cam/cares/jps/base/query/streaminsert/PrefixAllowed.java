package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;

import org.eclipse.rdf4j.sparqlbuilder.core.Prefix;

public interface PrefixAllowed {

    public TripleWriter prefix(String name, String uri) throws IOException;

    public TripleWriter prefix(Prefix prefix) throws IOException;

}