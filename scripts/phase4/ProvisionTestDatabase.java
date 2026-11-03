import java.sql.*;
import java.util.*;

/** Safe, non-destructive provisioner for the isolated Phase 4 PostgreSQL test database. */
public class ProvisionTestDatabase {
    private static final String TARGET = "yottaflex_phase4_test";
    private static final String TEMPLATE = "yottaflex_synthetic_repro";
    private static final List<String> TABLES = List.of("supplier", "site", "purchase_order", "po_line", "shipment",
            "receipt", "inspection", "invoice", "risk_signal", "staging_record", "exception_record", "quarantined_record",
            "score_run", "supplier_score", "score_evidence");

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("--inspect") || args[0].equals("--create-if-absent"))) {
            throw new IllegalArgumentException("Usage: ProvisionTestDatabase --inspect | --create-if-absent");
        }
        requireCredentials();
        try (Connection admin = connect("postgres")) {
            printDatabaseList(admin);
            if (exists(admin, TARGET)) {
                System.out.println("Target already exists; it will not be modified.");
                inspect(TARGET);
                return;
            }
            if (args[0].equals("--inspect")) {
                System.out.println("Target database is absent. No changes made.");
                return;
            }
            if (!exists(admin, TEMPLATE)) throw new IllegalStateException("Approved clean template database is absent.");
            verifyTemplate(admin);
            String owner = System.getenv().getOrDefault("DB_USERNAME", "postgres");
            if (!owner.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Unsafe database owner identifier.");
            try (Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + TARGET + " WITH TEMPLATE " + TEMPLATE + " OWNER " + owner);
            }
        }
        inspect(TARGET);
    }

    private static void requireCredentials() {
        if (System.getenv().getOrDefault("DB_PASSWORD", "").isBlank())
            throw new IllegalStateException("DB_PASSWORD must be set in the process environment; its value is never printed.");
    }

    private static Connection connect(String database) throws SQLException {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        String user = System.getenv().getOrDefault("DB_USERNAME", "postgres");
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + database,
                user, System.getenv("DB_PASSWORD"));
    }

    private static void printDatabaseList(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select datname from pg_database where not datistemplate order by datname")) {
            System.out.println("PostgreSQL databases:");
            while (r.next()) System.out.println("  " + r.getString(1));
        }
    }

    private static boolean exists(Connection c, String database) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select exists(select 1 from pg_database where datname=?)")) {
            s.setString(1, database);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
        }
    }

    private static void verifyTemplate(Connection admin) throws SQLException {
        try (PreparedStatement s = admin.prepareStatement("select count(*) from pg_stat_activity where datname=? and pid<>pg_backend_pid()")) {
            s.setString(1, TEMPLATE);
            try (ResultSet r = s.executeQuery()) { r.next(); if (r.getLong(1) != 0) throw new IllegalStateException("Template has active sessions; refusing clone."); }
        }
        try (Connection source = connect(TEMPLATE)) {
            if (tableCount(source, "supplier") != 25 || tableCount(source, "purchase_order") != 120 || tableCount(source, "shipment") != 180)
                throw new IllegalStateException("Template operational row counts do not match the verified clean Phase 2 repro dataset.");
            if (tableCount(source, "data_generation_run") != 1 || scalar(source, "select count(*) from data_generation_run where status='COMPLETED'") != 1)
                throw new IllegalStateException("Template must contain exactly one completed generation run.");
            for (String table : List.of("staging_record", "exception_record", "quarantined_record"))
                if (tableCount(source, table) != 0) throw new IllegalStateException("Template contains Phase 3 staging data; refusing clone.");
            if (tableCount(source, "score_run") != 0) throw new IllegalStateException("Template already contains scores; refusing clone.");
        }
    }

    private static void inspect(String database) throws SQLException {
        try (Connection c = connect(database)) {
            System.out.println("Inspection of " + database + ":");
            for (String table : TABLES) System.out.println("  " + table + "=" + tableCount(c, table));
            if (tableCount(c, "flyway_schema_history") > 0)
                System.out.println("  latest successful Flyway version=" + scalar(c, "select coalesce(max(version::int),0) from flyway_schema_history where success"));
        }
    }

    private static long tableCount(Connection c, String table) throws SQLException {
        if (!TABLES.contains(table) && !Set.of("data_generation_run", "flyway_schema_history").contains(table))
            throw new IllegalArgumentException("Table is not allowlisted.");
        try (PreparedStatement s = c.prepareStatement("select to_regclass(?)")) {
            s.setString(1, "public." + table);
            try (ResultSet r = s.executeQuery()) { r.next(); if (r.getString(1) == null) return 0; }
        }
        return scalar(c, "select count(*) from public." + table);
    }

    private static long scalar(Connection c, String query) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) { r.next(); return r.getLong(1); }
    }
}
