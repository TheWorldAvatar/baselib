package uk.ac.cam.cares.jps.base.timeseries;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.Condition;
import org.jooq.CreateSchemaFinalStep;
import org.jooq.CreateTableColumnStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStep4;
import org.jooq.InsertValuesStepN;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultDataType;
import org.postgis.Geometry;

import static org.jooq.impl.DSL.*;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;

/**
 * This class is an alternative to the TimeSeriesRDBClient which creates a new
 * table for each time series. This class will only create a new table if the
 * given time class does not exist already in the database. For applications
 * with vastly different data types, there may be lots of null values due to the
 * way different time series share the same table
 * 
 * @author Kok Foong Lee
 *
 */

public class TimeSeriesRDBClientWithReducedTables<T> implements TimeSeriesRDBClientInterface<T> {
    /**
     * Logger for error output.
     */
    private static final Logger LOGGER = LogManager.getLogger(TimeSeriesRDBClientWithReducedTables.class);
    // URL and credentials for the relational database
    private String rdbURL = null;
    private String rdbUser = null;
    private String rdbPassword = null;
    private String schema = null;
    // Time series column field (for RDB)
    private final Field<T> timeColumn;

    // Central database table

    private static final String INFO_SCHEMA = "information_schema";
    private static final String PREFIX_COLUMN = "column";

    private static final String DB_TABLE_NAME = "time_series_quantities";

    private static final String TS_IRI_COLUMN_STRING = "time_series_iri";
    private static final String TIME_COLUMN = "time";

    private static final Field<String> DATA_IRI_COLUMN = DSL.field(DSL.name("data_iri"), String.class);
    private static final Field<String> TS_IRI_COLUMN = DSL.field(DSL.name(TS_IRI_COLUMN_STRING), String.class);
    private static final Field<String> COLUMNNAME_COLUMN = DSL.field(DSL.name("column_name"), String.class);
    private static final Field<String> TABLENAME_COLUMN = DSL.field(DSL.name("table_name"), String.class);
    private static final Field<String> TABLE_SCHEMA_COLUMN = DSL.field(DSL.name("table_schema"), String.class);
    private static final Table<Record> COLUMNS_TABLE = DSL.table(DSL.name(INFO_SCHEMA, "columns"));

    // Exception prefix
    private final String exceptionPrefix = this.getClass().getSimpleName() + ": ";
    // Error message
    private static final String CONNECTION_ERROR = "Failed to connect to database";
    private static final String SQL_ERROR = "Error while executing SQL command";
    private static final String NO_TS_INST_ERROR = "%s<%s> does not have an assigned time series instance";

    private Class<T> timeClass;

    // Allowed aggregation function
    protected enum AggregateFunction {
        AVERAGE,
        MAX,
        MIN
    }

    /**
     * Standard constructor
     * 
     * @param timeClass class of the timestamps of the time series
     */
    public TimeSeriesRDBClientWithReducedTables(Class<T> timeClass) {
        timeColumn = DSL.field(DSL.name(TIME_COLUMN), timeClass);
        this.timeClass = timeClass;
    }

