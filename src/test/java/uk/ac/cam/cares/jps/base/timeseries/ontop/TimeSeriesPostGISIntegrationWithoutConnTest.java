package uk.ac.cam.cares.jps.base.timeseries.ontop;

import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesRDBClientOntop;

public class TimeSeriesPostGISIntegrationWithoutConnTest
        extends uk.ac.cam.cares.jps.base.timeseries.TimeSeriesPostGISIntegrationWithoutConnTest {

    @Override
    protected void setRdbClient() {
        tsClient = new TimeSeriesRDBClientOntop<>(Integer.class);
    }
}
