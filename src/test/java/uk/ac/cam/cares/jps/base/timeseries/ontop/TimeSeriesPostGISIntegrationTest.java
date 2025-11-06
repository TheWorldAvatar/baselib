package uk.ac.cam.cares.jps.base.timeseries.ontop;

import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesRDBClientOntop;

public class TimeSeriesPostGISIntegrationTest
        extends uk.ac.cam.cares.jps.base.timeseries.TimeSeriesPostGISIntegrationTest {

    @Override
    protected void setRdbClient() {
        tsClient = new TimeSeriesRDBClientOntop<>(Integer.class);
    }
}
