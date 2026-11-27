package uk.ac.cam.cares.jps.base.timeseries;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.time.Instant;

/**
 * This class provides unit tests for the TimeSeriesRDBClient class
 */

public class TimeSeriesRDBClientTest {
    @Test
    public void testConstructor()
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        TimeSeriesRDBClient<Instant> client = new TimeSeriesRDBClient<>(Instant.class);
        // Retrieve the value of the private field 'timeColumn' of the client to check
        // its value
        Field timeColumnField = client.getClass().getDeclaredField("timeColumn");
        timeColumnField.setAccessible(true);
        org.jooq.Field<String> timeColumn = (org.jooq.Field<String>) timeColumnField.get(client);
        // Test for correct field name and class
        Assertions.assertEquals("time", timeColumn.getName());
        Assertions.assertEquals(Instant.class, timeColumn.getType());
    }

    @Test
    public void testSetAndGetRdbURL() {
        TimeSeriesRDBClient<Instant> client = new TimeSeriesRDBClient<>(Instant.class);
        Assertions.assertNull(client.getRdbURL());
        client.setRdbURL("http://localhost:5342");
        Assertions.assertEquals("http://localhost:5342", client.getRdbURL());
    }

    @Test
    public void testSetAndGetRdbUser() {
        TimeSeriesRDBClient<Instant> client = new TimeSeriesRDBClient<>(Instant.class);
        Assertions.assertNull(client.getRdbUser());
        client.setRdbUser("postgres");
        Assertions.assertEquals("postgres", client.getRdbUser());
    }

    @Test
    public void testSetRdbPassword()
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        TimeSeriesRDBClient<Instant> client = new TimeSeriesRDBClient<>(Instant.class);
        // Retrieve the value of the private field 'rdbPassword' of the client to check
        // its value
        Field rdbPasswordField = client.getClass().getDeclaredField("rdbPassword");
        rdbPasswordField.setAccessible(true);

        Assertions.assertNull(rdbPasswordField.get(client));
        client.setRdbPassword("password");
        Assertions.assertNotNull(rdbPasswordField.get(client));
        Assertions.assertEquals("password", rdbPasswordField.get(client));
    }
}
