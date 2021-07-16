package uk.ac.cam.cares.jps.base.scenario.test;

import org.json.JSONObject;
import uk.ac.cam.cares.jps.base.scenario.JPSContext;

import org.junit.Test;
import static org.junit.Assert.*;


public class JPSContextTest{

	// Test get(JSONObject jo, String key) and put(JSONObject jo, String key, String value)
	@Test
	public void getTest(){
		JSONObject jo = new JSONObject();
		String key = "testkey";
		String value = "testvalue";
		// Default
		assertNull(JPSContext.get(jo, key));
		// Custom values
		JPSContext.put(jo, key, value);
		assertEquals("testvalue", JPSContext.get(jo, key));
	}
	
	// Test getJpsContext()
	@Test
	public void getJpsContextTest() {
		assertTrue(JPSContext.getJpsContext().length()==0);
		
	}
	
	// Test createJpsContext()
	@Test
	public void createJpsContextTest() {
		assertTrue(JPSContext.createJpsContext().length()==0);
	}
	
		
	// Test putScenarioUrl(JSONObject jo, String value) and getScenarioUrl(JSONObject jo)
	@Test
	public void getScenarioUrlTest() {
		JSONObject jo = new JSONObject();
		String value = "testvalue";
		assertNull(JPSContext.getScenarioUrl(jo));
		JPSContext.putScenarioUrl(jo, value);
		assertEquals("testvalue", JPSContext.getScenarioUrl(jo));
	}
	
	// Test putUsecaseUrl((JSONObject jo, String value) and getUsecaseUrl(JSONObject jo)
	@Test
	public void getUsecaseUrlTest() {
		JSONObject jo = new JSONObject();
		String value = "testvalue";
		assertNull(JPSContext.getUsecaseUrl(jo));
		JPSContext.putUsecaseUrl(jo, value);
		assertEquals("testvalue", JPSContext.getUsecaseUrl(jo));
	}
	
	// Test putSimulationTime(JSONObject jo, String value) and getSimulationTime(JSONObject jo)
	@Test
	public void getSimulationTimeTest() {
		JSONObject jo = new JSONObject();
		String value = "testvalue";
		assertNull(JPSContext.getSimulationTime(jo));
		JPSContext.putSimulationTime(jo, value);
		assertEquals("testvalue", JPSContext.getSimulationTime(jo));
		
	}

	// Test put(String key, String value); get(String key) and remove(String key)
	@Test
	public void getTest2() {
		String key = "testkey";
		String value = "testvalue";
		assertNull(JPSContext.get(key));
		JPSContext.put(key, value);
		assertEquals("testvalue", JPSContext.get(key));
		JPSContext.remove(key);
		assertNull(JPSContext.get(key));
	}

	// Test putScenarioUrl(String value); getScenarioUrl() and removeScenarioUrl()
	@Test
	public void getScenarioUrlTest2() {
		String value = "testvalue";
		assertNull(JPSContext.getScenarioUrl());
		JPSContext.putScenarioUrl(value);
		assertEquals("testvalue", JPSContext.getScenarioUrl());
		JPSContext.removeScenarioUrl();
		assertNull(JPSContext.getScenarioUrl());
	}
	
	// Test putUsecaseUrl(String value); getUsecaseUrl() and removeUsecaseUrl()
	@Test
	public void getUsecaseUrlTest2() {
		String value = "testvalue";
		assertNull(JPSContext.getUsecaseUrl());
		JPSContext.putUsecaseUrl(value);
		assertEquals("testvalue", JPSContext.getUsecaseUrl());
		JPSContext.removeUsecaseUrl();
		assertNull(JPSContext.getUsecaseUrl());
		
	}
	
	// Test putSimulationTime(String value); getSimulationTime() and removeSimulationTime()
	@Test
	public void getSimulationTimeTest2() {
		String value = "testvalue";
		assertNull(JPSContext.getSimulationTime());
		JPSContext.putSimulationTime(value);
		assertEquals("testvalue", JPSContext.getSimulationTime());
		JPSContext.removeSimulationTime();
		assertNull(JPSContext.getSimulationTime());
	}

}
