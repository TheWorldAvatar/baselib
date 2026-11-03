package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.json.JSONArray;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import uk.ac.cam.cares.jps.base.BlazegraphContainer;
import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;

@Testcontainers
class StreamInserterIntegrationTest {

    private static final String SUBJECT = "https://example.com/stream-inserter/subject";
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
                tripleWriter.s(SUBJECT).p(OBJECT_PREDICATE).o(OBJECT);
                tripleWriter.s(SUBJECT).p(LABEL_PREDICATE).l(LABEL);
                tripleWriter.end();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        RemoteStoreClient storeClient = blazegraph.getRemoteStoreClient();
        String query = "SELECT ?object ?label WHERE { <" + SUBJECT + "> <" + OBJECT_PREDICATE
                + "> ?object . <" + SUBJECT + "> <" + LABEL_PREDICATE + "> ?label }";
        JSONArray results = storeClient.executeQuery(query);

        Assertions.assertEquals(1, results.length());
        Assertions.assertEquals(OBJECT, results.getJSONObject(0).getString("object"));
        Assertions.assertEquals(LABEL, results.getJSONObject(0).getString("label"));
    }
}
