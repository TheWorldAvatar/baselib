package uk.ac.cam.cares.jps.base.timeseries;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.CreateSchemaFinalStep;
import org.jooq.CreateTableColumnStep;
import org.jooq.CreateTableConstraintStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStep1;
import org.jooq.InsertValuesStep2;
import org.jooq.InsertValuesStep3;
import org.jooq.InsertValuesStep4;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultDataType;
import org.jooq.impl.SQLDataType;
import org.postgis.Geometry;
import org.postgis.Point;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;

/**
 * This class creates one column per data type, using the string given via
 * getTypeName as shown below:
 * DefaultDataType.getDataType(DIALECT, String.class).getTypeName()
 */
public class TimeSeriesRDBClientOntop<T> implements TimeSeriesRDBClientInterface<T> {
    // these classes + srid combination are pre-initialised in the tables and ontop
    // mapping if the provided classes do not fall within these classes checks will
    // be made against the database to make the necessary initialisation
    // However the ontop mapping will not include the extra classes
    private static final List<Class<?>> PRECONFIGURED_DATA_CLASSES = Arrays.asList(Double.class, Point.class,
            Integer.class);
    private static final int PRECONFIGURED_SRID = 4326;

    private static final List<Class<?>> TIMESTAMP_CLASSES = Arrays.asList(Instant.class, OffsetDateTime.class,
            ZonedDateTime.class, LocalDateTime.class);

    private static final String TIME_NUMBER_COLUMN = "time_as_number";
    private static final String TIME_TIMESTAMP_COLUMN = "time_as_timestamp";

    /**
     * Logger for error output.
     */
    private static final Logger LOGGER = LogManager.getLogger(TimeSeriesRDBClientOntop.class);

    // URL and credentials for the relational database
    private String rdbURL = null;
    private String rdbUser = null;
    private String rdbPassword = null;
    private String schema = null;
    // Time series column field (for RDB)
    private final Field<T> timeColumn;
    private final Field<?> theOtherTimeColumn;

    private static final String TS_DATA_IRI_TABLE = "time_series_data_iri";
    private static final String TS_DATA_TABLE = "time_series_data";
    private static final String TS_DATA_TYPE_TABLE = "time_series_data_type";

    private static final Field<String> DATA_IRI_COLUMN = DSL.field(DSL.name("data_iri"), String.class);
    private static final Field<String> DATA_TYPE_COLUMN = DSL.field(DSL.name("data_type"), String.class);

    private static final Field<Integer> DATA_TYPE_INDEX_COLUMN = DSL.field(DSL.name("data_type_index"), Integer.class);
    private static final Field<Integer> DATA_TYPE_INDEX_COLUMN_SERIAL = DSL.field(DSL.name("data_type_index"),
            SQLDataType.INTEGER.identity(true));// makes the value increment automatically

    private static final Field<Integer> DATA_IRI_INDEX_COLUMN = DSL.field(DSL.name("data_iri_index"), Integer.class);
    private static final Field<Integer> DATA_IRI_INDEX_COLUMN_SERIAL = DSL.field(DSL.name("data_iri_index"),
            SQLDataType.INTEGER.identity(true));

    private static final Field<Integer> DATA_INDEX_COLUMN = DSL.field(DSL.name("id"),
            SQLDataType.INTEGER.identity(true));

    // Error message
    private static final String SQL_ERROR = "Error while executing SQL command";
    // Exception prefix
    private final String exceptionPrefix = this.getClass().getSimpleName() + ": ";

    private Class<T> timeClass;

