package uk.ac.cam.cares.jps.base.timeseries;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.Condition;
import org.jooq.CreateSchemaFinalStep;
import org.jooq.CreateTableColumnStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStep3;
import org.jooq.InsertValuesStepN;
import org.jooq.Record;
import org.jooq.Record3;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultDataType;
import org.jooq.impl.SQLDataType;
import org.postgis.Geometry;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;

/**
 * This class creates one column per data type, using the string given via
 * getTypeName as shown below:
 * DefaultDataType.getDataType(DIALECT, String.class).getTypeName()
 */
public class TimeSeriesRDBClientOntop<T> implements TimeSeriesRDBClientInterface<T> {
    /**
     * Logger for error output.
     */
    private static final Logger LOGGER = LogManager.getLogger(TimeSeriesRDBClientOntop.class);

    private static final String INFO_SCHEMA = "information_schema";

    // URL and credentials for the relational database
    private String rdbURL = null;
    private String rdbUser = null;
    private String rdbPassword = null;
    private String schema = null;
    // Time series column field (for RDB)
    private final Field<T> timeColumn;

    private static final String TS_LOOKUP_TABLE = "time_series_lookup_table";
    private static final String TS_DATA_TABLE = "time_series_data_table";
    private static final String TIME_COLUMN = "time";

    private static final Field<String> DATA_IRI_COLUMN = DSL.field(DSL.name("data_iri"), String.class);
    private static final Field<String> TS_IRI_COLUMN = DSL.field(DSL.name("time_series_iri"), String.class);
    private static final Field<String> DATA_TYPE_COLUMN = DSL.field(DSL.name("data_type"), String.class);
    private static final Field<Integer> DATA_INDEX_COLUMN = DSL.field(DSL.name("data_index"),
            SQLDataType.INTEGER.identity(true)); // makes the value increment automatically

    // Error message
    private static final String SQL_ERROR = "Error while executing SQL command";
    // Exception prefix
    private final String exceptionPrefix = this.getClass().getSimpleName() + ": ";

    private Class<T> timeClass;

    /**
     * Standard constructor
     * 
     * @param timeClass class of the timestamps of the time series
     */
    public TimeSeriesRDBClientOntop(Class<T> timeClass) {
        timeColumn = DSL.field(DSL.name(TIME_COLUMN), timeClass);
        this.timeClass = timeClass;
    }

    @Override
    public void setSchema(String schema) {
        this.schema = schema;
    }

    @Override
    public String getSchema() {
        return schema;
    }

    @Override
    public Class<T> getTimeClass() {
        return timeClass;
    }

    @Override
    public List<Integer> bulkInitTimeSeriesTable(List<List<String>> dataIRIs, List<List<Class<?>>> dataClasses,
            List<String> tsIRIs, Integer srid, Connection conn) {
        try {
            if (schema != null) {
                initSchemaIfNotExists(conn);
            }

            initCentralTableIfNotExists(conn);

            List<String> flatDataIRIs = dataIRIs.stream().flatMap(List::stream).collect(Collectors.toList());
            List<Class<?>> flatClasses = dataClasses.stream().flatMap(List::stream).collect(Collectors.toList());
            List<String> flatTsIri = new ArrayList<>();

            // Check if any data has already been initialised (i.e. is associated with
            // different tsIRI)
            String faultyDataIRI = checkAnyDataHasTimeSeries(flatDataIRIs, conn);
            if (faultyDataIRI != null) {
                throw new JPSRuntimeException(
                        exceptionPrefix + "<" + faultyDataIRI + "> already has an assigned time series instance");
            }

            // Ensure that there is a class for each data IRI
            for (int i = 0; i < dataIRIs.size(); i++) {
                if (dataIRIs.get(i).size() != dataClasses.get(i).size()) {
                    LOGGER.error("Length of dataClass is different from number of data IRIs");
                    // Assume all data IRIs have failed at the moment
                    return IntStream.range(0, dataIRIs.size()).boxed().collect(Collectors.toList());
                }
                flatTsIri.addAll(Collections.nCopies(dataIRIs.get(i).size(), tsIRIs.get(i)));
            }

            initDataTableIfNotExists(conn);
            initColumnsIfNotExist(flatClasses, srid, conn);
            updateLookupTable(flatDataIRIs, flatClasses, flatTsIri, srid, conn);

            // returning empty list because it is not really applicable to this class
            return new ArrayList<>();
        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(SQL_ERROR, e);
        }
    }

