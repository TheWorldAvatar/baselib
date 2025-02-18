package uk.ac.cam.cares.jps.base.scenario;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

import uk.ac.cam.cares.jps.base.discovery.AgentCaller;
import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;

/**
 * All JPS agents that want to make use of scenario have to inherit from this servlet class.
 *
 * @author Andreas
 */
@Controller
public abstract class JPSHttpServlet extends HttpServlet {

    private static final long serialVersionUID = 3507827296966247195L;
    protected Logger logger;
    private static final String GET_AGENT_INPUT_PARAMS_KEY = "query";

    /**
     * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        handleRequest(request, response);
    }

    /**
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        handleRequest(request, response);
    }

    /**
     * Handles GET and POST requests processing
     * - can be extended to handle other HTTP methods as well as
     * perform any pre-processing tasks before moving to do*JPS methods
     *
     * @param request  HTTP Servlet Request
     * @param response HTTP Servlet response
     */
    private void handleRequest(HttpServletRequest request, HttpServletResponse response) {
    	System.out.println("HANDLING REQUEST 1: ");
        JSONObject reqBody;
        try {
            reqBody = enableScenario(request);
            System.out.println("HANDLING REQUEST 2: ");
            System.out.println("REQ_BODY: " + reqBody.toString());
            if (request.getMethod().equals(HttpGet.METHOD_NAME)) {
            	System.out.println("HANDLING REQUEST 3: ");
                doGetJPS(request, response);
            } else if ((request.getMethod().equals(HttpPost.METHOD_NAME))) {
                doPostJPS(request, response, reqBody);
            }
        } catch (Exception e) {
            throw new JPSRuntimeException(e.getMessage(), e);
        } finally {
            disableScenario();
        }
    }

    /**
     * JPS wrapper for HttpServlet#doGet
     *
     * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doGetJPS(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    	System.out.println("DO GET JPS: ");
        doHttpJPS(request, response);
    }


    /**
     * JPS wrapper for HttpServlet#doPost
     *
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doPostJPS(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doHttpJPS(request, response);
    }

    /**
     * JPS wrapper for HttpServlet#doPost
     *
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doPostJPS(HttpServletRequest request, HttpServletResponse response, JSONObject reqBody) throws ServletException, IOException {
        doHttpJPS(request, response, reqBody);
    }

    /**
     * Method to group pre-processing steps common to all Http request methods
     *
     * @param request  HTTP Servlet Request
     * @param response HTTP Servlet Rsponse
     * @throws IOException @see PrintWriter#getWriter
     */
    protected void doHttpJPS(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    	System.out.println("DO HTTP JPS: 1 ");
    	setLogger();
    	System.out.println("DO HTTP JPS: 2 ");
        response.getWriter().write(getResponseBody(request));
    }

    /**
     * Method to group pre-processing steps common to all Http request methods
     *
     * @param request  HTTP Servlet Request
     * @param response HTTP Servlet Rsponse
     * @throws IOException @see PrintWriter#getWriter
     */
    protected void doHttpJPS(HttpServletRequest request, HttpServletResponse response, JSONObject reqBody) throws IOException, ServletException {
    	setLogger();
        response.getWriter().write(getResponseBody(request, reqBody));
    }
    
    protected void setLogger() {
        logger = LoggerFactory.getLogger(JPSHttpServlet.class);
    } 

    /**
     * Extract & Transform input parameters and return response parameters
     *
     * @param request HTTP Servlet Request
     * @return Response parameters as String
     */
    private String getResponseBody(HttpServletRequest request) {
    	System.out.println("DO GET RESPONSE BODY: 1 ");
        JSONObject requestParams = AgentCaller.readJsonParameter(request);
        System.out.println("DO GET RESPONSE BODY: 2 ");
        
        JSONObject responseParams;
        responseParams = processRequestParameters(requestParams);
        if (responseParams.isEmpty()) {
            responseParams = processRequestParameters(requestParams, request);
        }
        return responseParams.toString();
    }

    /**
     * Extract & Transform input parameters and return response parameters
     *
     * @param request HTTP Servlet Request
     * @return Response parameters as String
     */
    private String getResponseBody(HttpServletRequest request, JSONObject requestParams) {
        JSONObject responseParams;
        responseParams = processRequestParameters(requestParams);
        if (responseParams.isEmpty()) {
            responseParams = processRequestParameters(requestParams, request);
        }
        return responseParams.toString();
    }


