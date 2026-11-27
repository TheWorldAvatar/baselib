package uk.ac.cam.cares.jps.base.timeseries;

import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Table;

public final class TestDatabaseUtils {
    private TestDatabaseUtils() {
    }

    public static List<Table<?>> getApplicationTables(DSLContext context) {
        return context.meta().getTables().stream()
                .filter(table -> table.getSchema() != null)
                .filter(table -> {
                    String schema = table.getSchema().getName().toLowerCase();
                    return !schema.equals("information_schema") && !schema.equals("pg_catalog")
                            && !schema.startsWith("pg_toast") && !schema.startsWith("pg_temp");
                })
                .toList();
    }
}
