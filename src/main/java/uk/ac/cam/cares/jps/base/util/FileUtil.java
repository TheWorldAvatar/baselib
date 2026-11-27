package uk.ac.cam.cares.jps.base.util;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

public class FileUtil {

    /**
     * Reads a CSV file and returns the content as a list of lines. Each line</br>
     * is also codified as a list of elements.
     * 
     * @param fileName
     * @return
     * @throws IOException
     * @throws CsvValidationException
     */
    public static List<List<String>> openCSVSourceFile(String fileName) throws IOException, CsvValidationException {
        List<List<String>> records = new ArrayList<List<String>>();
        try (CSVReader csvReader = new CSVReader(new FileReader(fileName));) {
            String[] values = null;
            while ((values = csvReader.readNext()) != null) {
                records.add(Arrays.asList(values));
            }
        }
        return records;
    }
}
