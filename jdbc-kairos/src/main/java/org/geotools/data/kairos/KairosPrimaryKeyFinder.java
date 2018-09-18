package org.geotools.data.kairos;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.jdbc.AutoGeneratedPrimaryKeyColumn;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.jdbc.NonIncrementingPrimaryKeyColumn;
import org.geotools.jdbc.PrimaryKey;
import org.geotools.jdbc.PrimaryKeyColumn;
import org.geotools.jdbc.PrimaryKeyFinder;
import org.geotools.jdbc.SequencedPrimaryKeyColumn;
import org.geotools.util.logging.Logging;

public class KairosPrimaryKeyFinder extends PrimaryKeyFinder {
    protected static final Logger LOGGER = Logging.getLogger(KairosPrimaryKeyFinder.class);

    @Override
    public PrimaryKey getPrimaryKey(JDBCDataStore store, String databaseSchema, String tableName,
            Connection cx) throws SQLException {
        DatabaseMetaData metaData = cx.getMetaData();
        Statement st = null;
        ResultSet rs = null;
        try {
            st = cx.createStatement();

            String sql = "SELECT fldname FROM sysindex WHERE ";
            sql += " tblname = '" + tableName + "'";
            sql += " AND tblowner = '" + databaseSchema + "'";
            sql += " AND idxname like '_cst_pk%'";
            sql += " AND idxunique = 1";

            rs = st.executeQuery(sql);

            return createPrimaryKey(store, rs, metaData, databaseSchema, tableName, cx);
        } finally {
            store.closeSafe(rs);
            store.closeSafe(st);
        }
    }

    PrimaryKey createPrimaryKey(JDBCDataStore store, ResultSet index, DatabaseMetaData metaData,
            String databaseSchema, String tableName, Connection cx) throws SQLException {
        ArrayList<PrimaryKeyColumn> cols = new ArrayList<PrimaryKeyColumn>();

        while (index.next()) {
            String columnName = index.getString(1);
            if (columnName == null) {
                continue;
            }

            // look up the type ( should only be one row )
            ResultSet columns = metaData.getColumns(null, databaseSchema, tableName, columnName);
            columns.next();

            Class<?> columnType = store.getSQLDialect().getMapping(columns, cx);
            if (columnType == null) {
                int binding = columns.getInt("DATA_TYPE");
                columnType = store.getMapping(binding);
                if (columnType == null) {
                    LOGGER.warning("No class for sql type " + binding);
                    columnType = Object.class;
                }
            }

            // determine which type of primary key we have
            PrimaryKeyColumn col = null;

            // 1. Auto Incrementing?
            Statement st = cx.createStatement();
            try {
                // not actually going to get data
                st.setFetchSize(1);

                StringBuffer sql = new StringBuffer();
                sql.append("SELECT ");
                store.getSQLDialect().encodeColumnName(null, columnName, sql);
                sql.append(" FROM ");
                store.getSQLDialect().encodeTableName(databaseSchema, sql);
                sql.append(".");
                store.getSQLDialect().encodeTableName(tableName, sql);

                sql.append(" WHERE 0=1");

                LOGGER.log(Level.FINE, "Grabbing table pk metadata: {0}", sql);

                ResultSet rs = st.executeQuery(sql.toString());

                try {
                    if (rs.getMetaData().isAutoIncrement(1)) {
                        col = new AutoGeneratedPrimaryKeyColumn(columnName, columnType);
                    }
                } finally {
                    store.closeSafe(rs);
                }
            } finally {
                store.closeSafe(st);
            }

            // 2. Has a sequence?
            if (col == null) {
                try {
                    String sequenceName = store.getSQLDialect().getSequenceForColumn(
                            databaseSchema, tableName, columnName, cx);
                    if (sequenceName != null) {
                        col = new SequencedPrimaryKeyColumn(columnName, columnType, sequenceName);
                    }
                } catch (Exception e) {
                    // log the exception , and continue on
                    LOGGER.log(Level.WARNING, "Error occured determining sequence for "
                            + columnName + ", " + tableName, e);
                }
            }

            if (col == null) {
                col = new NonIncrementingPrimaryKeyColumn(columnName, columnType);
            }

            cols.add(col);
        }

        if (!cols.isEmpty()) {
            return new PrimaryKey(tableName, cols);
        }

        return null;
    }

}