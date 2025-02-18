package uk.ac.cam.cares.jps.base.scenario;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import uk.ac.cam.cares.jps.base.config.JPSConstants;
import uk.ac.cam.cares.jps.base.config.KeyValueManager;
import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import uk.ac.cam.cares.jps.base.query.ResourcePathConverter;

public class BucketHelper {
	
	private static final String SLASH_KB = "/" + JPSConstants.SCENARIO_SUBDIR_KB + "/";
	private static final String SLASH_DATA = "/" + JPSConstants.SCENARIO_SUBDIR_DATA + "/";
	
	public static String getScenarioUrl(String scenarioName) {
		String url = KeyValueManager.getServerAddress() + ScenarioHelper.SCENARIO_COMP_URL + "/" + scenarioName;
		
//		System.out.println("THE SCENARIO NAME= "+scenarioName);
//		if (!scenarioName.contentEquals("base")) {
//			String usecaseUrl = getUsecaseUrl(url);
//			url = url  + '_' + usecaseUrl.replace(url + SLASH_KB, "");
//		}
		
		return url;
	}

	public static String getScenarioUrl() {
		
		String scenarioUrl = JPSContext.getScenarioUrl();	
		if (scenarioUrl == null) {
			scenarioUrl = getScenarioUrl(JPSConstants.SCENARIO_NAME_BASE);
		}
		
		return scenarioUrl;
	}
	
	public static String getScenarioName (String scenarioUrl) {
		int i = scenarioUrl.indexOf(ScenarioHelper.SCENARIO_COMP_URL);
		return scenarioUrl.substring(i + ScenarioHelper.SCENARIO_COMP_URL.length() + 1);
	}
	
	public static String getIriPrefix() {
		String usecaseUrl = getUsecaseUrl();
		int iHostEnd = usecaseUrl.indexOf(ScenarioHelper.SCENARIO_COMP_URL);
		int iKbBegin = usecaseUrl.indexOf(SLASH_KB);
		String prefix = usecaseUrl.substring(0, iHostEnd) + "/" + JPSConstants.KNOWLEDGE_BASE_JPS + usecaseUrl.substring(iKbBegin);
		return prefix;
	}
	
	public static String getUsecaseUrl() {

		String usecaseUrl = JPSContext.getUsecaseUrl();	
		System.out.println("WHAT IS USECASEUrl here? "+usecaseUrl);
		if (usecaseUrl == null) {
			usecaseUrl = getUsecaseUrl(getScenarioUrl());
			JPSContext.putUsecaseUrl(usecaseUrl);
		}
		
		return usecaseUrl;
	}
	
	public static String getUsecaseUrl(String scenarioUrl) {
		String usecaseUrl;
		String usecaseID = UUID.randomUUID().toString();

			usecaseUrl = scenarioUrl + SLASH_KB + usecaseID;

		
		return usecaseUrl;
		
	}
	
	public static String getUsecaseUrlForData() {
		String usecaseUrl = BucketHelper.getUsecaseUrl();
		return usecaseUrl.replace(SLASH_KB, SLASH_DATA);
	}

	public static String getKbScenarioUrl() {
		String scenarioUrl = JPSContext.getScenarioUrl();	
		if (scenarioUrl == null) {
			throw new JPSRuntimeException("can't create a scenario kb url for the base scenario");
		} 
		
		return getScenarioUrl() + "/" + JPSConstants.SCENARIO_SUBDIR_KB;
	}

	public static boolean isScenarioUrl(String url) {
		return (url.indexOf(ScenarioHelper.SCENARIO_COMP_URL) >= 0);
	}
	
	public static boolean isBaseScenario(String url) {
		if (url == null) {
			return true;
		}
		String scenarioName = getScenarioName(url);
		return JPSConstants.SCENARIO_NAME_BASE.equals(scenarioName);
	}
	
	public static String getLocalDataPath() {
		String usecaseUrl = getUsecaseUrlForData();
		return getLocalPath(usecaseUrl, getScenarioUrl());
	}
	
	public static String getLocalDataPathWithoutThreadContext() {
		String usecaseUrl = getUsecaseUrl(getScenarioUrl());
		usecaseUrl = usecaseUrl.replace(SLASH_KB, SLASH_DATA);
		return getLocalPath(usecaseUrl, getScenarioUrl());
	}
	