    @Override
    public Class<T> getTimeClass() {
        return timeClass;
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

    /**
     * Initialise a time series and add respective entries to
     * central lookup table. Time series for a specific time class will share the
     * same table
     * <p>
     * For the list of supported classes, refer org.jooq.impl.SQLDataType
     * <p>
     * The timeseries IRI needs to be provided. A unique uuid for the corresponding
     * table will be generated.
     * 
     * @param dataIRI   list of IRIs for the data provided as string
     * @param dataClass list with the corresponding Java class (typical String,
     *                  double or int) for each data IRI
     * @param tsIRI     IRI of the timeseries provided as string
     * @param conn      connection to the RDB
     */
    @Override
    public void initTimeSeriesTable(List<String> dataIRI, List<Class<?>> dataClass, String tsIRI,
            Connection conn) {
        initTimeSeriesTable(dataIRI, dataClass, tsIRI, null, conn);
    }

    /**
     * similar to above, but specifically to use with PostGIS
     * the additional argument srid is to restrict any geometry columns to the srid
     * if not needed, set to null, or use above the above method
     * 
     * @param dataIRI
     * @param dataClass
     * @param tsIRI
     * @param srid
     * @param conn      connection to the RDB
     * @return
     */
    @Override
    public void initTimeSeriesTable(List<String> dataIRIList, List<Class<?>> dataClass, String tsIRI, Integer srid,
            Connection conn) {

        // All database interactions in try-block to ensure closure of connection
        try {

            // initialise schema and central table
            if (schema != null) {
                initSchemaIfNotExists(conn);
            }
            if (!checkCentralTableExists(conn)) {
                initCentralTable(conn);
            }

            // Check if any data has already been initialised (i.e. is associated with
            // different tsIRI)
            String faultyDataIRI = checkAnyDataHasTimeSeries(dataIRIList, conn);
            if (faultyDataIRI != null) {
                throw new JPSRuntimeException(
                        exceptionPrefix + "<" + faultyDataIRI + "> already has an assigned time series instance");
            }

            // Ensure that there is a class for each data IRI
            if (dataIRIList.size() != dataClass.size()) {
                throw new JPSRuntimeException(
                        exceptionPrefix + "Length of dataClass is different from number of data IRIs");
            }

            // Assign column name for each dataIRI; name for time column is fixed
            Map<String, String> dataColumnNames = new HashMap<>();

            String tsTableName = getTableWithMatchingTimeColumn(conn);
            if (tsTableName != null) {
                TimeSeriesDatabaseMetadata timeSeriesDatabaseMetadata = getTimeSeriesDatabaseMetadata(conn,
                        tsTableName);
                List<Boolean> hasMatchingColumn = timeSeriesDatabaseMetadata.hasMatchingColumn(dataClass, srid);

                for (int i = 0; i < hasMatchingColumn.size(); i++) {
                    if (Boolean.TRUE.equals(hasMatchingColumn.get(i))) {
                        // use existing column
                        String existingSuitableColumn = timeSeriesDatabaseMetadata
                                .getExistingSuitableColumn(dataClass.get(i), srid);
                        dataColumnNames.put(dataIRIList.get(i), existingSuitableColumn);
                    } else {
                        // add new columns
                        int columnIndex = getNumberOfDataColumns(tsTableName, conn) + 1;
                        String columnName = PREFIX_COLUMN + columnIndex;
                        addColumn(tsTableName, dataClass.get(i), columnName, srid, conn);
                        dataColumnNames.put(dataIRIList.get(i), columnName);
                    }
                }
            } else {
                tsTableName = "timeseries_" + UUID.randomUUID().toString().replace("-", "_");
                int i = 1;
                for (String dataIri : dataIRIList) {
                    dataColumnNames.put(dataIri, PREFIX_COLUMN + i);
                    i += 1;
                }
                createEmptyTimeSeriesTable(tsTableName, dataColumnNames, dataIRIList, dataClass, srid, conn);
            }

            populateCentralTable(tsTableName, dataIRIList, dataColumnNames, tsIRI, conn);
        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * bulk version to above
     * 
     * @param dataIRIs
     * @param dataClasses
     * @param tsIRIs
     * @param srid
     * @param conn        connection to the RDB
     * @return
     */
    @Override
    public List<Integer> bulkInitTimeSeriesTable(List<List<String>> dataIRIs, List<List<Class<?>>> dataClasses,
            List<String> tsIRIs, Integer srid, Connection conn) {

        List<Integer> failedIndex = new ArrayList<>();
        // initialise schema and central table
        if (schema != null) {
            initSchemaIfNotExists(conn);
        }
        if (!checkCentralTableExists(conn)) {
            initCentralTable(conn);
        } else {
            // Check if any data has already been initialised (i.e. is associated with
            // different tsIRI)
            List<String> flatDataIRIs = dataIRIs.stream().flatMap(List::stream).collect(Collectors.toList());
            String faultyDataIRI = checkAnyDataHasTimeSeries(flatDataIRIs, conn);
            if (faultyDataIRI != null) {
                LOGGER.error("At least one data series already has an assigned time series instance");
                // Assume all data IRIs have failed at the moment
                return IntStream.range(0, dataIRIs.size()).boxed().collect(Collectors.toList());
            }
        }

        // Ensure that there is a class for each data IRI
        for (int i = 0; i < dataIRIs.size(); i++) {
            if (dataIRIs.get(i).size() != dataClasses.get(i).size()) {
                LOGGER.error("Length of dataClass is different from number of data IRIs");
                // Assume all data IRIs have failed at the moment
                return IntStream.range(0, dataIRIs.size()).boxed().collect(Collectors.toList());
            }
        }

        String tsTableName = getTableWithMatchingTimeColumn(conn);
        Boolean tsTableInitialised = (tsTableName != null);
        if (Boolean.FALSE.equals(tsTableInitialised)) {
            tsTableName = "timeseries_" + UUID.randomUUID().toString().replace("-", "_");
        }
        List<String> tsTableNames = Collections.nCopies(dataIRIs.size(), tsTableName);

        List<Map<String, String>> dataColumnNamesMaps = new ArrayList<>();

        TimeSeriesDatabaseMetadata timeSeriesDatabaseMetadata = getTimeSeriesDatabaseMetadata(conn, tsTableName);

        for (int i = 0; i < dataIRIs.size(); i++) {
            try {

                List<String> dataIRIList = dataIRIs.get(i);
                List<Class<?>> dataClass = dataClasses.get(i);
                // Assign column name for each dataIRI; name for time column is fixed
                Map<String, String> dataColumnNames = new HashMap<>();

                if (Boolean.TRUE.equals(tsTableInitialised)) {

                    List<Boolean> hasMatchingColumn = timeSeriesDatabaseMetadata.hasMatchingColumn(dataClass, srid);

                    for (int j = 0; j < hasMatchingColumn.size(); j++) {
                        if (Boolean.TRUE.equals(hasMatchingColumn.get(j))) {
                            // use existing column
                            String existingSuitableColumn = timeSeriesDatabaseMetadata
                                    .getExistingSuitableColumn(dataClass.get(j), srid);
                            dataColumnNames.put(dataIRIList.get(j), existingSuitableColumn);
                        } else {
                            // add new columns
                            int columnIndex = getNumberOfDataColumns(tsTableName, conn) + 1;
                            String columnName = PREFIX_COLUMN + columnIndex;
                            addColumn(tsTableName, dataClass.get(j), columnName, srid, conn);
                            dataColumnNames.put(dataIRIList.get(j), columnName);
                        }
                    }

                    // if not all dataClass has a matching column, a new column is added. metadata
                    // will need to be updated
                    if (!hasMatchingColumn.stream().allMatch(Boolean::booleanValue)) {
                        timeSeriesDatabaseMetadata = getTimeSeriesDatabaseMetadata(conn, tsTableName);
                    }

                } else {
                    int j = 1;
                    for (String dataIri : dataIRIList) {
                        dataColumnNames.put(dataIri, PREFIX_COLUMN + j);
                        j += 1;
                    }
                    createEmptyTimeSeriesTable(tsTableName, dataColumnNames, dataIRIList, dataClass, srid, conn);
                    tsTableInitialised = true;
                    // get metadata for the first time
                    timeSeriesDatabaseMetadata = getTimeSeriesDatabaseMetadata(conn, tsTableName);
                }
                dataColumnNamesMaps.add(dataColumnNames);

            } catch (JPSRuntimeException eRdbCreate) {
                LOGGER.error("Failed to create Timeseries for data IRI '{}'.", dataIRIs.get(i), eRdbCreate);
                failedIndex.add(i);
            } catch (Exception e) {
                // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
                // database) as JPSRuntimeException with respective message
                LOGGER.error(e.getMessage());
                throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
            }
        }

        try {
            // Add corresponding entries in central lookup table
            bulkPopulateCentralTable(tsTableNames, dataIRIs, dataColumnNamesMaps, tsIRIs, failedIndex, conn);
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Assume all data IRIs have failed at the moment
            return IntStream.range(0, dataIRIs.size()).boxed().collect(Collectors.toList());
        }

        return failedIndex;

    }

    /**
     * Append time series data
     * If certain columns within the table are not provided, they will be nulls
     * 
     * @param ts_list TimeSeries object to add
     * @param conn    connection to the RDB
     */
    @Override
    public void addTimeSeriesData(List<TimeSeries<T>> tsList, Connection conn) {

        // Check if central database lookup table exists
        if (!checkCentralTableExists(conn)) {
            throw new JPSRuntimeException(
                    exceptionPrefix + "Central RDB lookup table has not been initialised yet");
        }

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection

        try {
            Statement statement = conn.createStatement();

            List<String> listStmt = tsList.parallelStream().map(ts -> {
                List<String> dataIRI = ts.getDataIRIs();

                // Ensure that all provided dataIRIs/columns are located in the same RDB table
                // (throws Exception if not)
                checkDataIsInSameTable(dataIRI, context);

                String tsIri = getTimeSeriesIRI(dataIRI.get(0), context);
                // Assign column name for each dataIRI; name for time column is fixed
                Map<String, String> dataColumnNames = bulkGetColumnName(dataIRI, context);

                // Append time series data to time series table
                // if a row with the time value exists, that row will be updated instead of
                // creating a new row
                return populateTimeSeriesTable(ts, dataIRI, dataColumnNames, tsIri, conn);
            }).collect(Collectors.toList());

            for (String stmt : listStmt) {
                statement.addBatch(stmt);
            }

            statement.executeBatch();

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Retrieve time series within bounds from RDB (time bounds are inclusive and
     * optional)
     * <p>
     * Returns all data series from dataIRI list as one time series object (with
     * potentially multiple related data series);
     * <br>
     * Returned time series are in ascending order with respect to time (from oldest
     * to newest)
     * <br>
     * 
     * @param dataIRI    list of data IRIs provided as string
     * @param lowerBound start timestamp from which to retrieve data (null if not
     *                   applicable)
     * @param upperBound end timestamp until which to retrieve data (null if not
     *                   applicable)
     * @param conn       connection to the RDB
     */
    @Override
    public TimeSeries<T> getTimeSeriesWithinBounds(List<String> dataIRI, T lowerBound, T upperBound, Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {

            // Check if central database lookup table exists
            if (!checkCentralTableExists(conn)) {
                throw new JPSRuntimeException(
                        exceptionPrefix + "Central RDB lookup table has not been initialised yet");
            }

            // Ensure that all provided dataIRIs/columns are located in the same RDB table
            // (throws Exception if not)
            checkDataIsInSameTable(dataIRI, context);

            // Retrieve table corresponding to the time series connected to the data IRIs
            String tsIri = getTimeSeriesIRI(dataIRI.get(0), context);
            String tsTableName = getTimeSeriesTableName(dataIRI.get(0), context);

            // Create map between data IRI and the corresponding column field in the table
            Map<String, Field<Object>> dataColumnFields = new HashMap<>();
            for (String data : dataIRI) {
                String columnName = getColumnName(data, context);
                Field<Object> field = DSL.field(DSL.name(columnName));
                dataColumnFields.put(data, field);
            }

            // Retrieve list of column fields (including fixed time column)
            List<Field<?>> columnList = new ArrayList<>();
            columnList.add(timeColumn);
            for (String data : dataIRI) {
                columnList.add(dataColumnFields.get(data));
            }

            // Potentially update bounds (if no bounds were provided)
            if (lowerBound == null) {
                lowerBound = context.select(min(timeColumn)).from(getDSLTable(tsTableName)).fetch(min(timeColumn))
                        .get(0);
            }
            if (upperBound == null) {
                upperBound = context.select(max(timeColumn)).from(getDSLTable(tsTableName)).fetch(max(timeColumn))
                        .get(0);
            }

            // Perform query
            Result<? extends Record> queryResult = context.select(columnList).from(getDSLTable(tsTableName))
                    .where(timeColumn.between(lowerBound, upperBound).and(TS_IRI_COLUMN.eq(tsIri)))
                    .orderBy(timeColumn.asc()).fetch();

            // Collect results and return a TimeSeries object
            List<T> timeValues = queryResult.getValues(timeColumn);
            List<List<?>> dataValues = new ArrayList<>();
            for (String data : dataIRI) {
                List<?> column = queryResult.getValues(dataColumnFields.get(data));
                dataValues.add(column);
            }

            return new TimeSeries<>(timeValues, dataIRI, dataValues);

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Retrieve entire time series from RDB
     * 
     * @param dataIRI list of data IRIs provided as string
     * @param conn    connection to the RDB
     */
    @Override
    public TimeSeries<T> getTimeSeries(List<String> dataIRI, Connection conn) {
        return getTimeSeriesWithinBounds(dataIRI, null, null, conn);
    }

    /**
     * returns a TimeSeries object with the latest value of the given IRI
     * 
     * @param dataIRI
     * @param conn    connection to the RDB
     */
    @Override
    public TimeSeries<T> getLatestData(String dataIRI, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        try {
            String columnName = getColumnName(dataIRI, context);
            String tsIri = getTimeSeriesIRI(dataIRI, context);
            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            Field<Object> dataField = DSL.field(DSL.name(columnName));

            Result<? extends Record> queryResult = context.select(timeColumn, dataField)
                    .from(getDSLTable(tsTableName)).where(dataField.isNotNull().and(TS_IRI_COLUMN.eq(tsIri)))
                    .orderBy(timeColumn.desc()).limit(1).fetch();

            List<T> timeValues = queryResult.getValues(timeColumn);
            List<?> dataValues = queryResult.getValues(dataField);

            return new TimeSeries<>(timeValues, Arrays.asList(dataIRI), Arrays.asList(dataValues));
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }
    }

    /**
     * returns a TimeSeries object with the oldest value of the given IRI
     * 
     * @param dataIRI
     * @param conn    connection to the RDB
     */
    @Override
    public TimeSeries<T> getOldestData(String dataIRI, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        try {
            String columnName = getColumnName(dataIRI, context);
            String tsIri = getTimeSeriesIRI(dataIRI, context);
            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            Field<Object> dataField = DSL.field(DSL.name(columnName));

            Result<? extends Record> queryResult = context.select(timeColumn, dataField)
                    .from(getDSLTable(tsTableName)).where(dataField.isNotNull().and(TS_IRI_COLUMN.eq(tsIri)))
                    .orderBy(timeColumn.asc()).limit(1).fetch();

            List<T> timeValues = queryResult.getValues(timeColumn);
            List<?> dataValues = queryResult.getValues(dataField);

            return new TimeSeries<>(timeValues, Arrays.asList(dataIRI), Arrays.asList(dataValues));
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }
    }

    /**
     * Retrieve average value of a column; stored data should be in numerics
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     * @return The average of the provided data series as double
     */
    @Override
    public double getAverage(String dataIRI, Connection conn) {
        return getAggregate(dataIRI, AggregateFunction.AVERAGE, conn);
    }

    /**
     * Retrieve maximum value of a column; stored data should be in numerics
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     * @return The maximum of the provided data series as double
     */
    @Override
    public double getMaxValue(String dataIRI, Connection conn) {
        return getAggregate(dataIRI, AggregateFunction.MAX, conn);
    }

    /**
     * Retrieve minimum value of a column; stored data should be in numerics
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     * @return The minimum of the provided data series as double
     */
    @Override
    public double getMinValue(String dataIRI, Connection conn) {
        return getAggregate(dataIRI, AggregateFunction.MIN, conn);
    }

    /**
     * Retrieve latest (maximum) time entry for a given dataIRI
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     * @return The maximum (latest) timestamp of the provided data series
     */
    @Override
    public T getMaxTime(String dataIRI, Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {
            // Retrieve table corresponding to the time series connected to the data IRI
            String tsIri = getTimeSeriesIRI(dataIRI, context);
            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            List<T> queryResult = context.select(max(timeColumn)).from(getDSLTable(tsTableName))
                    .where(TS_IRI_COLUMN.eq(tsIri)).fetch(max(timeColumn));

            return queryResult.get(0);

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Retrieve earliest (minimum) time entry for a given dataIRI
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     * @return The minimum (earliest) timestamp of the provided data series
     */
    @Override
    public T getMinTime(String dataIRI, Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {
            // Retrieve table corresponding to the time series connected to the data IRI
            String tsIri = getTimeSeriesIRI(dataIRI, context);
            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            List<T> queryResult = context.select(min(timeColumn)).from(getDSLTable(tsTableName))
                    .where(TS_IRI_COLUMN.eq(tsIri)).fetch(min(timeColumn));

            return queryResult.get(0);

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Delete time series data between lower and upper Bound
     * <p>
     * Note that this will delete all columns associated with the same time series
     * (in addition to the given data IRI)
     * 
     * @param dataIRI    data IRI provided as string
     * @param lowerBound start timestamp from which to delete data
     * @param upperBound end timestamp until which to delete data
     * @param conn       connection to the RDB
     */
    @Override
    public void deleteRows(String dataIRI, T lowerBound, T upperBound, Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {
            // Retrieve RDB table for dataIRI
            String tsIri = getTimeSeriesIRI(dataIRI, context);
            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            // Delete rows between bounds (including bounds!)
            context.delete(getDSLTable(tsTableName))
                    .where(timeColumn.between(lowerBound, upperBound).and(TS_IRI_COLUMN.eq(tsIri))).execute();

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Delete individual time series (i.e. data for one dataIRI only)
     * If this data is part of a time series, only its entry in the lookup table
     * gets deleted and the remaining data is left in the time series table, it's
     * not possible to query the value via the client anyway
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     */
    @Override
    public void deleteTimeSeries(String dataIRI, Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {
            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            // Delete entry in central lookup table
            context.delete(getDSLTable(DB_TABLE_NAME)).where(DATA_IRI_COLUMN.equal(dataIRI)).execute();

            // number of remaining data IRIs associated with this time series table
            int numDataRemaining = context.select(count()).from(getDSLTable(DB_TABLE_NAME))
                    .where(TABLENAME_COLUMN.equal(tsTableName)).fetchOne(0, int.class);

            if (numDataRemaining == 0) {
                context.dropTable(getDSLTable(tsTableName)).execute();
            }

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Delete all time series information related to a dataIRI
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     */
    @Override
    public void deleteEntireTimeSeries(String dataIRI, Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {

            // Retrieve RDB table for dataIRI
            String tsIRI = getTimeSeriesIRI(dataIRI, context);

            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            // Delete entries in central lookup table
            context.delete(getDSLTable(DB_TABLE_NAME)).where(TS_IRI_COLUMN.equal(tsIRI)).execute();

            // number of time series remaining in this table
            int numDataRemaining = context.select(count()).from(getDSLTable(DB_TABLE_NAME))
                    .where(TABLENAME_COLUMN.eq(tsTableName)).fetchOne(0, int.class);

            if (numDataRemaining == 0) {
                // delete entire table
                context.dropTable(getDSLTable(tsTableName)).execute();
            } else {
                // delete only the entries
                context.delete(getDSLTable(tsTableName)).where(TS_IRI_COLUMN.eq(tsIRI)).execute();
            }

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Delete all time series RDB tables and central lookup table
     * 
     * @param conn connection to the RDB
     */
    @Override
    public void deleteAll(Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {

            // Check if central database lookup table exists
            if (checkCentralTableExists(conn)) {
                List<String> timeSeriesTables = context.selectDistinct(TABLENAME_COLUMN)
                        .from(getDSLTable(DB_TABLE_NAME))
                        .fetch(TABLENAME_COLUMN);
                context.dropTable(getDSLTable(DB_TABLE_NAME)).execute();
                timeSeriesTables.stream().forEach(table -> context.dropTable(getDSLTable(table)).execute());
            }

        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(SQL_ERROR, e);
        }

    }

    /**
     * Initialise central database lookup table
     * 
     * @param context
     *                <p>
     *                Requires existing RDB connection
     */
    private void initCentralTable(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        // Initialise central lookup table: only creates empty table if it does not
        // exist, otherwise it is left unchanged
        try (CreateTableColumnStep createStep = context.createTableIfNotExists(getDSLTable(DB_TABLE_NAME))) {
            createStep.column(DATA_IRI_COLUMN).column(TS_IRI_COLUMN).column(COLUMNNAME_COLUMN).column(TABLENAME_COLUMN)
                    .execute();
        }
        context.createIndex().on(getDSLTable(DB_TABLE_NAME),
                Arrays.asList(DATA_IRI_COLUMN, TS_IRI_COLUMN, COLUMNNAME_COLUMN, TABLENAME_COLUMN)).execute();
    }

    private boolean checkCentralTableExists(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        Table<Record> tables = DSL.table(DSL.name(INFO_SCHEMA, "tables"));

        Condition condition = TABLENAME_COLUMN.eq(DB_TABLE_NAME);
        if (schema != null) {
            condition = condition.and(TABLE_SCHEMA_COLUMN.eq(schema));
        }
        return context.select(count()).from(tables).where(condition).fetchOne(0, int.class) == 1;
    }

    /**
     * Add new entries to central RDB lookup table
     * <p>
     * Requires existing RDB connection
     * 
     * @param tsTable         name of the timeseries table provided as string
     * @param dataIRI         list of data IRIs provided as string
     * @param dataColumnNames list of column names in the tsTable corresponding to
     *                        the data IRIs
     * @param tsIRI           timeseries IRI provided as string
     * @param context
     */
    private void populateCentralTable(String tsTableName, List<String> dataIRI, Map<String, String> dataColumnNames,
            String tsIRI, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        InsertValuesStep4<Record, String, String, String, String> insertValueStep = context.insertInto(
                getDSLTable(DB_TABLE_NAME), TABLENAME_COLUMN, DATA_IRI_COLUMN, TS_IRI_COLUMN, COLUMNNAME_COLUMN);

        // Populate columns row by row
        for (String s : dataIRI) {
            insertValueStep = insertValueStep.values(tsTableName, s, tsIRI, dataColumnNames.get(s));
        }

        insertValueStep.execute();
    }

    /**
     * Add new entries to central RDB lookup table for multiple time series
     * <p>
     * Requires existing RDB connection
     * 
     * @param tsTables            name of the timeseries table provided as list of
     *                            string
     * @param dataIRIs            list of list of data IRIs provided as string
     * @param dataColumnNamesMaps list of maps of column names in the tsTable
     *                            corresponding to the data IRIs
     * @param tsIRIs              timeseries IRI provided as list of string
     * @param failedIndex         timeseries that have failed
     * @param context
     */
    private void bulkPopulateCentralTable(List<String> tsTables, List<List<String>> dataIRIs,
            List<Map<String, String>> dataColumnNamesMaps,
            List<String> tsIRIs, List<Integer> failedIndex, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        InsertValuesStep4<Record, String, String, String, String> insertValueStep = context.insertInto(
                getDSLTable(DB_TABLE_NAME), TABLENAME_COLUMN, DATA_IRI_COLUMN, TS_IRI_COLUMN, COLUMNNAME_COLUMN);

        // Populate columns row by row
        for (int i = 0; i < tsTables.size(); i++) {
            if (!failedIndex.contains(i)) {
                String tsTableName = tsTables.get(i);
                String tsIRI = tsIRIs.get(i);
                List<String> dataIRI = dataIRIs.get(i);
                Map<String, String> dataColumnNames = dataColumnNamesMaps.get(i);
                for (String s : dataIRI) {
                    insertValueStep = insertValueStep.values(tsTableName, s, tsIRI, dataColumnNames.get(s));
                }
            }
        }

        insertValueStep.execute();
    }

    /**
     * Create an empty RDB table with the given data types for the respective
     * columns
     * <p>
     * Requires existing RDB connection
     * 
     * @param tsTable         name of the timeseries table provided as string
     * @param dataColumnNames list of column names in the tsTable corresponding to
     *                        the data IRIs
     * @param dataIRI         list of data IRIs provided as string
     * @param dataClass       list with the corresponding Java class (typical
     *                        String, double or int) for each data IRI
     * @param srid
     * @param conn            connection to the RDB
     * @throws SQLException
     */
    private void createEmptyTimeSeriesTable(String tsTableName, Map<String, String> dataColumnNames,
            List<String> dataIRI, List<Class<?>> dataClass, Integer srid, Connection conn) throws SQLException {

        DSLContext context = DSL.using(conn, DIALECT);

        List<String> additionalGeomColumns = new ArrayList<>();
        List<Class<?>> classForAdditionalGeomColumns = new ArrayList<>();
        CreateTableColumnStep createStep = null;
        try {
            // Create table
            createStep = context.createTable(getDSLTable(tsTableName));

            // Create time column
            createStep = createStep.column(timeColumn).column(TS_IRI_COLUMN);

            // Create 1 column for each value
            for (int i = 0; i < dataIRI.size(); i++) {
                if (Geometry.class.isAssignableFrom(dataClass.get(i))) {
                    // these columns will be added with their respective restrictions
                    additionalGeomColumns.add(dataColumnNames.get(dataIRI.get(i)));
                    classForAdditionalGeomColumns.add(dataClass.get(i));
                } else {
                    createStep = createStep.column(dataColumnNames.get(dataIRI.get(i)),
                            DefaultDataType.getDataType(DIALECT, dataClass.get(i)));
                }
            }

            // Send consolidated request to RDB
            createStep.constraints(unique(timeColumn, TS_IRI_COLUMN)).execute();
        } finally {
            if (createStep != null) {
                createStep.close();
            }
        }

        // create index on time column and time series IRI columns for quicker searches
        context.createIndex().on(getDSLTable(tsTableName), timeColumn, TS_IRI_COLUMN).execute();

        // add remaining geometry columns with restrictions
        if (!additionalGeomColumns.isEmpty()) {
            addGeometryColumns(tsTableName, additionalGeomColumns, classForAdditionalGeomColumns, srid, conn);
        }
    }

    /**
     * workaround because open source jOOQ DataType class does not support geometry
     * datatypes properly
     * rather than creating a generic geometry column, this will restrict the column
     * to the class provided
     * e.g. Polygon, Point, Multipolygon, along with the srid, if provided
     * 
     * @throws SQLException
     */
    private void addGeometryColumns(String tsTableName, List<String> columnNames, List<Class<?>> dataTypes,
            Integer srid,
            Connection conn) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("alter table %s ", getDSLTable(tsTableName).toString()));

        for (int i = 0; i < columnNames.size(); i++) {
            sb.append(String.format("add %s geometry(%s", DSL.name(columnNames.get(i)),
                    dataTypes.get(i).getSimpleName()));

            // add srid if given
            if (srid != null) {
                sb.append(String.format(", %d)", srid));
            } else {
                sb.append(")");
            }

            if (i != columnNames.size() - 1) {
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

    /**
     * Return SQL commands for appending time series data from TimeSeries object to
     * (existing) RDB table
     * <p>
     * Requires existing RDB connection
     * 
     * @param ts              time series to write into the table
     * @param dataIRIs        list of strings of data IRIs
     * @param dataColumnNames list of column names in the tsTable corresponding to
     *                        the data in the ts
     * @param tsIri           IRI of timeseries as string
     * @param conn            connection to the RDB
     */
    private String populateTimeSeriesTable(TimeSeries<T> ts, List<String> dataIRIs,
            Map<String, String> dataColumnNames, String tsIri, Connection conn) {

        DSLContext context = DSL.using(conn, DIALECT);

        String tsTableName = getTimeSeriesTableName(dataIRIs.get(0), context);

        // Retrieve RDB table from table name
        Table<?> table = getDSLTable(tsTableName);

        List<Field<?>> columnList = new ArrayList<>();
        // Retrieve list of corresponding column names for dataIRIs
        columnList.add(timeColumn);
        for (String data : dataIRIs) {
            columnList.add(DSL.field(DSL.name(dataColumnNames.get(data))));
        }
        columnList.add(TS_IRI_COLUMN);

        // Populate columns row by row
        InsertValuesStepN<?> insertValueStep = context.insertInto(table, columnList);

        List<Object[]> listNewValues = new ArrayList<>();

        List<T> tsTime = ts.getTimes();
        List<List<?>> tsValues = new ArrayList<>();
        int numDataIRI = dataIRIs.size();

        for (int j = 0; j < numDataIRI; j++) {
            tsValues.add(ts.getValues(dataIRIs.get(j)));
        }

        for (int i = 0; i < tsTime.size(); i++) {
            Object[] newValues = new Object[numDataIRI + 2];
            newValues[0] = tsTime.get(i);
            for (int j = 0; j < numDataIRI; j++) {
                newValues[j + 1] = tsValues.get(j).get(i);
            }
            newValues[numDataIRI + 1] = tsIri;
            listNewValues.add(newValues);
        }

        for (int i = 0; i < tsTime.size(); i++) {
            // newValues is the row elements
            insertValueStep = insertValueStep.values(listNewValues.get(i));
        }

        // Manually modify the query to upsert
        // TODO: use jOOQ 3.18 or above which support upsert (requires Java 17 or above)
        StringBuilder insertStatement = new StringBuilder(insertValueStep.toString());

        insertStatement.append(System.lineSeparator())
                .append("on conflict (\"")
                .append(timeColumn.getName())
                .append("\",\"")
                .append(TS_IRI_COLUMN.getName())
                .append("\")")
                .append(System.lineSeparator())
                .append("do update")
                .append(System.lineSeparator())
                .append("set");

        for (String data : dataIRIs) {
            insertStatement.append(System.lineSeparator())
                    .append("\"")
                    .append(dataColumnNames.get(data))
                    .append("\" = EXCLUDED.\"")
                    .append(dataColumnNames.get(data))
                    .append("\",");
        }

        // Remove the last comma
        insertStatement.setLength(insertStatement.length() - 1);

        return insertStatement.toString();

    }

    /**
     * Check whether dataIRI has a tsIRI associated with it (i.e. dataIRI exists in
     * central lookup table)
     * <p>
     * Requires existing RDB connection
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     * @return True if the data IRI exists in central lookup table's dataIRI column,
     *         false otherwise
     */
    @Override
    public boolean checkDataHasTimeSeries(String dataIRI, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        if (!checkCentralTableExists(conn)) {
            return false;
        }

        // Look for the entry dataIRI in dbTable
        Table<?> table = getDSLTable(DB_TABLE_NAME);
        return context.fetchExists(selectFrom(table).where(DATA_IRI_COLUMN.eq(dataIRI)));
    }

    @Override
    public boolean checkDataHasTimeSeries(String dataIRI) {
        try (Connection conn = getConnection()) {
            return checkDataHasTimeSeries(dataIRI, conn);
        } catch (SQLException e) {
            throw new JPSRuntimeException(String.format("Error making connection to %s", rdbURL), e);
        }
    }

    /**
     * Check if all given data IRI is attached to a time series in kb
     * 
     * @param dataIRIs data IRIs provided as list of string
     * @param conn     connection to the RDB
     * @return True if any dataIRIs exist and are attached to a time series, false
     *         otherwise
     */
    @Override
    public String checkAnyDataHasTimeSeries(List<String> dataIRIs, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        Table<?> table = getDSLTable(DB_TABLE_NAME);

        List<Condition> conditions = dataIRIs.stream().map(DATA_IRI_COLUMN::eq).collect(Collectors.toList());

        Condition combinedCondition = DSL.or(conditions);

        Result<Record> result = context.select().from(table).where(combinedCondition).fetch();

        // Check if the result is non-empty and return the first element
        if (!result.isEmpty()) {
            return result.get(0).getValue(DATA_IRI_COLUMN.getName(), String.class);
        }

        return null; // If no data IRI exists
    }

    /**
     * Ensure that all dataIRIs are associated with same RDB table (i.e. have same
     * time series IRI)
     * <br>
     * Throws JPSRuntime Exception if not all dataIRIs are attached to same table in
     * the database
     * <p>
     * Requires existing RDB connection;
     * 
     * @param dataIRI list of data IRIs provided as string
     * @param context
     */
    private void checkDataIsInSameTable(List<String> dataIRI, DSLContext context) {

        Table<?> table = getDSLTable(DB_TABLE_NAME);

        List<Condition> conditions = dataIRI.stream().map(DATA_IRI_COLUMN::eq).collect(Collectors.toList());

        Condition combinedCondition = DSL.or(conditions);

        List<String> queryResult = context.select(TS_IRI_COLUMN).from(table).where(combinedCondition)
                .fetch(TS_IRI_COLUMN);

        boolean allIdentical = queryResult.stream().allMatch(s -> s.equals(queryResult.get(0)));

        if (!allIdentical) {
            throw new JPSRuntimeException(exceptionPrefix + "Provided data is not within the same RDB table");
        }
    }

    /**
     * Retrieve tsIRI for provided dataIRI from central database lookup table (if it
     * exists)
     * <p>
     * Requires existing RDB connection
     * 
     * @param dataIRI data IRI provided as string
     * @param context
     * @return The attached time series IRI as string
     */
    private String getTimeSeriesIRI(String dataIRI, DSLContext context) {
        try {
            // Look for the entry dataIRI in dbTable
            Table<?> table = getDSLTable(DB_TABLE_NAME);
            List<String> queryResult = context.select(TS_IRI_COLUMN).from(table).where(DATA_IRI_COLUMN.eq(dataIRI))
                    .fetch(TS_IRI_COLUMN);
            // Throws IndexOutOfBoundsException if dataIRI is not present in central lookup
            // table (i.e. queryResult is empty)
            return queryResult.get(0);
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(String.format(NO_TS_INST_ERROR, exceptionPrefix, dataIRI));
        }
    }

    /**
     * Retrieve column name for provided dataIRI from central database lookup table
     * (if it exists)
     * <p>
     * Requires existing RDB connection
     * 
     * @param dataIRI data IRI provided as string
     * @param context
     * @return Corresponding column name in the RDB table related to the data IRI
     */
    private String getColumnName(String dataIRI, DSLContext context) {
        try {
            // Look for the entry dataIRI in dbTable
            Table<?> table = getDSLTable(DB_TABLE_NAME);
            List<String> queryResult = context.select(COLUMNNAME_COLUMN).from(table).where(DATA_IRI_COLUMN.eq(dataIRI))
                    .fetch(COLUMNNAME_COLUMN);
            // Throws IndexOutOfBoundsException if dataIRI is not present in central lookup
            // table (i.e. queryResult is empty)
            return queryResult.get(0);
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(String.format(NO_TS_INST_ERROR, exceptionPrefix, dataIRI));
        }
    }

    /**
     * Retrieve column name for provided dataIRI from central database lookup table
     * (if it exists)
     * <p>
     * Requires existing RDB connection
     * 
     * @param dataIRI data IRI provided as string
     * @param context
     * @return Corresponding column name in the RDB table related to the data IRI
     */
    private Map<String, String> bulkGetColumnName(List<String> dataIRIs, DSLContext context) {
        Map<String, String> dataIRIColumnMap = new HashMap<>();
        try {
            // Look for the entry dataIRI in dbTable
            Table<?> table = getDSLTable(DB_TABLE_NAME);
            Result<Record2<String, String>> queryResult = context.select(COLUMNNAME_COLUMN, DATA_IRI_COLUMN)
                    .from(table).where(DATA_IRI_COLUMN.in(dataIRIs)).fetch();
            for (Record result : queryResult) {
                String dataIRI = result.getValue(DATA_IRI_COLUMN);
                String columnValue = result.getValue(COLUMNNAME_COLUMN);
                dataIRIColumnMap.put(dataIRI, columnValue);
            }
            return dataIRIColumnMap;
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(
                    exceptionPrefix + "At least 1 data IRI does not have an assigned time series instance");
        }
    }

    /**
     * Retrieve table name for provided dataIRI from central database lookup table
     * (if it exists)
     * <p>
     * Requires existing RDB connection
     * 
     * @param dataIRI data IRI provided as string
     * @param context
     * @return Corresponding table name as string
     */
    private String getTimeSeriesTableName(String dataIRI, DSLContext context) {
        try {
            List<String> queryResult = context.select(TABLENAME_COLUMN).from(getDSLTable(DB_TABLE_NAME))
                    .where(DATA_IRI_COLUMN.eq(dataIRI)).fetch(TABLENAME_COLUMN);
            // Throws IndexOutOfBoundsException if dataIRI is not present in central lookup
            // table (i.e. queryResult is empty)
            return queryResult.get(0);
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(String.format(NO_TS_INST_ERROR, exceptionPrefix, dataIRI));
        }
    }

    /**
     * Retrieve aggregate value of a column; stored data should be in numerics
     * 
     * @param dataIRI           data IRI provided as string
     * @param aggregateFunction enumerator for the wanted type of aggregation
     *                          (AVERAGE, MAX, MIN)
     * @param conn              connection to the RDB
     * @return The aggregate value of the whole time series corresponding to the
     *         dataIRI.
     */
    private double getAggregate(String dataIRI, AggregateFunction aggregateFunction, Connection conn) {

        // Initialise connection and set jOOQ DSL context
        DSLContext context = DSL.using(conn, DIALECT);

        // All database interactions in try-block to ensure closure of connection
        try {
            String tsIri = getTimeSeriesIRI(dataIRI, context);
            String tsTableName = getTimeSeriesTableName(dataIRI, context);

            // Create map between the data IRI and the corresponding column field in the
            // table
            String columnName = getColumnName(dataIRI, context);
            Field<Double> columnField = DSL.field(DSL.name(columnName), Double.class);

            switch (aggregateFunction) {
                case AVERAGE:
                    return context.select(avg(columnField)).from(getDSLTable(tsTableName))
                            .where(TS_IRI_COLUMN.eq(tsIri)).fetch(avg(columnField)).get(0).doubleValue();
                case MAX:
                    return context.select(max(columnField)).from(getDSLTable(tsTableName))
                            .where(TS_IRI_COLUMN.eq(tsIri)).fetch(max(columnField)).get(0);
                case MIN:
                    return context.select(min(columnField)).from(getDSLTable(tsTableName))
                            .where(TS_IRI_COLUMN.eq(tsIri)).fetch(min(columnField)).get(0);
                default:
                    throw new JPSRuntimeException(
                            exceptionPrefix + "Aggregate function " + aggregateFunction.name() + " not valid!");
            }

        } catch (JPSRuntimeException e) {
            // Re-throw JPSRuntimeExceptions
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            // Throw all exceptions incurred by jooq (i.e. by SQL interactions with
            // database) as JPSRuntimeException with respective message
            throw new JPSRuntimeException(exceptionPrefix + SQL_ERROR, e);
        }

    }

    /**
     * Get and set methods for private relational database properties (e.g.
     * PostgreSQL)
     */
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

    /**
     * Initialise a time series and add respective entries to
     * central lookup table. Time series for a specific time class will share the
     * same table
     * <p>
     * For the list of supported classes, refer org.jooq.impl.SQLDataType
     * <p>
     * The timeseries IRI needs to be provided. A unique uuid for the corresponding
     * table will be generated.
     * 
     * @param dataIRI   list of IRIs for the data provided as string
     * @param dataClass list with the corresponding Java class (typical String,
     *                  double or int) for each data IRI
     * @param tsIRI     IRI of the timeseries provided as string
     */
    @Override
    public void initTimeSeriesTable(List<String> dataIRI, List<Class<?>> dataClass, String tsIRI) {
        initTimeSeriesTable(dataIRI, dataClass, tsIRI, (Integer) null);
    }

    /**
     * similar to above, but specifically to use with PostGIS
     * the additional argument srid is to restrict any geometry columns to the srid
     * if not needed, set to null, or use above the above method
     * 
     * @param dataIRI
     * @param dataClass
     * @param tsIRI
     * @param srid
     * @return
     */
    @Override
    public void initTimeSeriesTable(List<String> dataIRI, List<Class<?>> dataClass, String tsIRI, Integer srid) {
        try (Connection conn = getConnection()) {
            initTimeSeriesTable(dataIRI, dataClass, tsIRI, srid, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Append time series data
     * If certain columns within the time series are not provided, they will be
     * nulls
     * 
     * @param tsList TimeSeries object to add
     */
    @Override
    public void addTimeSeriesData(List<TimeSeries<T>> tsList) {
        try (Connection conn = getConnection()) {
            addTimeSeriesData(tsList, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Retrieve time series within bounds from RDB (time bounds are inclusive and
     * optional)
     * <p>
     * Returns all data series from dataIRI list as one time series object (with
     * potentially multiple related data series);
     * <br>
     * Returned time series are in ascending order with respect to time (from oldest
     * to newest)
     * <br>
     * 
     * @param dataIRI    list of data IRIs provided as string
     * @param lowerBound start timestamp from which to retrieve data (null if not
     *                   applicable)
     * @param upperBound end timestamp until which to retrieve data (null if not
     *                   applicable)
     */
    @Override
    public TimeSeries<T> getTimeSeriesWithinBounds(List<String> dataIRI, T lowerBound, T upperBound) {
        // All database interactions in try-block to ensure closure of connection
        try (Connection conn = getConnection()) {
            return getTimeSeriesWithinBounds(dataIRI, lowerBound, upperBound, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }

    }

    /**
     * Retrieve entire time series from RDB
     * 
     * @param dataIRI list of data IRIs provided as string
     */
    @Override
    public TimeSeries<T> getTimeSeries(List<String> dataIRI) {
        return getTimeSeriesWithinBounds(dataIRI, null, null);
    }

    /**
     * returns a TimeSeries object with the latest value of the given IRI
     * 
     * @param dataIRI
     * @param conn    connection to the RDB
     */
    @Override
    public TimeSeries<T> getLatestData(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getLatestData(dataIRI, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * returns a TimeSeries object with the oldest value of the given IRI
     * 
     * @param dataIRI
     */
    @Override
    public TimeSeries<T> getOldestData(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getOldestData(dataIRI, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Retrieve average value of a column; stored data should be in numerics
     * 
     * @param dataIRI data IRI provided as string
     * @return The average of the provided data series as double
     */
    @Override
    public double getAverage(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getAggregate(dataIRI, AggregateFunction.AVERAGE, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Retrieve maximum value of a column; stored data should be in numerics
     * 
     * @param dataIRI data IRI provided as string
     * @return The maximum of the provided data series as double
     */
    @Override
    public double getMaxValue(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getAggregate(dataIRI, AggregateFunction.MAX, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Retrieve minimum value of a column; stored data should be in numerics
     * 
     * @param dataIRI data IRI provided as string
     * @return The minimum of the provided data series as double
     */
    @Override
    public double getMinValue(String dataIRI) {
        try (Connection conn = getConnection()) {
            return getAggregate(dataIRI, AggregateFunction.MIN, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Retrieve latest (maximum) time entry for a given dataIRI
     * 
     * @param dataIRI data IRI provided as string
     * @return The maximum (latest) timestamp of the provided data series
     */
    @Override
    public T getMaxTime(String dataIRI) {
        // All database interactions in try-block to ensure closure of connection
        try (Connection conn = getConnection()) {
            return getMaxTime(dataIRI, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Retrieve earliest (minimum) time entry for a given dataIRI
     * 
     * @param dataIRI data IRI provided as string
     * @return The minimum (earliest) timestamp of the provided data series
     */
    @Override
    public T getMinTime(String dataIRI) {
        // All database interactions in try-block to ensure closure of connection
        try (Connection conn = getConnection()) {
            return getMinTime(dataIRI, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Delete time series data between lower and upper Bound
     * <p>
     * Note that this will delete all data for the given bounds in addition to the
     * given data IRI
     * 
     * @param dataIRI    data IRI provided as string
     * @param lowerBound start timestamp from which to delete data
     * @param upperBound end timestamp until which to delete data
     */
    @Override
    public void deleteRows(String dataIRI, T lowerBound, T upperBound) {
        // All database interactions in try-block to ensure closure of connection
        try (Connection conn = getConnection()) {
            deleteRows(dataIRI, lowerBound, upperBound, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Delete individual time series (i.e. data for one dataIRI only)
     * 
     * @param dataIRI data IRI provided as string
     * @param conn    connection to the RDB
     */
    @Override
    public void deleteTimeSeries(String dataIRI) {
        // All database interactions in try-block to ensure closure of connection
        try (Connection conn = getConnection()) {
            deleteTimeSeries(dataIRI, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Delete all time series information related to a dataIRI (i.e. entire RDB
     * table and entries in central table)
     * 
     * @param dataIRI data IRI provided as string
     */
    @Override
    public void deleteEntireTimeSeries(String dataIRI) {
        // All database interactions in try-block to ensure closure of connection
        try (Connection conn = getConnection()) {
            deleteEntireTimeSeries(dataIRI, conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    /**
     * Delete all time series RDB tables and central lookup table
     */
    @Override
    public void deleteAll() {
        // All database interactions in try-block to ensure closure of connection
        try (Connection conn = getConnection()) {
            deleteAll(conn);
        } catch (SQLException e) {
            LOGGER.error(e.getMessage());
            throw new JPSRuntimeException(exceptionPrefix + CONNECTION_ERROR, e);
        }
    }

    @Override
    public void addColumnsToExistingTimeSeries(List<String> dataIRIs, List<Class<?>> dataClasses, String tsIri,
            Integer srid, Connection conn) {
        DSLContext context = DSL.using(conn);
        String tsTableName = getTableWithMatchingTimeColumn(conn);

        // Assign column name for each dataIRI; name for time column is fixed
        Map<String, String> dataColumnNames = new HashMap<>();

        List<String> existingColumns = context.select(COLUMNNAME_COLUMN).from(getDSLTable(DB_TABLE_NAME))
                .where(TS_IRI_COLUMN.eq(tsIri)).fetch(COLUMNNAME_COLUMN);

        if (tsTableName != null) {
            TimeSeriesDatabaseMetadata timeSeriesDatabaseMetadata = getTimeSeriesDatabaseMetadata(conn,
                    tsTableName, existingColumns);
            List<Boolean> hasMatchingColumn = timeSeriesDatabaseMetadata.hasMatchingColumn(dataClasses, srid);

            for (int i = 0; i < hasMatchingColumn.size(); i++) {
                if (Boolean.TRUE.equals(hasMatchingColumn.get(i))) {
                    // use existing column
                    String existingSuitableColumn = timeSeriesDatabaseMetadata
                            .getExistingSuitableColumn(dataClasses.get(i), srid);
                    dataColumnNames.put(dataIRIs.get(i), existingSuitableColumn);
                } else {
                    // add new columns
                    int columnIndex = getNumberOfDataColumns(tsTableName, conn) + 1;
                    String columnName = PREFIX_COLUMN + columnIndex;
                    try {
                        addColumn(tsTableName, dataClasses.get(i), columnName, srid, conn);
                    } catch (SQLException e) {
                        String errmsg = "Failed to add column for " + dataClasses.get(i).getSimpleName();
                        LOGGER.error(errmsg);
                        throw new JPSRuntimeException(errmsg);
                    }

                    dataColumnNames.put(dataIRIs.get(i), columnName);
                }
            }
        } else {
            String errmsg = "Probably the wrong time class is initialised for TimeSeriesClient";
            throw new JPSRuntimeException(exceptionPrefix + errmsg);
        }

        populateCentralTable(tsTableName, dataIRIs, dataColumnNames, tsIri, conn);
    }

    @Override
    public boolean timeSeriesExists(String tsIRI, Connection conn) {
        DSLContext context = DSL.using(conn);
        return context.fetchExists(selectFrom(getDSLTable(DB_TABLE_NAME)).where(TS_IRI_COLUMN.eq(tsIRI)));
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");
            return DriverManager.getConnection(this.rdbURL, this.rdbUser, this.rdbPassword);
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

    private Table<Record> getDSLTable(String tableName) {
        if (schema != null) {
            return DSL.table(DSL.name(schema, tableName));
        } else {
            return DSL.table(DSL.name(tableName));
        }
    }

    /**
     * returns TimeSeriesDatabaseMetadata object which contains the state of the
     * current time series database, especially on the available columns and their
     * data types
     * 
     * @param conn
     * @param tableName
     * @param columnsToExclude // used when adding new columns
     * @return
     */
    private TimeSeriesDatabaseMetadata getTimeSeriesDatabaseMetadata(Connection conn, String tableName,
            List<String> columnsToExclude) {
        TimeSeriesDatabaseMetadata timeSeriesDatabaseMetadata = new TimeSeriesDatabaseMetadata();
        addDataTypes(conn, timeSeriesDatabaseMetadata, tableName, columnsToExclude);
        addSpecificGeometryClass(conn, timeSeriesDatabaseMetadata, tableName);
        return timeSeriesDatabaseMetadata;
    }

    private TimeSeriesDatabaseMetadata getTimeSeriesDatabaseMetadata(Connection conn, String tableName) {
        return getTimeSeriesDatabaseMetadata(conn, tableName, new ArrayList<>());
    }

    /**
     * obtains data from the information_schema.columns table on the initialised
     * time series columns
     * 
     * @param conn
     * @param timeSeriesDatabaseMetadata
     * @param tableName
     */
    private void addDataTypes(Connection conn, TimeSeriesDatabaseMetadata timeSeriesDatabaseMetadata,
            String tableName, List<String> columnsToExclude) {
        DSLContext context = DSL.using(conn, DIALECT);
        Field<String> dataTypeColumn = DSL.field("data_type", String.class);
        Field<String> udtNameColumn = DSL.field("udt_name", String.class);

        Condition condition = TABLENAME_COLUMN.eq(tableName)
                .and(COLUMNNAME_COLUMN.notIn(TIME_COLUMN, TS_IRI_COLUMN_STRING));

        if (schema != null) {
            condition.and(TABLE_SCHEMA_COLUMN.eq(schema));
        }

        Result<? extends Record> queryResult = context
                .select(dataTypeColumn, udtNameColumn, COLUMNNAME_COLUMN).from(COLUMNS_TABLE)
                .where(condition).fetch();

        queryResult.stream().filter(rec -> !columnsToExclude.contains(rec.get(COLUMNNAME_COLUMN))).forEach(rec -> {
            String dataType = rec.get(dataTypeColumn);
            String udtName = rec.get(udtNameColumn);
            String columnName = rec.get(COLUMNNAME_COLUMN);
            timeSeriesDatabaseMetadata.addDataType(columnName, dataType);
            timeSeriesDatabaseMetadata.addUdtName(columnName, udtName);
        });
    }

    /**
     * 
     * @param conn
     * @param timeSeriesMetadata
     * @param tableName
     */
    private void addSpecificGeometryClass(Connection conn, TimeSeriesDatabaseMetadata timeSeriesMetadata,
            String tableName) {
        List<String> geometryColumns = timeSeriesMetadata.getGeometryColumns();
        if (!geometryColumns.isEmpty()) {
            DSLContext context = DSL.using(conn, DIALECT);
            Field<String> tableNameColumn = DSL.field("f_table_name", String.class);
            Field<String> columnNameColumn = DSL.field("f_geometry_column", String.class);
            Field<String> typeColumn = DSL.field("type", String.class);
            Field<Integer> sridColumn = DSL.field("srid", Integer.class);

            Condition condition = tableNameColumn.eq(tableName).and(columnNameColumn.in(geometryColumns));

            Result<? extends Record> queryResult = context
                    .select(sridColumn, typeColumn, columnNameColumn)
                    .from(getDSLTable("geometry_columns")).where(condition).fetch();

            queryResult.forEach(rec -> {
                String columnName = rec.get(columnNameColumn);
                String geometryName = rec.get(typeColumn);
                int srid = rec.get(sridColumn);

                timeSeriesMetadata.addGeometryType(columnName, geometryName);
                timeSeriesMetadata.addSrid(columnName, srid);
            });
        }
    }

    /**
     * returns true if there is an existing time series table with the equivalent
     * time column type
     * 
     * @param conn
     * @return
     */
    private String getTableWithMatchingTimeColumn(Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);
        Field<String> dataTypeColumn = DSL.field(DSL.name("data_type"), String.class);
        Field<String> udtNameColumn = DSL.field(DSL.name("udt_name"), String.class);

        // the table_name column is the same in the time series table and the
        // information_schema.columns table

        String choice1 = DefaultDataType.getDataType(DIALECT, timeClass).getTypeName();
        String choice2 = DefaultDataType.getDataType(DIALECT, timeClass).getSQLDataType().getName();

        Condition condition = TABLENAME_COLUMN
                .in(context.select(TABLENAME_COLUMN).from(getDSLTable(DB_TABLE_NAME)).fetch(TABLENAME_COLUMN))
                .and(COLUMNNAME_COLUMN.eq(TIME_COLUMN)).and(dataTypeColumn.eq(choice1).or(dataTypeColumn.eq(choice2))
                        .or(udtNameColumn.eq(choice1).or(udtNameColumn.eq(choice2))));

        if (schema != null) {
            condition = condition.and(TABLE_SCHEMA_COLUMN.eq(schema));
        }

        List<String> queryResult = context.select(TABLENAME_COLUMN).from(COLUMNS_TABLE).where(condition)
                .fetch(TABLENAME_COLUMN);

        if (queryResult.isEmpty()) {
            return null;
        } else {
            return queryResult.get(0);
        }
    }

    /**
     * adds a new column to a time series table for the given class
     * 
     * @param tsTableName
     * @param clas
     * @param columnName
     * @param srid
     * @param conn
     * @throws SQLException
     */
    private void addColumn(String tsTableName, Class<?> clas, String columnName, Integer srid, Connection conn)
            throws SQLException {
        if (Geometry.class.isAssignableFrom(clas)) {
            addGeometryColumns(tsTableName, Arrays.asList(columnName), Arrays.asList(clas), srid, conn);
        } else {
            DSLContext context = DSL.using(conn, DIALECT);
            context.alterTable(getDSLTable(tsTableName))
                    .add(DSL.name(columnName), DefaultDataType.getDataType(DIALECT, clas)).execute();
        }
    }

    /**
     * used to determine the next column name
     */
    private int getNumberOfDataColumns(String tsTableName, Connection conn) {
        DSLContext context = DSL.using(conn, DIALECT);

        Condition condition = TABLENAME_COLUMN.eq(tsTableName)
                .and(COLUMNNAME_COLUMN.notIn(TIME_COLUMN, TS_IRI_COLUMN_STRING));

        if (schema != null) {
            condition.and(TABLE_SCHEMA_COLUMN.eq(schema));
        }

        return context.select(count()).from(COLUMNS_TABLE).where(condition).fetchOne(0, int.class);
    }
}