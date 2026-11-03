package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;

import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfObject;

public interface OAllowed {

    public O o(String object) throws IOException;

    public O o(RdfObject object) throws IOException;
}