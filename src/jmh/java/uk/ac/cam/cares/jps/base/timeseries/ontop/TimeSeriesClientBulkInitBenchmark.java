package uk.ac.cam.cares.jps.base.timeseries.ontop;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.collections4.ListUtils;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import uk.ac.cam.cares.jps.base.BlazegraphContainer;
import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import uk.ac.cam.cares.jps.base.query.RemoteRDBStoreClient;
import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesClient;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesRDBClientOntop;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
public class TimeSeriesClientBulkInitBenchmark {

@Param({ "100000" })
    private int partitionSize;

    @Param({ "1000000" })
    public int size;

    private final BlazegraphContainer blazegraph = new BlazegraphContainer();
    private final DockerImageName myImage = DockerImageName.parse("postgis/postgis:18-3.7")
            .asCompatibleSubstituteFor("postgres");
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(myImage);

    private TimeSeriesClient<Instant> tsClient;
    private RemoteRDBStoreClient rdbStoreClient;
    private RemoteStoreClient kbClient;
    private final String timeUnit = "http://s";

    @Setup
    public void startServices() {
        try {
            blazegraph.start();
            postgres.start();

            kbClient = blazegraph.getRemoteStoreClient();
            tsClient = new TimeSeriesClient<>(kbClient, new TimeSeriesRDBClientOntop(Integer.class));
            rdbStoreClient = new RemoteRDBStoreClient(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
        } catch (Exception e) {
            stopServices();
            throw new JPSRuntimeException("Benchmark container startup failed.", e);
        }
    }

    @TearDown
    public void stopServices() {
        if (postgres.isRunning()) {
            postgres.stop();
        }
        if (blazegraph.isRunning()) {
            blazegraph.stop();
        }
    }

    @Benchmark
    public void bulkInitTimeSeries() throws SQLException {
        try (Connection conn = rdbStoreClient.getConnection()) {
            List<String> dataIris = IntStream.range(0, size)
                    .mapToObj(n -> "https://goodsizebaseiri/" + n)
                    .collect(Collectors.toList());
            List<List<String>> partitions = ListUtils.partition(dataIris, partitionSize);
            List<List<Class<?>>> dataClasses = Collections.nCopies(partitions.size(),
                    Collections.nCopies(Integer.min(partitionSize, size), Double.class));
            List<String> timeUnits = Collections.nCopies(partitions.size(), timeUnit);
            tsClient.bulkInitTimeSeries(partitions, dataClasses, timeUnits, conn);
        }
    }
}
