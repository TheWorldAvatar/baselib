package uk.ac.cam.cares.jps.base.timeseries;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.Condition;
import org.jooq.CreateSchemaFinalStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStep1;
import org.jooq.InsertValuesStep2;
import org.jooq.InsertValuesStep3;
import org.jooq.InsertValuesStep5;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
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
    private static final List<Class<?>> PRECONFIGURED_DATA_CLASSES = Arrays.asList(Double.class, Integer.class);
    private static final List<Class<?>> PRECONFIGURED_GEOMETRY_CLASSES = Arrays.asList(Point.class);
    private static final Integer PRECONFIGURED_SRID = 4326;

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

    private static final String DATA_TYPE_INDEX = "data_type_index";
    private static final Field<Integer> DATA_TYPE_INDEX_COLUMN = DSL.field(DSL.name(DATA_TYPE_INDEX), Integer.class);
    private static final Field<Integer> DATA_TYPE_INDEX_COLUMN_SERIAL = DSL.field(DSL.name(DATA_TYPE_INDEX),
            SQLDataType.INTEGER.identity(true));// makes the value increment automatically

    private static final String DATA_IRI_INDEX = "data_iri_index";
    private static final Field<Integer> DATA_IRI_INDEX_COLUMN = DSL.field(DSL.name(DATA_IRI_INDEX), Integer.class);
    private static final Field<Integer> DATA_IRI_INDEX_COLUMN_SERIAL = DSL.field(DSL.name(DATA_IRI_INDEX),
            SQLDataType.INTEGER.identity(true));

    private static final Field<Integer> DATA_INDEX_COLUMN = DSL.field(DSL.name("id"),
            SQLDataType.INTEGER.identity(true));

    private static final Field<String> UNIT_COLUMN = DSL.field(DSL.name("unit"), String.class);

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
        if (schema == null) {
            return "public";
        } else {
            return schema;
        }
    }

    @Override
    public Class<T> getTimeClass() {
        return timeClass;
    }

    @Override
    public List<Integer> bulkInitTimeSeriesTable(List<List<String>> dataIRIs, List<List<Class<?>>> dataClasses,
            List<String> tsIRIs, Integer srid, Connection conn) {
        if (tsIRIs != null && !tsIRIs.isEmpty()) {
            LOGGER.warn("Time series IRIs will be ignored for this RDB client class");
        }
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
                throw new JPSRuntimeException("Length of dataClass is different from number of data IRIs");
            }
        }

        initDataTableIfNotExists(conn);
        initDataTypesIfNotExist(flatClasses, srid, conn);
        updateDataIriTable(flatDataIRIs, flatClasses, srid, conn);

        // returning empty list because it is not really applicable to this class
        return new ArrayList<>();
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

        if (dataColumnMetadata.getColumns().isEmpty()) {
            throw new JPSRuntimeException(exceptionPrefix + "Provided data IRI does not exist. " + dataIriFlatList);
        }

        Map<String, List<?>> dataToValueListMap = new HashMap<>();
        Map<String, List<T>> dataToTimeListMap = new HashMap<>();
        Map<String, String> dataToUnitMap = new HashMap<>();

        tsList.forEach(ts -> {
            List<String> dataIriList = ts.getDataIRIs();

            dataIriList.forEach(dataIri -> {
                dataToValueListMap.put(dataIri, ts.getValues(dataIri));
                dataToTimeListMap.put(dataIri, ts.getTimes());
                dataToUnitMap.put(dataIri, ts.getUnit(dataIri));
            });
        });

        // 1 query per data type/column, collect queries
        List<String> queries = new ArrayList<>();
        DSLContext context = DSL.using(DIALECT);
        dataColumnMetadata.getColumns().forEach(column -> {
            List<String> dataIriList = dataColumnMetadata.getDataIriList(column);

            // special case, both time columns are used
            if (timeClass == Instant.class) {
                InsertValuesStep5<Record, T, Double, Integer, Object, String> insertStep = context
                        .insertInto(getDSLTable(TS_DATA_TABLE)).columns(timeColumn, (Field<Double>) theOtherTimeColumn,
                                DATA_IRI_INDEX_COLUMN, DSL.field(DSL.name(column)), UNIT_COLUMN);
                int numRows = 0;
                for (String dataIri : dataIriList) {
                    List<T> timeList = dataToTimeListMap.get(dataIri);
                    List<?> valueList = dataToValueListMap.get(dataIri);
                    int dataIndex = dataColumnMetadata.getIndex(dataIri);
                    String unit = dataToUnitMap.get(dataIri);

                    for (int i = 0; i < timeList.size(); i++) {
                        if (timeList.get(i) == null) {
                            LOGGER.warn("Null time value detected, skipping");
                            continue;
                        }

                        double millisecondsAsDouble = ((Instant) timeList.get(i)).toEpochMilli();
                        insertStep = insertStep.values(timeList.get(i), millisecondsAsDouble / 1000, dataIndex,
                                valueList.get(i), unit);
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

        Field<Integer> aliasedField1 = DSL.field(DSL.name("a", DATA_TYPE_INDEX), SQLDataType.INTEGER.identity(true));
        Field<Integer> aliasedField2 = DSL.field(DSL.name("b", DATA_TYPE_INDEX), SQLDataType.INTEGER.identity(true));

        Result<Record3<String, String, Integer>> queryResult;
        try {
            queryResult = context
                    .select(DATA_IRI_COLUMN, DATA_TYPE_COLUMN, DATA_IRI_INDEX_COLUMN)
                    .from(getDSLTable(TS_DATA_IRI_TABLE).as("a"))
                    .leftJoin(getDSLTable(TS_DATA_TYPE_TABLE).as("b")).on(aliasedField1.eq(aliasedField2))
                    .where(DATA_IRI_COLUMN.in(dataIriList)).fetch();
        } catch (DataAccessException e) {
            String errmsg = "Time series tables have not been initialised";
            throw new JPSRuntimeException(errmsg, e);
        }

        if (queryResult.isEmpty()) {
            String errmsg = "Provided data IRI(s) does not exist yet: " + dataIriList;
            throw new JPSRuntimeException(errmsg);
        }

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

        // create empty time series objects
        if (queryResult.isEmpty()) {
            dataIriList.forEach(d -> {
                List<List<?>> valuesList = new ArrayList<>();
                valuesList.add(new ArrayList<>());
                iriToTsMap.put(d, new TimeSeries<>(new ArrayList<>(), Arrays.asList(d), valuesList));
            });
        }

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
            throw new JPSRuntimeException("Error in initTimeSeriesTable", e);
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
            throw new JPSRuntimeException("Error while adding time series data", e);
        }
    }

    @Override
    public void deleteRows(String dataIRI, T lowerBound, T upperBound, Connection conn) {
        DSLContext context = DSL.using(conn);

        context.deleteFrom(getDSLTable(TS_DATA_TABLE)).where(DATA_IRI_INDEX_COLUMN.eq(
                DSL.select(DATA_IRI_INDEX_COLUMN)
                        .from(getDSLTable(TS_DATA_IRI_TABLE).where(DATA_IRI_COLUMN.eq(dataIRI))))
                .and(timeColumn.ge(lowerBound))
                .and(timeColumn.le(upperBound))).execute();
    }

    @Override
    public void deleteRows(String dataIRI, T lowerBound, T upperBound) {
        try (Connection conn = getConnection()) {
            deleteRows(dataIRI, lowerBound, upperBound, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error in deleteRows", e);
        }
    }

    @Override
    public void deleteTimeSeries(String dataIRI, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        int deletedRows = context.deleteFrom(getDSLTable(TS_DATA_IRI_TABLE)).where(DATA_IRI_COLUMN.eq(dataIRI))
                .execute();
        if (deletedRows == 0) {
            String errmsg = "<" + dataIRI + "> does not have an assigned time series instance";
            throw new JPSRuntimeException(errmsg);
        }
    }

    @Override
    public void deleteTimeSeries(String dataIRI) {
        try (Connection conn = getConnection()) {
            deleteTimeSeries(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while deleting time series", e);
        }
    }

    @Override
    public void deleteEntireTimeSeries(String dataIRI, Connection conn) {
        deleteTimeSeries(dataIRI, conn);
    }

    @Override
    public void deleteEntireTimeSeries(String dataIRI) {
        deleteTimeSeries(dataIRI);
    }

    @Override
    public void deleteAll(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        context.dropTableIfExists(getDSLTable(TS_DATA_TABLE)).execute();
        context.dropTableIfExists(getDSLTable(TS_DATA_IRI_TABLE)).execute();
        context.dropTableIfExists(getDSLTable(TS_DATA_TYPE_TABLE)).execute();
    }

    @Override
    public void deleteAll() {
        try (Connection conn = getConnection()) {
            deleteAll(conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error in deleteAll", e);
        }
    }

    @Override
    public TimeSeries<T> getTimeSeriesWithinBounds(List<String> dataIriList, T lowerBound, T upperBound,
            Connection conn) {
        if (dataIriList.size() != 1) {
            String errmsg = "Only one IRI can be provided for this class";
            throw new JPSRuntimeException(errmsg);
        }
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(dataIriList, conn);

        String dataColumn = dataColumnMetadata.getColumns().iterator().next(); // there should only be one value
        Field<Object> dataField = DSL.field(DSL.name(dataColumn));

        Condition condition = DATA_IRI_INDEX_COLUMN.in(dataColumnMetadata.getAllIndex());

        if (lowerBound != null) {
            condition = condition.and(timeColumn.ge(lowerBound));
        }

        if (upperBound != null) {
            condition = condition.and(timeColumn.le(upperBound));
        }

        DSLContext context = DSL.using(conn);
        Result<Record2<Object, T>> queryResult = context.select(dataField, timeColumn).from(getDSLTable(TS_DATA_TABLE))
                .where(condition).orderBy(timeColumn.asc()).fetch();

        List<T> timeList = new ArrayList<>();
        List<Object> valueList = new ArrayList<>();
        for (Record row : queryResult) {
            timeList.add(row.get(timeColumn));
            valueList.add(row.get(dataField));
        }

        List<List<?>> valuesList = new ArrayList<>();
        valuesList.add(valueList);

        return new TimeSeries<>(timeList, Arrays.asList(dataIriList.get(0)), valuesList);
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
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(Arrays.asList(dataIRI), conn);

        String dataColumn = dataColumnMetadata.getColumns().iterator().next(); // there should only be one value
        Field<Object> dataField = DSL.field(DSL.name(dataColumn));

        DSLContext context = DSL.using(conn);
        Result<Record2<Object, T>> queryResult = context.select(dataField, timeColumn).from(getDSLTable(TS_DATA_TABLE))
                .where(DATA_IRI_INDEX_COLUMN.in(dataColumnMetadata.getAllIndex()))
                .orderBy(timeColumn.desc()).limit(1).fetch();

        List<T> timeList = new ArrayList<>();
        List<Object> valueList = new ArrayList<>();
        for (Record row : queryResult) {
            timeList.add(row.get(timeColumn));
            valueList.add(row.get(dataField));
        }
        List<List<?>> valuesList = Arrays.asList(valueList);
        return new TimeSeries<>(timeList, Arrays.asList(dataIRI), valuesList);
    }

    @Override
    public TimeSeries<T> getOldestData(String dataIRI, Connection conn) {
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(Arrays.asList(dataIRI), conn);

        String dataColumn = dataColumnMetadata.getColumns().iterator().next(); // there should only be one value
        Field<Object> dataField = DSL.field(DSL.name(dataColumn));

        DSLContext context = DSL.using(conn, DIALECT);
        Result<Record2<Object, T>> queryResult = context.select(dataField, timeColumn).from(getDSLTable(TS_DATA_TABLE))
                .where(DATA_IRI_INDEX_COLUMN.in(dataColumnMetadata.getAllIndex()))
                .orderBy(timeColumn.asc()).limit(1).fetch();

        List<T> timeList = new ArrayList<>();
        List<Object> valueList = new ArrayList<>();
        for (Record row : queryResult) {
            timeList.add(row.get(timeColumn));
            valueList.add(row.get(dataField));
        }
        List<List<?>> valuesList = Arrays.asList(valueList);
        return new TimeSeries<>(timeList, Arrays.asList(dataIRI), valuesList);
    }

    @Override
    public double getAverage(String dataIRI, Connection conn) {
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(Arrays.asList(dataIRI), conn);

        String dataColumn = dataColumnMetadata.getColumns().iterator().next(); // there should only be one value
        int dataIriIndex = dataColumnMetadata.getIndex(dataIRI);
        Field<Double> dataField = DSL.field(DSL.name(dataColumn), Double.class);

        DSLContext context = DSL.using(conn, DIALECT);
        return context.select(DSL.avg(dataField)).from(getDSLTable(TS_DATA_TABLE))
                .where(DATA_IRI_INDEX_COLUMN.eq(dataIriIndex)).fetchOneInto(Double.class);
    }

    @Override
    public double getMaxValue(String dataIRI, Connection conn) {
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(Arrays.asList(dataIRI), conn);

        String dataColumn = dataColumnMetadata.getColumns().iterator().next(); // there should only be one value
        int dataIriIndex = dataColumnMetadata.getIndex(dataIRI);
        Field<Double> dataField = DSL.field(DSL.name(dataColumn), Double.class);

        DSLContext context = DSL.using(conn, DIALECT);
        return context.select(DSL.max(dataField)).from(getDSLTable(TS_DATA_TABLE))
                .where(DATA_IRI_INDEX_COLUMN.eq(dataIriIndex)).fetchOneInto(Double.class);
    }

    @Override
    public double getMinValue(String dataIRI, Connection conn) {
        DataColumnMetadata dataColumnMetadata = getDataColumnMetadata(Arrays.asList(dataIRI), conn);

        String dataColumn = dataColumnMetadata.getColumns().iterator().next(); // there should only be one value
        int dataIriIndex = dataColumnMetadata.getIndex(dataIRI);
        Field<Double> dataField = DSL.field(DSL.name(dataColumn), Double.class);

        DSLContext context = DSL.using(conn, DIALECT);
        return context.select(DSL.min(dataField)).from(getDSLTable(TS_DATA_TABLE))
                .where(DATA_IRI_INDEX_COLUMN.eq(dataIriIndex)).fetchOneInto(Double.class);
    }

    @Override
    public T getMaxTime(String dataIRI, Connection conn) {
        DSLContext context = DSL.using(conn);

        Field<Integer> aliasedField1 = DSL.field(DSL.name("a", DATA_IRI_INDEX), SQLDataType.INTEGER.identity(true));
        Field<Integer> aliasedField2 = DSL.field(DSL.name("b", DATA_IRI_INDEX), SQLDataType.INTEGER.identity(true));

        return context.select(timeColumn)
                .from(getDSLTable(TS_DATA_TABLE).as("a"))
                .leftJoin(getDSLTable(TS_DATA_IRI_TABLE).as("b")).on(aliasedField1.eq(aliasedField2))
                .where(DATA_IRI_COLUMN.eq(dataIRI)).orderBy(timeColumn.desc()).limit(1).fetch(timeColumn).get(0);
    }

    @Override
    public T getMinTime(String dataIRI, Connection conn) {
        DSLContext context = DSL.using(conn);

        Field<Integer> aliasedField1 = DSL.field(DSL.name("a", DATA_IRI_INDEX), SQLDataType.INTEGER.identity(true));
        Field<Integer> aliasedField2 = DSL.field(DSL.name("b", DATA_IRI_INDEX), SQLDataType.INTEGER.identity(true));

        return context.select(timeColumn)
                .from(getDSLTable(TS_DATA_TABLE).as("a"))
                .leftJoin(getDSLTable(TS_DATA_IRI_TABLE).as("b")).on(aliasedField1.eq(aliasedField2))
                .where(DATA_IRI_COLUMN.eq(dataIRI)).orderBy(timeColumn.asc()).limit(1).fetch(timeColumn).get(0);
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
        try (Connection conn = getConnection()) {
            return getTimeSeriesWithinBounds(dataIRI, lowerBound, upperBound, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while executing getTimeSeriesWithinBounds", e);
        }
    }

    @Override
    public TimeSeries<T> getTimeSeries(List<String> dataIRI) {
        try (Connection conn = getConnection()) {
            return getTimeSeries(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while executing getTimeSeries", e);
        }
    }

    @Override
    public TimeSeries<T> getLatestData(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getLatestData(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while executing getLatestData", e);
        }
    }

    @Override
    public TimeSeries<T> getOldestData(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getOldestData(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while executing getOldestData", e);
        }
    }

    @Override
    public double getAverage(String dataIRI) {
        throw new UnsupportedOperationException("Unimplemented method 'getAverage'");
    }

    @Override
    public double getMaxValue(String dataIRI) {
        throw new UnsupportedOperationException("Unimplemented method 'getMaxValue'");
    }

    @Override
    public double getMinValue(String dataIRI) {
        throw new UnsupportedOperationException("Unimplemented method 'getMinValue'");
    }

    @Override
    public T getMaxTime(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getMaxTime(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while executing getMaxTime", e);
        }
    }

    @Override
    public T getMinTime(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getMinTime(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while executing getMinTime", e);
        }
    }

    @Override
    public boolean checkDataHasTimeSeries(String dataIRI, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        return context.fetchExists(DSL.selectFrom(getDSLTable(TS_DATA_IRI_TABLE).where(DATA_IRI_COLUMN.eq(dataIRI))));
    }

    @Override
    public boolean checkDataHasTimeSeries(String dataIRI) {
        try (Connection conn = getConnection()) {
            return checkDataHasTimeSeries(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException("Error while executing checkDataHasTimeSeries", e);
        }
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
        initTimeSeriesTable(dataIRIs, dataClasses, tsIri, srid, conn);
    }

    @Override
    public boolean timeSeriesExists(String tsIRI, Connection conn) {
        throw new UnsupportedOperationException("'timeSeriesExists' is not applicable to this class");
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

        List<Class<?>> preconfiguredClasses = new ArrayList<>();
        preconfiguredClasses.addAll(PRECONFIGURED_DATA_CLASSES);
        preconfiguredClasses.addAll(PRECONFIGURED_GEOMETRY_CLASSES);

        for (Class<?> clas : preconfiguredClasses) {
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
        context.createTableIfNotExists(getDSLTable(TS_DATA_IRI_TABLE))
                .column(DATA_IRI_COLUMN).column(DATA_IRI_INDEX_COLUMN_SERIAL).column(DATA_TYPE_INDEX_COLUMN)
                .constraints(
                        DSL.unique(DATA_IRI_COLUMN),
                        DSL.primaryKey(DATA_IRI_INDEX_COLUMN_SERIAL),
                        DSL.foreignKey(DATA_TYPE_INDEX_COLUMN)
                                .references(getDSLTable(TS_DATA_TYPE_TABLE), DATA_TYPE_INDEX_COLUMN)
                                .onDeleteCascade())
                .execute();

        context.createIndexIfNotExists("ts_data_iri_table_data_type_idx")
                .on(getDSLTable(TS_DATA_IRI_TABLE), DATA_TYPE_INDEX_COLUMN).execute();
    }

    private void initDataTableIfNotExists(Connection conn) {

        // add data columns
        List<Field<?>> columns = new ArrayList<>(
                Arrays.asList(DATA_INDEX_COLUMN, timeColumn, theOtherTimeColumn, DATA_IRI_INDEX_COLUMN, UNIT_COLUMN));
        for (Class<?> clas : PRECONFIGURED_DATA_CLASSES) {
            columns.add(DSL.field(DSL.name(getColumnName(clas, PRECONFIGURED_SRID)), clas));
        }

        // row id used for ontop
        DSLContext context = DSL.using(conn, DIALECT);

        context.createTableIfNotExists(getDSLTable(TS_DATA_TABLE))
                .columns(columns).constraints(
                        DSL.primaryKey(DATA_INDEX_COLUMN),
                        DSL.foreignKey(DATA_IRI_INDEX_COLUMN)
                                .references(getDSLTable(TS_DATA_IRI_TABLE), DATA_IRI_INDEX_COLUMN)
                                .onDeleteCascade(),
                        DSL.unique(DATA_IRI_INDEX_COLUMN, timeColumn),
                        DSL.unique(DATA_IRI_INDEX_COLUMN, theOtherTimeColumn))
                .execute();

        // geometry columns need to be added separately due to jooq's restrictions
        List<String> geometryColumns = PRECONFIGURED_GEOMETRY_CLASSES.stream()
                .map(c -> getColumnName(c, PRECONFIGURED_SRID)).collect(Collectors.toList());
        addGeometryColumns(geometryColumns, conn);
    }

    /**
     * Checks information_schema table to see whether column exists, if not, add the
     * new columns required
     * 
     * @param classes
     * @param conn
     * @throws SQLException
     */
    private void initDataTypesIfNotExist(List<Class<?>> classes, Integer srid, Connection conn) {
        Set<Class<?>> classSet = new HashSet<Class<?>>(classes);
        // check if there is any class that is outside of the preconfigured classes
        List<Class<?>> classesToInit = new ArrayList<>();
        classSet.forEach(c -> {
            if ((Geometry.class.isAssignableFrom(c) && !srid.equals(PRECONFIGURED_SRID))
                    || !PRECONFIGURED_DATA_CLASSES.contains(c)) {
                classesToInit.add(c);
            }
        });

        if (classesToInit.isEmpty()) {
            return;
        }

        LOGGER.warn("Initialising additional data types that will not be in the Ontop mapping");

        if (classesToInit.contains(Point.class) && srid == null) {
            LOGGER.warn(
                    "Point class is provided and SRID is null, perhaps you forgot to specify the SRID as 4326? It is not going to be included in the Ontop mapping");
        }

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

    private void addGeometryColumns(List<String> geometryColumns, Connection conn) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("ALTER TABLE %s ", getDSLTable(TS_DATA_TABLE).toString()));

        for (int i = 0; i < geometryColumns.size(); i++) {
            // type and column name are the same
            sb.append(String.format("ADD COLUMN IF NOT EXISTS \"%s\" %s", geometryColumns.get(i),
                    geometryColumns.get(i)));

            if (i != geometryColumns.size() - 1) {
                sb.append(", ");
                sb.append(System.lineSeparator());
            } else {
                sb.append(";");
                sb.append(System.lineSeparator());
            }
        }

        for (int i = 0; i < geometryColumns.size(); i++) {
            String indexName = geometryColumns.get(i).replace("(", "").replace(")", "").replace(",", "").toLowerCase();
            sb.append(String.format("CREATE INDEX IF NOT EXISTS %s on %s USING GIST(\"%s\");", indexName,
                    getDSLTable(TS_DATA_TABLE).toString(), geometryColumns.get(i)));
            sb.append(System.lineSeparator());
        }

        String sql = sb.toString();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            String errmsg = "Error adding geometry columns";
            throw new JPSRuntimeException(errmsg, e);
        }
    }

    private void updateDataIriTable(List<String> dataIriList, List<Class<?>> classes, Integer srid, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        // first query the data type index from the data type table
        List<String> columnNames = classes.stream().map(c -> getColumnName(c, srid)).collect(Collectors.toList());

        Result<Record2<String, Integer>> queryResult = context.select(DATA_TYPE_COLUMN, DATA_TYPE_INDEX_COLUMN)
                .from(getDSLTable(TS_DATA_TYPE_TABLE)).where(DATA_TYPE_COLUMN.in(columnNames)).fetch();

        Map<String, Integer> dataTypeToIndexMap = new HashMap<>();
        for (Record2<String, Integer> row : queryResult) {
            dataTypeToIndexMap.put(row.get(DATA_TYPE_COLUMN), row.get(DATA_TYPE_INDEX_COLUMN));
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
