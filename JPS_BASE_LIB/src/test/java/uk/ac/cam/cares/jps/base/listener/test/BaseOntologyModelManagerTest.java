package uk.ac.cam.cares.jps.base.listener.test;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.VCARD;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.ac.cam.cares.jps.base.config.IKeys;
import uk.ac.cam.cares.jps.base.config.KeyValueMap;
import uk.ac.cam.cares.jps.base.listener.BaseOntologyModelManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class BaseOntologyModelManagerTest {

    @Rule
    public TemporaryFolder folder= new TemporaryFolder();

    @Test
    public void testSave(){
        OntModel testM = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        String testIRI = "testIRI";
        String testMmsi = "testMmsi";

        try{
            ExecutorService threadPool = Executors.newFixedThreadPool(5);
            for (int i = 0; i < 5; i++) {
                threadPool.execute(() -> {
                    BaseOntologyModelManager.save(testM, testIRI, testMmsi);
                });
            }
            threadPool.shutdown();
            TimeUnit.SECONDS.sleep(5);
        }catch (Exception e){
            Assert.assertTrue(e.getMessage().contains("Saving OWL failed: "));
        }
    }

    @Test
    public void testSaveToOwl() {
        String ABSDIR_ROOT_TEST =  KeyValueMap.getProperty("/jpstest.properties", IKeys.ABSDIR_ROOT);
        String ABSDIR_KB_TEST = ABSDIR_ROOT_TEST + "/kb/";

        OntModel testM = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        String testIRI = "testIRI";
        String testMmsi = "testMmsi";
        File file= new File(ABSDIR_KB_TEST + "/ships/testMmsi/Chimney-1.owl");

        try{
            BaseOntologyModelManager.saveToOwl(testM, testIRI, testMmsi);
            Assert.assertTrue(file.exists());
        }catch (Exception e){
            Assert.assertTrue(e.getMessage().contains("Saving OWL failed: "));
        }
   }

    @Test
    public void testPrepareDirectory() throws IOException {
        File createdFolder= folder.newFolder("testFolder");
        String testFilePath2 = createdFolder.getPath() + "/test";
        try{
            BaseOntologyModelManager.prepareDirectory(testFilePath2);
            Assert.assertTrue(createdFolder.isDirectory());
            Assert.assertTrue(createdFolder.list().length<1);
        }catch (Exception e){
            Assert.assertTrue(e.getMessage().contains("No such directory: "));
        }

    }

    @Test
    public void testQuery() {
        OntModel testM = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        String[] personURI ={ "http://somewhere/test", "http://somewhere/test",
                "http://somewhere/test3","http://somewhere/test4"};
        String[] testData = {"test1","test2","test3","test4"};

        for (int i=0;i<personURI.length;i++){
            Resource person = testM.createResource(personURI[i]);
            person.addProperty(VCARD.FN, testData[i]);
        }
        FileWriter fwriter = null;
        try {
            File testFile= folder.newFile("test.owl");
            fwriter = new FileWriter(testFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        testM.write(fwriter);

        String sparql = "SELECT ?z WHERE{<http://somewhere/test> ?y ?z}";
        ResultSet testrs = BaseOntologyModelManager.query(sparql, testM);
        String testRes = "";
        while (testrs.hasNext()) {
            QuerySolution qs = testrs.nextSolution();
            testRes = testRes + qs.get("z").toString() + "\n";
        }
        Assert.assertEquals("test2\ntest1\n", testRes);

    }

}