    @Override
    public void addTimeSeriesData(List<TimeSeries<T>> tsList, Connection conn) {
        List<String> dataIriFlatList = tsList.stream().map(ts -> ts.getDataIRIs()).flatMap(List::stream)
                .collect(Collectors.toList());

        // check if there are any shared data between time series or within time series
        Set<String> dataIriSet = new HashSet<>(dataIriFlatList);
        if (dataIriFlatList.size() != dataIriSet.size()) {
            throw new JPSRuntimeException(
                    exceptionPrefix + "duplicate data IRI found within time series list provided");
        }

        // perform a query to obtain column types associated with data to add
        DSLContext context = DSL.using(conn);
        Result<Record3<String, String, Integer>> queryResult = context
                .select(DATA_IRI_COLUMN, DATA_TYPE_COLUMN, DATA_INDEX_COLUMN)
                .from(getDSLTable(TS_LOOKUP_TABLE)).where(DATA_IRI_COLUMN.in(dataIriFlatList)).fetch();

        List<String> queriedIri = queryResult.getValues(DATA_IRI_COLUMN);
        List<String> queriedType = queryResult.getValues(DATA_TYPE_COLUMN);
        List<Integer> queriedIndex = queryResult.getValues(DATA_INDEX_COLUMN);

        Map<String, List<String>> columnToDataListMap = new HashMap<>();
        Map<String, Integer> iriToIndexMap = new HashMap<>();
        for (int i = 0; i < queriedIri.size(); i++) {
            columnToDataListMap.computeIfAbsent(queriedType.get(i), k -> new ArrayList<>());
            columnToDataListMap.get(queriedType.get(i)).add(queriedIri.get(i));

            iriToIndexMap.put(queriedIri.get(i), queriedIndex.get(i));
        }

        Map<String, List<?>> dataToValueListMap = new HashMap<>();
        Map<String, List<T>> dataToTimeListMap = new HashMap<>();

        tsList.forEach(ts -> {
            List<String> dataIriList = ts.getDataIRIs();

            dataIriList.forEach(dataIri -> {
                dataToValueListMap.put(dataIri, ts.getValues(dataIri));
                dataToTimeListMap.put(dataIri, ts.getTimes());
            });
        });

        // 1 query per data type/column, collect queries
        List<String> queries = new ArrayList<>();
        columnToDataListMap.entrySet().forEach(entry -> {
            String column = entry.getKey();
            List<String> dataIriList = entry.getValue();

            InsertValuesStep3<Record, T, Integer, Object> insertStep = context.insertInto(getDSLTable(TS_DATA_TABLE))
                    .columns(timeColumn, DATA_INDEX_COLUMN, DSL.field(DSL.name(column)));

            for (String dataIri : dataIriList) {
                List<T> timeList = dataToTimeListMap.get(dataIri);
                List<?> valueList = dataToValueListMap.get(dataIri);

                for (int i = 0; i < timeList.size(); i++) {
                    insertStep = insertStep.values(timeList.get(i), iriToIndexMap.get(dataIri), valueList.get(i));
                }
            }

            // upsert
            String upsertSql = String.format("ON CONFLICT (\"%s\", \"%s\") DO UPDATE SET \"%s\" = EXCLUDED.\"%s\"",
                    timeColumn.getName(), DATA_INDEX_COLUMN.getName(), column, column);

            StringBuilder insertStatement = new StringBuilder(insertStep.toString());
            insertStatement.append(System.lineSeparator()).append(upsertSql);

            queries.add(insertStatement.toString());
        });

        // execute queries
        try (Statement statement = conn.createStatement()) {
            for (String query : queries) {
                statement.addBatch(query);
            }
            statement.executeBatch();
        } catch (SQLException e) {
            String errmsg = exceptionPrefix + "Error while adding time series data";
            throw new JPSRuntimeException(errmsg, e);
        }
    }

