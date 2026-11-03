package uk.ac.cam.cares.jps.base.query.streaminsert;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.rdf4j.rio.RDFFormat;

public class StreamInserter {
    private static final Logger LOGGER = LogManager.getLogger(StreamInserter.class);
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public static void streamInsert(String updateEndpointURL, Consumer<TripleWriter> outputter) {
        try (PipedInputStream inputStream = new PipedInputStream()) {
            Future<String> future = null;

            try (PipedOutputStream outputStream = new PipedOutputStream(inputStream);
                    Writer writer = new OutputStreamWriter(outputStream, "UTF-8");
                    TripleWriter tripleWriter = TripleWriter.create(writer)) {

                future = executorService.submit(new Callable<String>() {
                    public String call() {
                        HttpPost httpPost = new HttpPost(updateEndpointURL);

                        InputStreamEntity entity = new InputStreamEntity(inputStream,
                                ContentType.create(RDFFormat.TURTLE.getDefaultMIMEType()));
                        httpPost.setEntity(entity);

                        try (CloseableHttpClient httpClient = HttpClients.createDefault();
                                CloseableHttpResponse response = httpClient.execute(httpPost)) {
                            String responseBody = IOUtils.toString(response.getEntity().getContent(),
                                    StandardCharsets.UTF_8);
                            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                                throw new RuntimeException(
                                        "Failed to insert triples into '" + updateEndpointURL + "':\n"
                                                + response.getStatusLine().getReasonPhrase() + "\n" + responseBody);
                            }
                            return responseBody;
                        } catch (IOException ex) {
                            throw new UncheckedIOException("Error inserting triples into '" + updateEndpointURL + "''.",
                                    ex);
                        }
                    }
                });
                outputter.accept(tripleWriter);

            } finally {
                try {
                    if (null != future) {
                        LOGGER.info(future.get());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Error waiting for insert triples HTTP request to finish.", e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("Error executing insert triples HTTP request.", e);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error in outputter when stream inserting triples.", ex);
        }
    }
}
