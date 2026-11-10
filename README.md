
# TWA Base Library

This project was formally known as the JPS Base Lib.

## Requirements

### GitHub Maven repository credentials

To be able to fetch from, and deploy to, the TWA Maven repositories hosted on GitHub you need to set up some credentials.
The following needs to be added to your Maven `settings.xml` file, which should be located here `${user.home}/.m2/settings.xml`:

```xml
<server>
    <id>github</id>
    <username></username>
    <password>{A GitHub Token with only package "read" (and "write" if you need to deploy) permissions}</password>
</server>
```

## Usage

To be written

## Time-series benchmarks

The time-series bulk-initialization benchmark is opt-in and requires Docker
for its Blazegraph and PostgreSQL containers. Build it with JMH using the
`benchmarks` Maven profile and generate the test classpath:

```bash
mvn -Pbenchmarks -DskipTests clean test-compile dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt -Dmdep.includeScope=test
```

And then run the generated .jar file:

```bash
java -cp "target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)" -Djmh.shutdownTimeout=5 org.openjdk.jmh.Main uk.ac.cam.cares.jps.base.timeseries.ontop.TimeSeriesClientBulkInitBenchmark
```

To collect an async-profiler CPU flame graph, install async-profiler and add
its native library path to the JMH profiler options:

```bash
java -cp "target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)" -Djmh.shutdownTimeout=5 org.openjdk.jmh.Main uk.ac.cam.cares.jps.base.timeseries.ontop.TimeSeriesClientBulkInitBenchmark -prof 'async:libPath=<path-to-async-profiler-library>;output=flamegraph;event=cpu;dir=profile-results'
```

The benchmark runs each input scale once as a single-shot measurement. Stores
are cleared before measurement, while container startup and cleanup are
excluded from the benchmark.
