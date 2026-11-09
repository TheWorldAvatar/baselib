package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;

import org.apache.jena.rdf.model.Property;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfPredicate;

public interface PAllowed {

    public P p(String predicate) throws IOException;

    public P p(RdfPredicate predicate) throws IOException;

    public P p(IRI predicate) throws IOException;

    public P p(Property predicate) throws IOException;

    public P a() throws IOException;
}