    @Override
    public Map<String, TimeSeries<T>> bulkGetTimeSeries(List<String> dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'bulkGetTimeSeries'");
    }

    @Override
    public void initTimeSeriesTable(List<String> dataIRIList, List<Class<?>> dataClass, String tsIRI, Connection conn) {
        initTimeSeriesTable(dataIRIList, dataClass, tsIRI, null, conn);
    }

    @Override
    public void initTimeSeriesTable(List<String> dataIRI, List<Class<?>> dataClass, String tsIRI) {
        initTimeSeriesTable(dataIRI, dataClass, tsIRI, (Integer) null);
    }

    @Override
    public void initTimeSeriesTable(List<String> dataIRI, List<Class<?>> dataClass, String tsIRI, Integer srid) {
        try (Connection conn = getConnection()) {
            initTimeSeriesTable(dataIRI, dataClass, tsIRI, srid, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(e);
        }
    }

    @Override
    public void initTimeSeriesTable(List<String> dataIriList, List<Class<?>> dataClass, String tsIri, Integer srid,
            Connection conn) {
        List<List<String>> dataListList = new ArrayList<>();
        dataListList.add(dataIriList);

        List<List<Class<?>>> classListList = new ArrayList<>();
        classListList.add(dataClass);

        List<String> tsIriList = new ArrayList<>();
        tsIriList.add(tsIri);

        bulkInitTimeSeriesTable(dataListList, classListList, tsIriList, srid, conn);
    }

    @Override
    public void addTimeSeriesData(List<TimeSeries<T>> tsList) {
        try (Connection conn = getConnection()) {
            addTimeSeriesData(tsList, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(e);
        }
    }

    @Override
    public void deleteRows(String dataIRI, T lowerBound, T upperBound, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteRows'");
    }

    @Override
    public void deleteRows(String dataIRI, T lowerBound, T upperBound) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteRows'");
    }

    @Override
    public void deleteTimeSeries(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteTimeSeries'");
    }

    @Override
    public void deleteTimeSeries(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteTimeSeries'");
    }

    @Override
    public void deleteEntireTimeSeries(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteEntireTimeSeries'");
    }

    @Override
    public void deleteEntireTimeSeries(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteEntireTimeSeries'");
    }

    @Override
    public void deleteAll(Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteAll'");
    }

    @Override
    public void deleteAll() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteAll'");
    }

    @Override
    public TimeSeries<T> getTimeSeriesWithinBounds(List<String> dataIRI, T lowerBound, T upperBound, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTimeSeriesWithinBounds'");
    }

    @Override
    public TimeSeries<T> getTimeSeries(List<String> dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTimeSeries'");
    }

    @Override
    public TimeSeries<T> getLatestData(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getLatestData'");
    }

    @Override
    public TimeSeries<T> getOldestData(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getOldestData'");
    }

    @Override
    public double getAverage(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getAverage'");
    }

    @Override
    public double getMaxValue(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMaxValue'");
    }

    @Override
    public double getMinValue(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMinValue'");
    }

    @Override
    public T getMaxTime(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMaxTime'");
    }

    @Override
    public T getMinTime(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMinTime'");
    }

    @Override
    public void setRdbURL(String rdbURL) {
        this.rdbURL = rdbURL;
    }

    @Override
    public String getRdbURL() {
        return rdbURL;
    }

    @Override
    public void setRdbUser(String user) {
        this.rdbUser = user;
    }

    @Override
    public String getRdbUser() {
        return rdbUser;
    }

    @Override
    public void setRdbPassword(String password) {
        this.rdbPassword = password;
    }

