package uk.ac.cam.cares.jps.base.query;

import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Iterator;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFactory;
import org.apache.jena.query.ResultSetRewindable;
import org.apache.jena.query.TxnType;

import com.github.owlcs.ontapi.OntManagers;
import com.github.owlcs.ontapi.Ontology;
import com.github.owlcs.ontapi.OntologyManager;

public class FileBasedKnowledgeBaseClient extends KnowledgeBaseClient {
	
	private String filePath;
	private Ontology ont;
	
	/**
	 * Default constructor
	 */
	public FileBasedKnowledgeBaseClient() {}
	
	/**
	 * Constructor with file path provided
	 * 
	 * @param filePath
	 */
	public FileBasedKnowledgeBaseClient(String filePath) {
		this.filePath = filePath;
	}
	
	/*
	 * Load file
	 * 
	public void loadOntology(String filePath) {
		this.filePath = filePath;
		this.loadOntology();
	}
	 */
	
	public int executeUpdate() throws SQLException {
		return executeUpdate(this.query);
	}
	
	/*
	 * Perform a select query
	 */
	private ResultSet performExecuteQuery(Query query) {
		
		QueryExecution queryExec = QueryExecutionFactory.create(query, this.ont.asGraphModel());
		ResultSet rs = queryExec.execSelect();   
			
		//reset the cursor, so that the ResultSet can be repeatedly used
		ResultSetRewindable results = ResultSetFactory.copyResults(rs);    
		return results;
	}

	public JSONArray executeQuery(String sparql) {
		
		Query query = QueryFactory.create(sparql);
		return convert(performExecuteQuery(query));
	}	
	
	public JSONArray executeQuery() {
		return executeQuery(this.query);
	}
	
	private JSONArray convert(ResultSet resultSet) {
		
		JSONArray json = new JSONArray();
		
		while (resultSet.hasNext()) {
			QuerySolution qs = resultSet.next();
			JSONObject obj = new JSONObject();
			Iterator<String> it = qs.varNames(); 
			while(it.hasNext()) {
				String var = it.next(); 
				obj.put(var, qs.get(var));
			}
			json.put(obj);
		}
		return json;
	}

	@Override
	public int executeUpdate(String update) throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}
}