    /**
     * Standard constructor
     * There are two time columns, one for numbers, and one for timestamp
     * Instant is the special class where both columns are populated
     * All other classes only use one time column
     * 
     * @param timeClass class of the timestamps of the time series
     */
    public TimeSeriesRDBClientOntop(Class<T> timeClass) {
        // special Instant case
        if (timeClass == Instant.class) {
            timeColumn = DSL.field(DSL.name(TIME_TIMESTAMP_COLUMN),
                    DefaultDataType.getDataType(DIALECT, timeClass).nullable(false));
            // to store unix timestamp
            theOtherTimeColumn = DSL.field(DSL.name(TIME_NUMBER_COLUMN),
                    DefaultDataType.getDataType(DIALECT, Double.class).nullable(false));

        } else if (TIMESTAMP_CLASSES.contains(timeClass)) {
            // timestamp case
            timeColumn = DSL.field(DSL.name(TIME_TIMESTAMP_COLUMN),
                    DefaultDataType.getDataType(DIALECT, timeClass).nullable(false));
            // dummy column
            theOtherTimeColumn = DSL.field(DSL.name(TIME_NUMBER_COLUMN),
                    DefaultDataType.getDataType(DIALECT, Double.class));

        } else if (Number.class.isAssignableFrom(timeClass)) {
            // number case
            timeColumn = DSL.field(DSL.name(TIME_NUMBER_COLUMN),
                    DefaultDataType.getDataType(DIALECT, timeClass).nullable(false));
            // dummy column
            theOtherTimeColumn = DSL.field(DSL.name(TIME_TIMESTAMP_COLUMN),
                    DefaultDataType.getDataType(DIALECT, Instant.class));
        } else {
            throw new JPSRuntimeException("Unsupported time class: " + timeClass.getSimpleName());
        }
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
        if (tsIRIs != null) {
            if (!tsIRIs.isEmpty()) {
                LOGGER.warn("Time series IRIs will be ignored for this RDB client class");
            }
        }
        try {
            if (schema != null) {
                initSchemaIfNotExists(conn);
            }
            initDataTypeTableIfNotExists(conn);
            initDataIriTableIfNotExists(conn);

            List<String> flatDataIRIs = dataIRIs.stream().flatMap(List::stream).collect(Collectors.toList());
            List<Class<?>> flatClasses = dataClasses.stream().flatMap(List::stream).collect(Collectors.toList());

            // Check if any data has already been initialised (i.e. is associated with
            // different tsIRI)
            String faultyDataIRI = checkAnyDataHasTimeSeries(flatDataIRIs, conn);
            if (faultyDataIRI != null) {
                String errmsg = exceptionPrefix + "<" + faultyDataIRI
                        + "> already has an assigned time series instance";
                LOGGER.error(errmsg);
                throw new JPSRuntimeException(errmsg);
            }

            // Ensure that there is a class for each data IRI
            for (int i = 0; i < dataIRIs.size(); i++) {
                if (dataIRIs.get(i).size() != dataClasses.get(i).size()) {
                    LOGGER.error("Length of dataClass is different from number of data IRIs");
                    // Assume all data IRIs have failed at the moment
                    return IntStream.range(0, dataIRIs.size()).boxed().collect(Collectors.toList());
                }
            }

            initDataTableIfNotExists(conn);
            initDataTypesIfNotExist(flatClasses, srid, conn);
            updateDataIriTable(flatDataIRIs, flatClasses, srid, conn);

            // returning empty list because it is not really applicable to this class
            return new ArrayList<>();
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
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(dataIriFlatList, conn);

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
        DSLContext context = DSL.using(DIALECT);
        dataColumnMetadata.getColumns().forEach(column -> {
            List<String> dataIriList = dataColumnMetadata.getDataIriList(column);

            // special case, both time columns are used
            if (timeClass == Instant.class) {
                InsertValuesStep4<Record, T, Double, Integer, Object> insertStep = context
                        .insertInto(getDSLTable(TS_DATA_TABLE)).columns(timeColumn, (Field<Double>) theOtherTimeColumn,
                                DATA_IRI_INDEX_COLUMN,
                                DSL.field(DSL.name(column)));
                int numRows = 0;
                for (String dataIri : dataIriList) {
                    List<T> timeList = dataToTimeListMap.get(dataIri);
                    List<?> valueList = dataToValueListMap.get(dataIri);
                    int dataIndex = dataColumnMetadata.getIndex(dataIri);

                    for (int i = 0; i < timeList.size(); i++) {
                        if (timeList.get(i) == null) {
                            LOGGER.warn("Null time value detected, skipping");
                            continue;
                        }

                        double millisecondsAsDouble = ((Instant) timeList.get(i)).toEpochMilli();
                        insertStep = insertStep.values(timeList.get(i), millisecondsAsDouble / 1000, dataIndex,
                                valueList.get(i));
                        numRows += 1;
                    }
                }

                if (numRows > 0) {
                    // append upsert query manually
                    String upsertSql = String.format(
                            "ON CONFLICT (\"%s\", \"%s\") DO UPDATE SET \"%s\" = EXCLUDED.\"%s\"",
                            timeColumn.getName(), DATA_IRI_INDEX_COLUMN.getName(), column, column);
                    StringBuilder insertStatement = new StringBuilder(insertStep.toString());
                    insertStatement.append(System.lineSeparator()).append(upsertSql);
                    queries.add(insertStatement.toString());
                }
            } else {
                // only one time column is used
                InsertValuesStep3<Record, T, Integer, Object> insertStep = context
                        .insertInto(getDSLTable(TS_DATA_TABLE))
                        .columns(timeColumn, DATA_IRI_INDEX_COLUMN, DSL.field(DSL.name(column)));
                int numRows = 0;
                for (String dataIri : dataIriList) {
                    List<T> timeList = dataToTimeListMap.get(dataIri);
                    List<?> valueList = dataToValueListMap.get(dataIri);
                    int dataIndex = dataColumnMetadata.getIndex(dataIri);

                    for (int i = 0; i < timeList.size(); i++) {
                        if (timeList.get(i) == null) {
                            LOGGER.warn("Null time value detected, skipping");
                            continue;
                        }
                        insertStep = insertStep.values(timeList.get(i), dataIndex, valueList.get(i));
                        numRows += 1;
                    }
                }

                if (numRows > 0) {
                    // append upsert query manually
                    String upsertSql = String.format(
                            "ON CONFLICT (\"%s\", \"%s\") DO UPDATE SET \"%s\" = EXCLUDED.\"%s\"",
                            timeColumn.getName(), DATA_IRI_INDEX_COLUMN.getName(), column, column);
                    StringBuilder insertStatement = new StringBuilder(insertStep.toString());
                    insertStatement.append(System.lineSeparator()).append(upsertSql);
                    queries.add(insertStatement.toString());
                }
            }
        });

        // execute queries
        if (queries.isEmpty()) {
            return;
        }

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

    private DataColumnMetadata getDataColumnMetadata(List<String> dataIriList, Connection conn) {
        DSLContext context = DSL.using(conn);

        Field<Integer> aliasedField1 = DSL.field(DSL.name("a", "data_type_index"), SQLDataType.INTEGER.identity(true));
        Field<Integer> aliasedField2 = DSL.field(DSL.name("b", "data_type_index"), SQLDataType.INTEGER.identity(true));

        Result<Record3<String, String, Integer>> queryResult = context
                .select(DATA_IRI_COLUMN, DATA_TYPE_COLUMN, DATA_IRI_INDEX_COLUMN)
                .from(getDSLTable(TS_DATA_IRI_TABLE).as("a"))
                .leftJoin(getDSLTable(TS_DATA_TYPE_TABLE).as("b")).on(aliasedField1.eq(aliasedField2))
                .where(DATA_IRI_COLUMN.in(dataIriList)).fetch();

        DataColumnMetadata dataColumnMetadata = new DataColumnMetadata();
        for (Record3<String, String, Integer> row : queryResult) {
            String dataType = row.get(DATA_TYPE_COLUMN);
            String dataIri = row.get(DATA_IRI_COLUMN);
            int dataIndex = row.get(DATA_IRI_INDEX_COLUMN);

            dataColumnMetadata.setColumn(dataIri, dataType);
            dataColumnMetadata.setIndex(dataIri, dataIndex);
            dataColumnMetadata.setType(dataIndex, dataType);
        }

        return dataColumnMetadata;
    }

    /**
     * each time series will contain only a single dataset in this Ontop version
     */
    @Override
    public Map<String, TimeSeries<T>> bulkGetTimeSeries(List<String> dataIriList, Connection conn) {
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(dataIriList, conn);

        List<Field<?>> columnFields = dataColumnMetadata.getColumns().stream().map(c -> DSL.field(DSL.name(c)))
                .collect(Collectors.toList());
        columnFields.add(timeColumn);
        columnFields.add(DATA_IRI_INDEX_COLUMN);

        DSLContext context = DSL.using(conn);
        Result<Record> queryResult = context.select(columnFields).from(getDSLTable(TS_DATA_TABLE))
                .where(DATA_IRI_INDEX_COLUMN.in(dataColumnMetadata.getAllIndex())).orderBy(timeColumn.asc()).fetch();

        Map<String, TimeSeries<T>> iriToTsMap = new HashMap<>();
        Map<Integer, List<Object>> indexToValueListMap = new HashMap<>();
        Map<Integer, List<T>> indexToTimeListMap = new HashMap<>();

        for (Record row : queryResult) {
            int dataIndex = row.get(DATA_IRI_INDEX_COLUMN);
            String column = dataColumnMetadata.getType(dataIndex);

            Field<Object> columnField = DSL.field(DSL.name(column));

            indexToValueListMap.computeIfAbsent(dataIndex, k -> new ArrayList<>());
            indexToValueListMap.get(dataIndex).add(row.get(columnField));

            indexToTimeListMap.computeIfAbsent(dataIndex, k -> new ArrayList<>());
            indexToTimeListMap.get(dataIndex).add(row.get(timeColumn));
        }

        indexToValueListMap.entrySet().forEach(entry -> {
            int index = entry.getKey();
            List<Object> values = entry.getValue();
            List<T> timeList = indexToTimeListMap.get(index);
            String dataIri = dataColumnMetadata.getDataIri(index);

            List<List<?>> valuesList = new ArrayList<>();
            valuesList.add(values);

            TimeSeries<T> timeseries = new TimeSeries<>(timeList, Arrays.asList(dataIri), valuesList);
            iriToTsMap.put(dataIri, timeseries);
        });

        return iriToTsMap;
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
            String errmsg = exceptionPrefix + "Error while adding time series data";
            LOGGER.error(errmsg);
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(errmsg, e);
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
        if (dataIRI.size() != 1) {
            String errmsg = "Only one IRI can be provided for this class";
            throw new JPSRuntimeException(errmsg);
        }
        Map<String, TimeSeries<T>> map = bulkGetTimeSeries(dataIRI, conn);
        return map.values().iterator().next();
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
        try (Connection conn = getConnection()) {
            return getTimeSeries(dataIRI, conn);
        } catch (SQLException e) {
            String errmsg = exceptionPrefix + "Error while getting time series data";
            LOGGER.error(errmsg);
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(errmsg, e);
        }
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

        Table<?> table = getDSLTable(TS_DATA_IRI_TABLE);

        List<String> result = context.select(DATA_IRI_COLUMN).from(table).where(DATA_IRI_COLUMN.in(dataIRIs))
                .fetch(DATA_IRI_COLUMN);

        // Check if the result is non-empty and return the first element
        if (!result.isEmpty()) {
            return result.get(0);
        }

        return null; // If no data IRI exists
    }

    @Override
    public void addColumnsToExistingTimeSeries(List<String> dataIRIs, List<Class<?>> dataClasses, String tsIri,
            Integer srid, Connection conn) {
        throw new UnsupportedOperationException("'addColumnsToExistingTimeSeries' is not applicable to this class");
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

    private void initDataTypeTableIfNotExists(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        context.createTableIfNotExists(getDSLTable(TS_DATA_TYPE_TABLE)).column(DATA_TYPE_COLUMN)
                .column(DATA_TYPE_INDEX_COLUMN_SERIAL)
                .constraints(DSL.unique(DATA_TYPE_COLUMN), DSL.primaryKey(DATA_TYPE_INDEX_COLUMN_SERIAL)).execute();

        // add preconfigured data types
        InsertValuesStep1<Record, String> insertStep = context.insertInto(getDSLTable(TS_DATA_TYPE_TABLE),
                DATA_TYPE_COLUMN);

        for (Class<?> clas : PRECONFIGURED_DATA_CLASSES) {
            insertStep = insertStep.values(getColumnName(clas, PRECONFIGURED_SRID));
        }
        insertStep.onConflictDoNothing().execute();
    }

    /**
     * Initialise central database lookup table
     * 
     * @param context
     * 
     */
    private void initDataIriTableIfNotExists(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        try (CreateTableColumnStep createStep = context.createTableIfNotExists(getDSLTable(TS_DATA_IRI_TABLE))) {
            createStep.column(DATA_IRI_COLUMN).column(DATA_IRI_INDEX_COLUMN_SERIAL).column(DATA_TYPE_INDEX_COLUMN)
                    .constraints(
                            DSL.unique(DATA_IRI_COLUMN),
                            DSL.primaryKey(DATA_IRI_INDEX_COLUMN_SERIAL),
                            DSL.foreignKey(DATA_TYPE_INDEX_COLUMN)
                                    .references(getDSLTable(TS_DATA_TYPE_TABLE), DATA_TYPE_INDEX_COLUMN)
                                    .onDeleteCascade())
                    .execute();
        }

        context.createIndexIfNotExists("ts_data_iri_table_data_type_idx")
                .on(getDSLTable(TS_DATA_IRI_TABLE), DATA_TYPE_INDEX_COLUMN).execute();
    }

    private void initDataTableIfNotExists(Connection conn) {
        // row id used for ontop
        DSLContext context = DSL.using(conn, DIALECT);

        CreateTableColumnStep createStep = context.createTableIfNotExists(getDSLTable(TS_DATA_TABLE))
                .column(DATA_INDEX_COLUMN).column(timeColumn).column(theOtherTimeColumn).column(DATA_IRI_INDEX_COLUMN);

        // add data columns
        int numGeomColumn = 0;
        for (Class<?> clas : PRECONFIGURED_DATA_CLASSES) {
            String columnName = getColumnName(clas, PRECONFIGURED_SRID);
            if (Geometry.class.isAssignableFrom(clas)) {
                createStep = createStep.column(DSL.field(DSL.name(columnName)));
                numGeomColumn += 1;
            } else {
                createStep = createStep.column(DSL.field(DSL.name(columnName), clas));
            }
        }

        if (numGeomColumn > 1) {
            throw new JPSRuntimeException(
                    "Make sure to modify the create table query accordingly after modifying PRECONFIGURED_DATA_CLASSES");
        }

        // add constraints and execute
        CreateTableConstraintStep constraintStep = createStep.constraints(
                DSL.primaryKey(DATA_INDEX_COLUMN),
                DSL.foreignKey(DATA_IRI_INDEX_COLUMN)
                        .references(getDSLTable(TS_DATA_IRI_TABLE), DATA_IRI_INDEX_COLUMN)
                        .onDeleteCascade(),
                DSL.unique(DATA_IRI_INDEX_COLUMN, timeColumn));

        // this is a hack to add geometry column
        String sql = constraintStep.toString().replace("any", getColumnName(Point.class, PRECONFIGURED_SRID));

        // submit query manually
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            String errmsg = exceptionPrefix + "Error while creating time series data table";
            throw new JPSRuntimeException(errmsg, e);
        }
    }

    /**
     * Checks information_schema table to see whether column exists, if not, add the
     * new columns required
     * 
     * @param classes
     * @param conn
     * @throws SQLException
     */
    private void initDataTypesIfNotExist(List<Class<?>> classes, Integer srid, Connection conn) throws SQLException {
        Set<Class<?>> classSet = new HashSet<Class<?>>(classes);
        // check if there is any class that is outside of the preconfigured classes
        List<Class<?>> classesToInit = new ArrayList<>();
        classSet.forEach(c -> {
            if ((Geometry.class.isAssignableFrom(c) && srid != PRECONFIGURED_SRID)
                    || !PRECONFIGURED_DATA_CLASSES.contains(c)) {
                classesToInit.add(c);
            }
        });

        if (classesToInit.isEmpty()) {
            return;
        }

        LOGGER.warn("Initialising additional data types that will not be in the Ontop mapping");

        // collect geometry classes that are initialised separately
        List<String> additionalGeomColumns = new ArrayList<>();

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

        // get columns that are already initialised in the database
        List<String> existingColumns = context.select(DATA_TYPE_COLUMN).from(getDSLTable(TS_DATA_TYPE_TABLE))
                .where(DATA_TYPE_COLUMN.in(columnNames)).fetch(DATA_TYPE_COLUMN);

        // filter columns that are not initialised
        List<String> columnsToInitialise = columnNames.stream()
                .filter(c -> !existingColumns.contains(c)).collect(Collectors.toList());

        // two queries are performed here, one to add entries to the data type table,
        // and the second to add columns to the data table. Geometry columns are added
        // separately later due to jooq's restriction
        if (!columnsToInitialise.isEmpty()) {
            List<Field<?>> fields = new ArrayList<>();
            InsertValuesStep1<Record, String> insertStep = context.insertInto(getDSLTable(TS_DATA_TYPE_TABLE),
                    DATA_TYPE_COLUMN);
            for (int i = 0; i < columnsToInitialise.size(); i++) {
                String columnToInit = columnsToInitialise.get(i);

                if (!additionalGeomColumns.contains(columnToInit)) {
                    fields.add(DSL.field(DSL.name(columnToInit), columnNameToClassMap.get(columnToInit)));
                }

                insertStep.values(columnToInit);
            }
            insertStep.execute();

            if (!fields.isEmpty()) {
                context.alterTable(getDSLTable(TS_DATA_TABLE)).add(fields).execute();
            }
        }

        // check if any of the geometry columns have been initialised
        List<String> geomColumnsToAdd = additionalGeomColumns.stream().filter(c -> !existingColumns.contains(c))
                .collect(Collectors.toList());
        if (!geomColumnsToAdd.isEmpty()) {
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

    private void updateDataIriTable(List<String> dataIriList, List<Class<?>> classes, Integer srid, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        // first query the data type index from the data type table
        List<String> columnNames = classes.stream().map(c -> getColumnName(c, srid)).collect(Collectors.toList());

        Result<Record2<String, Integer>> queryResult = context.select(DATA_TYPE_COLUMN, DATA_TYPE_INDEX_COLUMN)
                .from(getDSLTable(TS_DATA_TYPE_TABLE)).where(DATA_TYPE_COLUMN.in(columnNames)).fetch();

        Map<String, Integer> dataTypeToIndexMap = new HashMap<>();
        for (Record2<String, Integer> record : queryResult) {
            dataTypeToIndexMap.put(record.get(DATA_TYPE_COLUMN), record.get(DATA_TYPE_INDEX_COLUMN));
        }

        InsertValuesStep2<Record, String, Integer> insertStep = context.insertInto(getDSLTable(TS_DATA_IRI_TABLE),
                DATA_IRI_COLUMN, DATA_TYPE_INDEX_COLUMN);

        for (int i = 0; i < dataIriList.size(); i++) {
            int dataIndex = dataTypeToIndexMap.get(getColumnName(classes.get(i), srid));
            insertStep = insertStep.values(dataIriList.get(i), dataIndex);
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

    private String prepareMapping() {
        String unixTRS = "http://dbpedia.org/resource/Unix_time";
        String generic = "http://example.org/TRS_placeholder";

        String obdaTemplate;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("timeseries_ontop_template.obda")) {
            obdaTemplate = IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JPSRuntimeException("Error while reading timeseries_ontop_template.obda", e);
        }

        if (timeClass == Instant.class) {
            obdaTemplate.replace("[TRS_REPLACE]", unixTRS);
        } else {
            obdaTemplate.replace("[TRS_REPLACE]", generic);
        }

        return obdaTemplate;
    }

    private class DataColumnMetadata {
        private Map<String, List<String>> columnToDataListMap = new HashMap<>();
        private Map<String, Integer> dataIriToIndexMap = new HashMap<>();
        private Map<Integer, String> indexToDataIriMap = new HashMap<>();
        private Map<Integer, String> indexToTypeMap = new HashMap<>();

        void setColumn(String dataIri, String column) {
            columnToDataListMap.computeIfAbsent(column, k -> new ArrayList<>());
            columnToDataListMap.get(column).add(dataIri);
        }

        void setIndex(String dataIri, int index) {
            dataIriToIndexMap.put(dataIri, index);
            indexToDataIriMap.put(index, dataIri);
        }

        void setType(int index, String type) {
            indexToTypeMap.put(index, type);
        }

        String getType(int index) {
            return indexToTypeMap.get(index);
        }

        Set<String> getColumns() {
            return columnToDataListMap.keySet();
        }

        List<String> getDataIriList(String column) {
            return columnToDataListMap.get(column);
        }

        int getIndex(String dataIri) {
            return dataIriToIndexMap.get(dataIri);
        }

        String getDataIri(int index) {
            return indexToDataIriMap.get(index);
        }

        List<Integer> getAllIndex() {
            return new ArrayList<>(dataIriToIndexMap.values());
        }
    }
}