    @Override
    public TimeSeries<T> getTimeSeriesWithinBounds(List<String> dataIRI, T lowerBound, T upperBound) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTimeSeriesWithinBounds'");
    }

    @Override
    public TimeSeries<T> getTimeSeries(List<String> dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTimeSeries'");
    }

    @Override
    public TimeSeries<T> getLatestData(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getLatestData'");
    }

    @Override
    public TimeSeries<T> getOldestData(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getOldestData'");
    }

    @Override
    public double getAverage(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getAverage'");
    }

    @Override
    public double getMaxValue(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMaxValue'");
    }

    @Override
    public double getMinValue(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMinValue'");
    }

    @Override
    public T getMaxTime(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMaxTime'");
    }

    @Override
    public T getMinTime(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMinTime'");
    }

    @Override
    public boolean checkDataHasTimeSeries(String dataIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'checkDataHasTimeSeries'");
    }

    @Override
    public boolean checkDataHasTimeSeries(String dataIRI) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'checkDataHasTimeSeries'");
    }

    @Override
    public String checkAnyDataHasTimeSeries(List<String> dataIRIs, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        Table<?> table = getDSLTable(TS_LOOKUP_TABLE);

        List<Condition> conditions = dataIRIs.stream().map(DATA_IRI_COLUMN::eq).collect(Collectors.toList());

        Condition combinedCondition = DSL.or(conditions);

        Result<Record> result = context.select().from(table).where(combinedCondition).fetch();

        // Check if the result is non-empty and return the first element
        if (!result.isEmpty()) {
            return result.get(0).getValue(DATA_IRI_COLUMN.getName(), String.class);
        }

        return null; // If no data IRI exists
    }

    @Override
    public void addColumnsToExistingTimeSeries(List<String> dataIRIs, List<Class<?>> dataClasses, String tsIri,
            Integer srid, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addColumnsToExistingTimeSeries'");
    }

    @Override
    public boolean timeSeriesExists(String tsIRI, Connection conn) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'timeSeriesExists'");
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");
            return DriverManager.getConnection(rdbURL, rdbUser, rdbPassword);
        } catch (ClassNotFoundException e) {
            throw new JPSRuntimeException(exceptionPrefix + "driver not found", e);
        }
    }

    private void initSchemaIfNotExists(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        try (CreateSchemaFinalStep createStep = context.createSchemaIfNotExists(DSL.name(schema))) {
            createStep.execute();
        }
    }

    /**
     * Initialise central database lookup table
     * 
     * @param context
     * 
     */
    private void initCentralTableIfNotExists(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        // Initialise central lookup table: only creates empty table if it does not
        // exist, otherwise it is left unchanged
        try (CreateTableColumnStep createStep = context.createTableIfNotExists(getDSLTable(TS_LOOKUP_TABLE))) {
            createStep.column(DATA_IRI_COLUMN).column(TS_IRI_COLUMN).column(DATA_INDEX_COLUMN).column(DATA_TYPE_COLUMN)
                    .constraints(DSL.primaryKey(DATA_INDEX_COLUMN)).execute();
        }

        context.createIndexIfNotExists("ts_lookup_table_iri_idx").on(getDSLTable(TS_LOOKUP_TABLE), DATA_IRI_COLUMN)
                .execute();
    }

    private void initDataTableIfNotExists(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        context.createTableIfNotExists(getDSLTable(TS_DATA_TABLE)).column(timeColumn).column(DATA_INDEX_COLUMN)
                .constraints(
                        DSL.foreignKey(DATA_INDEX_COLUMN).references(getDSLTable(TS_LOOKUP_TABLE), DATA_INDEX_COLUMN)
                                .onDeleteCascade(),
                        DSL.unique(DATA_INDEX_COLUMN, timeColumn))
                .execute();

        context.createIndexIfNotExists("ts_data_table_time_idx").on(getDSLTable(TS_DATA_TABLE), timeColumn).execute();
    }

    /**
     * Checks information_schema table to see whether column exists, if not, add the
     * new columns required
     * 
     * @param classes
     * @param conn
     * @throws SQLException
     */
    private void initColumnsIfNotExist(List<Class<?>> classes, Integer srid, Connection conn) throws SQLException {
        // collect geometry classes that are initialised separately
        List<String> additionalGeomColumns = new ArrayList<>();

        // column name for each class is fixed, will be used to check if they have been
        // initialised
        Set<Class<?>> classSet = new HashSet<Class<?>>(classes);
        Map<String, Class<?>> columnNameToClassMap = new HashMap<>();
        classSet.forEach(c -> {
            String columnName = getColumnName(c, srid);
            columnNameToClassMap.put(columnName, c);
            if (Geometry.class.isAssignableFrom(c)) {
                additionalGeomColumns.add(columnName);
            }
        });

        Set<String> columnNames = columnNameToClassMap.keySet();

        DSLContext context = DSL.using(conn, DIALECT);
        Table<Record> columns = DSL.table(DSL.name(INFO_SCHEMA, "columns"));
        Field<String> tableNameColumn = DSL.field(DSL.name("table_name"), String.class);
        Field<String> columnNameColumn = DSL.field(DSL.name("column_name"), String.class);

        Condition condition = tableNameColumn.eq(TS_DATA_TABLE).and(DATA_TYPE_COLUMN.in(columnNames));

        if (schema != null) {
            condition = condition.and(DSL.field(DSL.name("table_schema"), String.class).eq(schema));
        }

        // get columns that are already initialised in the database
        List<String> existingColumns = context.select(columnNameColumn).from(columns).where(condition)
                .fetch(columnNameColumn);

        // filter columns that are not initialised and are not geometries
        List<String> columnsToInitialise = columnNames.stream()
                .filter(c -> !existingColumns.contains(c) && !additionalGeomColumns.contains(c))
                .collect(Collectors.toList());

        if (!columnsToInitialise.isEmpty()) {
            List<Field<?>> fields = new ArrayList<>();
            for (int i = 0; i < columnsToInitialise.size(); i++) {
                String columnToInit = columnsToInitialise.get(i);
                fields.add(DSL.field(DSL.name(columnToInit), columnNameToClassMap.get(columnToInit)));
            }
            context.alterTable(getDSLTable(TS_DATA_TABLE)).add(fields).execute();
        }

        if (!additionalGeomColumns.isEmpty()) {
            addGeometryColumns(additionalGeomColumns, conn);
        }
    }

    private void addGeometryColumns(List<String> geometryColumns, Connection conn) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("ALTER TABLE %s ", getDSLTable(TS_DATA_TABLE).toString()));

        for (int i = 0; i < geometryColumns.size(); i++) {
            // type and column name are the same
            sb.append(String.format("ADD \"%s\" %s", geometryColumns.get(i), geometryColumns.get(i)));

            if (i != geometryColumns.size() - 1) {
                sb.append(", ");
            } else {
                sb.append(";");
            }
        }

        String sql = sb.toString();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void updateLookupTable(List<String> dataIriList, List<Class<?>> classes, List<String> tsIriList,
            Integer srid, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        List<Field<?>> columnList = new ArrayList<>();
        columnList.add(DATA_IRI_COLUMN);
        columnList.add(DATA_TYPE_COLUMN);
        columnList.add(TS_IRI_COLUMN);

        InsertValuesStepN<Record> insertStep = context.insertInto(getDSLTable(TS_LOOKUP_TABLE), columnList);

        for (int i = 0; i < dataIriList.size(); i++) {
            List<String> values = new ArrayList<>();
            values.add(dataIriList.get(i));
            values.add(getColumnName(classes.get(i), srid));
            values.add(tsIriList.get(i));

            insertStep = insertStep.values(values);
        }

        insertStep.execute();
    }

    /**
     * used during initialisation and checks
     */
    private String getColumnName(Class<?> clas, Integer srid) {
        if (Geometry.class.isAssignableFrom(clas)) {
            if (srid == null) {
                return String.format("geometry(%s)", clas.getSimpleName());
            } else {
                return String.format("geometry(%s,%d)", clas.getSimpleName(), srid);
            }
        } else {
            return DefaultDataType.getDataType(DIALECT, clas).getTypeName();
        }
    }

    private Table<Record> getDSLTable(String tableName) {
        if (schema != null) {
            return DSL.table(DSL.name(schema, tableName));
        } else {
            return DSL.table(DSL.name(tableName));
        }
    }
}
