package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.eclipse.rdf4j.sparqlbuilder.core.Prefix;
import org.eclipse.rdf4j.sparqlbuilder.core.SparqlBuilder;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Iri;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf;
import org.json.JSONArray;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import uk.ac.cam.cares.jps.base.BlazegraphContainer;
import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;

@Testcontainers
class StreamInserterIntegrationTest {
  static final Prefix PREFIX_SI = SparqlBuilder.prefix("si", Rdf.iri("https://example.com/stream-inserter/"));
    private static final Iri SUBJECT_SHORT = PREFIX_SI.iri("subject");
    private static final String SUBJECT_LONG = "https://example.com/stream-inserter/subject";
    private static final String OBJECT_PREDICATE = "https://example.com/stream-inserter/object";
    private static final String LABEL_PREDICATE = "https://example.com/stream-inserter/label";
    private static final String OBJECT = "https://example.com/stream-inserter/object-value";
    private static final String LABEL = "streamed label";

    @Container
    private static final BlazegraphContainer blazegraph = new BlazegraphContainer();

    @Test
    void streamsTriplesIntoBlazegraph() {
        StreamInserter.streamInsert(blazegraph.getURL(), tripleWriter -> {
            try {
                tripleWriter.prefix("si", "https://example.com/stream-inserter/");
                tripleWriter.s(SUBJECT_SHORT).p(OBJECT_PREDICATE).o(OBJECT);
                tripleWriter.s(SUBJECT_SHORT).p(LABEL_PREDICATE).l(LABEL);
                tripleWriter.end();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        RemoteStoreClient storeClient = blazegraph.getRemoteStoreClient();
        String query = "SELECT ?object ?label WHERE { <" + SUBJECT_LONG + "> <" + OBJECT_PREDICATE
                + "> ?object . <" + SUBJECT_LONG + "> <" + LABEL_PREDICATE + "> ?label }";
        JSONArray results = storeClient.executeQuery(query);

        Assertions.assertEquals(1, results.length());
        Assertions.assertEquals(OBJECT, results.getJSONObject(0).getString("object"));
        Assertions.assertEquals(LABEL, results.getJSONObject(0).getString("label"));
    }
}
