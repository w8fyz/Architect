package sh.fyz.architect.migration;

import org.hibernate.Session;
import sh.fyz.architect.persistent.SessionManager;

import java.sql.*;
import java.util.*;

public class DatabaseInspector {

    private final String dialect;

    public DatabaseInspector(String dialect) {
        this.dialect = dialect;
    }

    public List<TableInfo> listTables() {
        List<TableInfo> tables = new ArrayList<>();
        try (Session session = SessionManager.get().getSession()) {
            session.doWork(connection -> {
                Set<String> validTables = getValidTableNames(connection);
                for (String tableName : validTables) {
                    int columnCount = countColumns(connection, tableName);
                    long rowCount = countRows(connection, tableName);
                    tables.add(new TableInfo(tableName, columnCount, rowCount));
                }
            });
        }
        return tables;
    }

    public TableSchema getTableSchema(String tableName) {
        TableSchema[] result = new TableSchema[1];
        try (Session session = SessionManager.get().getSession()) {
            session.doWork(connection -> {
                validateTableName(connection, tableName);
                List<ColumnInfo> columns = new ArrayList<>();
                Set<String> primaryKeys = getPrimaryKeys(connection, tableName);

                DatabaseMetaData meta = connection.getMetaData();
                try (ResultSet rs = meta.getColumns(null, getSchemaPattern(), tableName, null)) {
                    while (rs.next()) {
                        columns.add(new ColumnInfo(
                                rs.getString("COLUMN_NAME"),
                                rs.getString("TYPE_NAME"),
                                rs.getInt("COLUMN_SIZE"),
                                "YES".equals(rs.getString("IS_NULLABLE")),
                                primaryKeys.contains(rs.getString("COLUMN_NAME")),
                                rs.getString("COLUMN_DEF")
                        ));
                    }
                }

                List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
                try (ResultSet rs = meta.getImportedKeys(null, getSchemaPattern(), tableName)) {
                    while (rs.next()) {
                        foreignKeys.add(new ForeignKeyInfo(
                                rs.getString("FKCOLUMN_NAME"),
                                rs.getString("PKTABLE_NAME"),
                                rs.getString("PKCOLUMN_NAME"),
                                rs.getString("FK_NAME")
                        ));
                    }
                }

                result[0] = new TableSchema(tableName, columns, foreignKeys);
            });
        }
        return result[0];
    }

    public TableData getTableData(String tableName, int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(pageSize, 1000));

        TableData[] result = new TableData[1];
        try (Session session = SessionManager.get().getSession()) {
            session.doWork(connection -> {
                validateTableName(connection, tableName);

                long totalRows = countRows(connection, tableName);
                List<String> columnNames = new ArrayList<>();
                DatabaseMetaData meta = connection.getMetaData();
                try (ResultSet rs = meta.getColumns(null, getSchemaPattern(), tableName, null)) {
                    while (rs.next()) {
                        columnNames.add(rs.getString("COLUMN_NAME"));
                    }
                }

                List<List<String>> rows = new ArrayList<>();
                int offset = safePage * safeSize;
                String sql = "SELECT * FROM \"" + tableName + "\" LIMIT " + safeSize + " OFFSET " + offset;
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    int colCount = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= colCount; i++) {
                            Object val = rs.getObject(i);
                            row.add(val == null ? "NULL" : val.toString());
                        }
                        rows.add(row);
                    }
                }

                result[0] = new TableData(tableName, columnNames, rows, totalRows, safePage, safeSize);
            });
        }
        return result[0];
    }

    private void validateTableName(Connection connection, String tableName) throws SQLException {
        Set<String> valid = getValidTableNames(connection);
        if (!valid.contains(tableName)) {
            throw new SQLException("Table not found: " + tableName);
        }
    }

    private Set<String> getValidTableNames(Connection connection) throws SQLException {
        Set<String> tables = new TreeSet<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, getSchemaPattern(), null, new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private int countColumns(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        int count = 0;
        try (ResultSet rs = meta.getColumns(null, getSchemaPattern(), tableName, null)) {
            while (rs.next()) count++;
        }
        return count;
    }

    private long countRows(Connection connection, String tableName) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"" + tableName + "\"")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private Set<String> getPrimaryKeys(Connection connection, String tableName) throws SQLException {
        Set<String> pks = new HashSet<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getPrimaryKeys(null, getSchemaPattern(), tableName)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    private String getSchemaPattern() {
        if (dialect.toLowerCase().contains("postgresql")) {
            return "public";
        }
        return null;
    }

    public record TableInfo(String name, int columnCount, long rowCount) {}

    public record ColumnInfo(
            String name,
            String type,
            int size,
            boolean nullable,
            boolean primaryKey,
            String defaultValue
    ) {}

    public record ForeignKeyInfo(
            String columnName,
            String referencedTable,
            String referencedColumn,
            String constraintName
    ) {}

    public record TableSchema(
            String tableName,
            List<ColumnInfo> columns,
            List<ForeignKeyInfo> foreignKeys
    ) {}

    public record TableData(
            String tableName,
            List<String> columnNames,
            List<List<String>> rows,
            long totalRows,
            int page,
            int pageSize
    ) {}
}