	public static String getLocalPath(String url) {
		String scenarioUrl = JPSContext.getScenarioUrl();
		return getLocalPath(url, scenarioUrl);
	}
	
	public static String getLocalPath(String url, String datasetUrl) {
		
		URI uri;
		try {
			uri = new URI(url);			
		} catch (URISyntaxException e) {
			throw new JPSRuntimeException(e.getMessage(), e);
		}
		String mapped = "/" + mapHost(uri);
		
		if ((datasetUrl != null) && !isScenarioUrl(datasetUrl)) {
			// check whether scenarioUrl is datasetUrl
			int i = datasetUrl.indexOf(JPSConstants.KNOWLEDGE_BASE_PATH_JPS_DATASET);
			if (i >= 0) {
				String datasetName = datasetUrl.substring(1 + i + JPSConstants.KNOWLEDGE_BASE_PATH_JPS_DATASET.length());
				String dir = ScenarioHelper.getJpsWorkingDir() + "/JPS_SCENARIO/dataset/" + datasetName;
				return dir + mapped + uri.getPath();
			} else {
				throw new JPSRuntimeException("unknown datasetUrl=" + datasetUrl + ", url=" + url);
			}
		}
		
		String scenarioUrl = datasetUrl;

		if (scenarioUrl == null) {
			scenarioUrl = getScenarioUrl(JPSConstants.SCENARIO_NAME_BASE);
		}
		System.out.println("THIS IS SCENARIO URL2= "+scenarioUrl);
		
		String scenarioName = getScenarioName(scenarioUrl);
		System.out.println("THIS IS SCENARIO NAME2= "+scenarioName);
		String scenarioBucket = ScenarioHelper.getScenarioBucket(scenarioName);
		System.out.println("THIS IS SCENARIO BUCKET2= "+scenarioBucket);
		
		// this method is idempotent
		if (url.startsWith(scenarioBucket)) {
			return url;
		}
		
		String path = uri.getPath();
		if (path.startsWith(ScenarioHelper.SCENARIO_COMP_URL)) {
			// insert the host name between scenario name and /data/ or /kb/
			String relativePath = path.substring(ScenarioHelper.SCENARIO_COMP_URL.length());
			System.out.println("THIS IS RELATIVE PATH**1= "+relativePath);
			int i = relativePath.indexOf(SLASH_DATA);
			if (i>0) {
				relativePath = relativePath.replace(SLASH_DATA, mapped + SLASH_DATA);
				System.out.println("THIS IS RELATIVE PATH**2= "+relativePath);
			} else {
				i = relativePath.indexOf(SLASH_KB);
				if (i>0) {
					relativePath = relativePath.replace(SLASH_KB, mapped + SLASH_KB);
					System.out.println("THIS IS RELATIVE PATH**3= "+relativePath);
				} 
				System.out.println("THIS IS RELATIVE PATH**4= "+relativePath);
			}
			System.out.println("THIS IS RELATIVE PATH2= "+relativePath);
			return ScenarioHelper.getScenarioWorkingDir() + relativePath;
		}
		

		if (JPSConstants.SCENARIO_NAME_BASE.equals(scenarioName) && !path.startsWith("/" + JPSConstants.KNOWLEDGE_BASE_JPS + "/")) {
			
			if ((url.contains(SLASH_KB) || url.contains(SLASH_DATA))) {
				
				// OWL files for the base scenario are stored in Tomcat's root directory
				System.out.println("CALLING RESOURCE PATH CONVERTER "+url);
				return ResourcePathConverter.convertToLocalPath(url);
			} 
			// else:
			// these files are usually auto-generated by agents and are stored for the base scenario 
			// in the same way as for other scenarios, see the code below
		}

		String relativePath = path;
		if (relativePath.startsWith("/" + JPSConstants.KNOWLEDGE_BASE_JPS + "/")) {
			relativePath = relativePath.substring(4);
		}
		System.out.println("FINAL RESPONSE PATH = "+scenarioBucket + mapped + relativePath);
		return scenarioBucket + mapped + relativePath;
	}
	
	private static String mapHost(URI uri) {
		String mapped = uri.getHost().replace(".", "_");
		int port = uri.getPort();
		if (port > 0) {
			mapped = mapped + "_" + port;
		}
		return mapped;
	}
	
}
