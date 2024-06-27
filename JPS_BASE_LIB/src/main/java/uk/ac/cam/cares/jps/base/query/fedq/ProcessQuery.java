package uk.ac.cam.cares.jps.base.query.fedq;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.core.BasicPattern;
import org.eclipse.rdf4j.federated.FedXFactory;
import org.eclipse.rdf4j.federated.endpoint.Endpoint;
import org.eclipse.rdf4j.federated.endpoint.EndpointFactory;
import org.eclipse.rdf4j.federated.repository.FedXRepository;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import java.io.*;
import java.util.*;

public class ProcessQuery {

    private Op queryObject;
    private String classIndexFilePath = "";
    private String propertyIndexFilePath = "";
    private String cpIndexFilePath = "";
    private Set<Node> classes = new HashSet<>();
    private Set<Node> properties = new HashSet<>();
    private Map<String, Set<String>> classIndex = new HashMap<>();
    private Map<String, Set<String>> propertyIndex = new HashMap<>();
    private Map<String, Set<String>> cpIndex = new HashMap<>();

    public ProcessQuery(String sparqlQuery) {
        Query query = QueryFactory.create(sparqlQuery);
        this.queryObject = org.apache.jena.sparql.algebra.Algebra.compile(query);
    }

    public void setIndexLocation(String indexDir) {
        if (indexDir.trim().endsWith("/")) {
            this.classIndexFilePath = indexDir.trim() + "cinv.indx";
            this.propertyIndexFilePath = indexDir.trim() + "pinv.indx";
            this.cpIndexFilePath = indexDir.trim() + "cpinv.indx";
        } else {
            this.classIndexFilePath = indexDir.trim() + "/cinv.indx";
            this.propertyIndexFilePath = indexDir.trim() + "/pinv.indx";
            this.cpIndexFilePath = indexDir.trim() + "/cpinv.indx";
        }
    }

    public void extractClassesAndProperties() {
        OpWalker.walk(queryObject, new OpVisitorBase() {
            @Override
            public void visit(OpBGP opBGP) {
                BasicPattern triples = opBGP.getPattern();
                for (Triple triple : triples) {
                    Node subject = triple.getSubject();
                    Node predicate = triple.getPredicate();
                    Node object = triple.getObject();
                    if (subject.isURI())
                        classes.add(subject);
                    if (predicate.isURI())
                        properties.add(predicate);
                    if (object.isURI() && object.getURI().startsWith("http"))
                        classes.add(object);
                }
            }
        });
    }

    public void loadIndices() throws IOException {
        this.classIndex = loadIndexFromFile(this.classIndexFilePath);
        this.propertyIndex = loadIndexFromFile(this.propertyIndexFilePath);
        // this.cpIndex = loadIndexFromFile(this.cpIndexFilePath);
        // System.out.printf("%d\n%d\n",this.classIndex.size(),this.propertyIndex.size());
    }

    private Map<String, Set<String>> loadIndexFromFile(String filePath) throws IOException {
        Map<String, Set<String>> index = new HashMap<>();
        Gson gson = new Gson();
        Type mapType = new TypeToken<HashMap<String, Set<String>>>() {
        }.getType();
        try (FileReader reader = new FileReader(filePath)) {
            index = gson.fromJson(reader, mapType);
        }
        return index;
    }

    public Set<String> getEndpoints() {
        Set<String> endpoints = new HashSet<>();
        for (Node classUriRef : classes) {
            String classUri = classUriRef.getURI();
            if (classIndex.containsKey(classUri)) {
                System.out.println("Found class alignment: " + classUri);
                endpoints.addAll(classIndex.get(classUri));
            } else {
                System.out.println("Un-aligned class: " + classUri);
            }
        }
        for (Node propertyUriRef : properties) {
            String propertyUri = propertyUriRef.getURI();
            if (propertyIndex.containsKey(propertyUri)) {
                System.out.println("Found property alignment: " + propertyUri);
                endpoints.addAll(propertyIndex.get(propertyUri));
            } else {
                System.out.println("Un-aligned property: " + propertyUri);
            }
        }
        System.out.println("The Final Endpoints: ");
        System.out.println(endpoints);

        return endpoints;
    }

    public void processQuery(Set<String> endpoint_set, String query) {
        int counter = 0;
        List<Endpoint> endpoints = new ArrayList<>();

        for (String element : endpoint_set) {
            endpoints.add(EndpointFactory.loadSPARQLEndpoint("namesspace_" + counter++, element));
        }
        FedXRepository repository = FedXFactory.createFederation(endpoints);

        try (RepositoryConnection conn = repository.getConnection()) {

            TupleQuery tq = conn.prepareTupleQuery(query);
            try (TupleQueryResult tqRes = tq.evaluate()) {

                int count = 0;
                while (tqRes.hasNext()) {
                    BindingSet b = tqRes.next();
                    System.out.println(b);
                    count++;
                }

                System.out.println("Results: " + count);
            }
        }

        repository.shutDown();
    }

    public static void main(String[] args) throws IOException {
        String sparqlQuery = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n" +
                "PREFIX pt: <http://www.daml.org/2003/01/periodictable/PeriodicTable.owl#>\n" +
                "PREFIX OntoKin: <http://www.theworldavatar.com/ontology/ontokin/OntoKin.owl#>\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "\n" +
                "SELECT ?identifier ?atomicMass ?atomicMassUnits\n" +
                "WHERE {\n" +
                "    ?element1 rdf:type pt:Element .\n" +
                "    BIND(STRAFTER(STR(?element1), \"#\") AS ?identifier)\n" +
                "    ?element2 rdf:type OntoKin:Element .\n" +
                "    ?element2 rdfs:label ?identifier1 .\n" +
                "    ?element2 OntoKin:hasAtomicMass ?atomicMass .\n" +
                "    ?element2 OntoKin:hasAtomicMassUnits ?atomicMassUnits .\n" +
                "    FILTER(?identifier = ?identifier1)\n" +
                "}";

        Set<String> eps = new HashSet<>();

        ProcessQuery ana = new ProcessQuery(sparqlQuery);
        String indexLocation = "C:/Users/printer_admin/Downloads/KGs/tests/";
        ana.setIndexLocation(indexLocation);
        ana.loadIndices();
        ana.extractClassesAndProperties();
        eps = ana.getEndpoints();
        ana.processQuery(eps, sparqlQuery);
    }
}
