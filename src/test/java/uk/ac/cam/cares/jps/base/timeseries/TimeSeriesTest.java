package uk.ac.cam.cares.jps.base.timeseries;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.postgis.jdbc.geometry.Point;
import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;

/**
 * This class provides unit tests for the TimeSeries class
 */

public class TimeSeriesTest {
	
	TimeSeries<Instant> ts;
	List<String> dataIRI = new ArrayList<>();
	List<Instant> timeList = new ArrayList<>();
	List<Double> data1 = new ArrayList<>();
	List<String> data2 = new ArrayList<>();
	List<Integer> data3 = new ArrayList<>();
	List<Double> data4 = new ArrayList<>();
	List<Point> data5 = new ArrayList<>();
	List<List<?>> dataToAdd = new ArrayList<>();
	
	@BeforeEach
	public void initialiseData() {
		dataIRI.add("http://data1"); dataIRI.add("http://data2"); dataIRI.add("http://data3"); dataIRI.add("http://data4"); 
		dataIRI.add("http://data5");
		for (int i = 0; i < 10; i++) {
			timeList.add(Instant.now().plusSeconds(i));
			data1.add(Double.valueOf(i));
			data2.add(String.valueOf(i));
			data3.add(Integer.valueOf(i));
			data4.add(Double.valueOf(i));

			Point point = new Point(); point.setX(i); point.setY(i);
			data5.add(point);
		}
		data4.set(3, null);
		dataToAdd.add(data1); dataToAdd.add(data2); dataToAdd.add(data3); dataToAdd.add(data4); dataToAdd.add(data5);
	}
	
	/**
	 * Constructor tests
	 */
	
	@Test
	public void testConstructorWithoutDataIRI() {
		Assertions.assertThrows(JPSRuntimeException.class, () ->
				ts = new TimeSeries<Instant>(timeList, new ArrayList<>(), new ArrayList<>()));
	}
	
	@Test
	public void testConstructorWithWrongDataIRIsize() {
		Assertions.assertThrows(JPSRuntimeException.class, () -> {
			dataToAdd.remove(dataToAdd.size()-1);
			ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
		});
	}
	
	@Test
	public void testConstructorWithWrongDataSeriesLength() {
		Assertions.assertThrows(JPSRuntimeException.class, () -> {
			dataToAdd.get(0).remove(0);
			ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
		});
	}
	
	/**
	 *  Method tests
	 */
	
	@Test
    public void testGetTimes() {
		// constructor for TimeSeries object takes: time column, dataIRIs, and corresponding values in lists
		ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
        Assertions.assertEquals(ts.getTimes(), timeList);
    }
    
	@Test
    public void testGetDataIRI() {
		// constructor for TimeSeries object takes: time column, dataIRIs, and corresponding values in lists
		ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
        for (String iri : ts.getDataIRIs()) {
            Assertions.assertTrue(dataIRI.contains(iri));
        }
    }
    
	@Test
    public void testGetValues() {
		// Constructor for TimeSeries object takes: time column, dataIRIs, and corresponding values in lists
		ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
        Assertions.assertEquals(ts.getValues(dataIRI.get(0)),data1);
        Assertions.assertEquals(ts.getValues(dataIRI.get(1)),data2);
        Assertions.assertEquals(ts.getValues(dataIRI.get(2)),data3);
        // Check whether null is returned for non-existing dataIRI keys
        Assertions.assertNull(ts.getValues("data0"));
    }
    
    /**
     * To use this, the original data must be an instance of "Number"
     */
	@Test
    public void testGetValuesAsDouble() {
		// Constructor for TimeSeries object takes: time column, dataIRIs, and corresponding values in lists
		ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
		Assertions.assertEquals(ts.getValuesAsDouble(dataIRI.get(0)), data1);
		Assertions.assertEquals(ts.getValuesAsDouble(dataIRI.get(0)).get(0).getClass(), Double.class);
		Assertions.assertEquals(ts.getValuesAsDouble(dataIRI.get(3)), data4);
        Assertions.assertEquals(ts.getValuesAsDouble(dataIRI.get(2)).get(0).getClass(), Double.class);
        // Test for non-existing and non-castable data series
        Assertions.assertNull(ts.getValuesAsDouble("data0"));
        try {
            List<Double> v = ts.getValuesAsDouble(dataIRI.get(1));
        } catch (Exception e) {
            Assertions.assertTrue(e instanceof JPSRuntimeException);
            Assertions.assertEquals("TimeSeries: Values for provided dataIRI are not castable to \"Number\"",
                    e.getMessage());
        }
    }
    
    /** 
     * If the original class has a toString() method, this should work
     */
	@Test
    public void testGetValuesAsString() {
		// Constructor for TimeSeries object takes: time column, dataIRIs, and corresponding values in lists
		ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
        for (String data : dataIRI) {
            Assertions.assertEquals(ts.getValuesAsString(data).get(0).getClass(), String.class);
        }
    }
    
    /**
     * To use this, the original data must be an instance of "Number"
     */
	@Test
    public void testGetValuesAsInteger() {
		// Constructor for TimeSeries object takes: time column, dataIRIs, and corresponding values in lists
		ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
		Assertions.assertEquals(ts.getValuesAsInteger(dataIRI.get(0)), data3);
        Assertions.assertEquals(ts.getValuesAsInteger(dataIRI.get(0)).get(0).getClass(), Integer.class);
        Assertions.assertEquals(ts.getValuesAsInteger(dataIRI.get(2)).get(0).getClass(), Integer.class);
        // Test for non-existing and non-castable data series
        Assertions.assertNull(ts.getValuesAsInteger("data0"));
        try {
            List<Integer> v = ts.getValuesAsInteger(dataIRI.get(1));
        } catch (Exception e) {
            Assertions.assertTrue(e instanceof JPSRuntimeException);
            Assertions.assertEquals("TimeSeries: Values for provided dataIRI are not castable to \"Number\"",
                    e.getMessage());
        }
    }

	@Test
	public void testGetValuesAsPoint() {
		ts = new TimeSeries<Instant>(timeList, dataIRI, dataToAdd);
		Assertions.assertEquals(ts.getValuesAsPoint(dataIRI.get(4)), data5);
		Assertions.assertNull(ts.getValuesAsPoint("data0"));
        try {
            ts.getValuesAsPoint(dataIRI.get(1));
        } catch (Exception e) {
            Assertions.assertTrue(e instanceof JPSRuntimeException);
            Assertions.assertEquals("TimeSeries: Values for provided dataIRI are not castable to \"Point\"",
                    e.getMessage());
        }
	}
}