    /**
     * Stub method to be overridden in subclasses
     * - shall implement logic transforming requestParams to responseParams
     *
     * @TODO: convert it to the abstract method and alter all JPSHttpServlet implementations (slightly bigger job)
     */
    protected JSONObject processRequestParameters(JSONObject requestParams) {
        JSONObject responseParams = new JSONObject();
        return responseParams;
    }

    /**
     * Stub method to be overridden in subclasses
     * - shall implement logic transforming requestParams to responseParams
     *
     * @TODO: convert it to the abstract method and alter all JPSHttpServlet implementations (slightly bigger job)
     */
    protected JSONObject processRequestParameters(JSONObject requestParams, HttpServletRequest request) {
        JSONObject responseParams = new JSONObject();
        return responseParams;
    }

    /**
     * Extracts agent input parameters from the request.
     * - makes a difference between GET and POST requests
     *
     * @param request Should contain agent input params
     * @return extracted parameters
     */
    private JSONObject getRequestParameters(HttpServletRequest request) {
        JSONObject params;
        try {
            String request_params = "";
            if (request.getMethod().equals(HttpPost.METHOD_NAME)) {
                request_params = IOUtils.toString(request.getReader());
            } else if (request.getMethod().equals(HttpGet.METHOD_NAME)) {
                request_params = request.getParameter(GET_AGENT_INPUT_PARAMS_KEY);
            }
            params = new JSONObject(request_params);
        } catch (IOException e) {
            throw new JPSRuntimeException(e.getMessage(), e);
        }
        return params;
    }

    /**
     * Method to call agents appropriate to the URI paths
     * - makes a difference between GET and POST requests
     * - logs URI paths and request parameters before execution
     * - logs response bodies after execution
     *
     * @param path Resource name, where the request goes to
     * @param jsonInput request body
     * @param method request HTTP method
     * @return response body
     */
    protected String execute(String path, String jsonInput, String method) {
        System.out.println("execute for path=" + path + ", json=" + jsonInput);
        String result;
        switch (method) {
            case HttpGet.METHOD_NAME:
                result = AgentCaller.executeGet(path, "query", jsonInput);
                break;
            case HttpPost.METHOD_NAME:
                result = AgentCaller.executePost(path, jsonInput);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + method);
        }
        System.out.println("execution result=" + result);
        return result;
    }

    /**
     * Method to call agents appropriate to the URI paths
     * - makes calls to 'execute' method, with GET set as a default
     *
     * @param path Resource name, where the request goes to
     * @param jsonInput request body
     * @return response body
     */
    protected String execute(String path, String jsonInput) {
        return execute(path, jsonInput, HttpGet.METHOD_NAME);
    }


    /**
     * Adds the scenariourl to ThreadContext such that other classes from the JPS_BASE can access it
     *
     * @param request
     */
    public static JSONObject enableScenario(HttpServletRequest request) {
    	System.out.println("ENABLING SCENARIO 1:");
        JSONObject jo = AgentCaller.readJsonParameter(request);
        if (JPSContext.getScenarioUrl(jo) != null) {
            String scenarioUrl = JPSContext.getScenarioUrl(jo);
            JPSContext.putScenarioUrl(scenarioUrl);
        }
        if (JPSContext.getUsecaseUrl(jo) != null) {
            String usecaseUrl =  JPSContext.getUsecaseUrl(jo);
            JPSContext.putUsecaseUrl(usecaseUrl);
        }
        return jo;
    }

    public static void enableScenario(String scenarioUrl) {
    	JPSContext.putScenarioUrl(scenarioUrl);
    }

    public static void enableScenario(String scenarioUrl, String usecaseUrl) {
    	JPSContext.putScenarioUrl(scenarioUrl);
    	JPSContext.putUsecaseUrl(usecaseUrl);
    }

    /**
     * Removes the scenariourl. This is important for the case that Tomcat (or any other server) might reuse the threads
     * which would have the consequence that other agents run wrongly in the same scenario. For this reason, this method
     * must be called whenever enable was called - even in case of an exception during any get, put etc. method of a servlet.
     */
    public static void disableScenario() {
    	JPSContext.removeScenarioUrl();
    	JPSContext.removeUsecaseUrl();
    }
}
