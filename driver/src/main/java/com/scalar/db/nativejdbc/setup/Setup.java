package com.scalar.db.nativejdbc.setup;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sets up every decoupled ScalarDB table of a catalog for the driver (design section 3): creates
 * the read view {@code rc_T} and the in-flight index, and on an imported table runs the backfill of
 * section 1. Each step is create-if-absent or re-runnable, so running the setup again changes
 * nothing. It needs DDL privileges and is run by an operator, never at connect; the driver only
 * checks at catalog load that the view and the index exist.
 */
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"
    },
    justification =
        "the DDL and backfill text holds only names from ScalarDB's catalog, each quoted by"
            + " Engine.quote, which doubles an embedded closing quote; values are bound")
public final class Setup {

  /** The {@code tx_id} the backfill writes on a row ScalarDB deems committed. */
  public static final String BACKFILL_TX_ID = "scalardb-native-backfill";

  private static final Duration DEFAULT_BACKFILL_WAIT = Duration.ofMinutes(1);

  private final Engine engine;
  private final Connection connection;
  private final Duration backfillWait;

  private Setup(Engine engine, Connection connection, Duration backfillWait) {
    this.engine = engine;
    this.connection = connection;
    this.backfillWait = backfillWait;
  }

  /**
   * Runs the setup for every decoupled table of the catalog, waiting up to a minute per table for
   * in-flight rows the backfill must outlast.
   *
   * @param catalog the catalog of the database
   * @param connection a connection to the same database, in autocommit, with DDL privileges
   * @return one line per table saying what was done
   * @throws SQLException if a step fails; steps already done stay done
   */
  public static List<String> run(Catalog catalog, Connection connection) throws SQLException {
    return run(catalog, connection, DEFAULT_BACKFILL_WAIT);
  }

  /**
   * Runs the setup for every decoupled table of the catalog.
   *
   * @param catalog the catalog of the database
   * @param connection a connection to the same database, in autocommit, with DDL privileges
   * @param backfillWait how long to wait per table for the backfill to complete
   * @return one line per table saying what was done
   * @throws SQLException if a step fails; steps already done stay done
   */
  public static List<String> run(Catalog catalog, Connection connection, Duration backfillWait)
      throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new SQLException("the setup runs in autocommit mode", "55000");
    }
    Setup setup = new Setup(catalog.engine(), connection, backfillWait);
    List<String> report = new ArrayList<>();
    try {
      for (TableInfo t : catalog.decoupledTables()) {
        report.add(setup.table(t));
      }
    } finally {
      catalog.invalidate(); // the next lookup sees what was created
    }
    return report;
  }

  private String table(TableInfo t) throws SQLException {
    String view = t.readView(engine);
    String index = t.inflightIndex(engine);
    for (String name : List.of(view, index)) {
      if (!engine.fitsIdentifier(name)) {
        throw new SQLException(
            "the name "
                + name
                + " the setup needs for "
                + t.namespace()
                + "."
                + t.table()
                + " is longer than the engine allows",
            "42622");
      }
    }
    execute(viewDdl(t, view));
    if (!engine
        .setupObjects(connection, List.of(t.namespace()))
        .contains(
            ("index:" + t.namespace() + "." + t.metadataTable() + "." + index)
                .toLowerCase(Locale.ROOT))) {
      execute(indexDdl(t, index));
    }
    String line =
        t.namespace() + "." + t.table() + ": read view " + view + ", in-flight index " + index;
    if (t.layout() == Layout.DECOUPLED_IMPORTED) {
      line += ", backfill " + backfill(t) + " rows";
    }
    return line;
  }

  private String name(String schema, String object) {
    return engine.quote(schema) + "." + engine.quote(object);
  }

  private String q(String identifier) {
    return engine.quote(identifier);
  }

  private String keyJoin(TableInfo t) {
    List<String> on = new ArrayList<>();
    for (String k : t.keyColumns()) {
      on.add("m." + q(k) + " = t." + q(k));
    }
    return String.join(" AND ", on);
  }

  /**
   * The committed image of each row (design section 3): the before image while one is in flight.
   */
  private String viewDdl(TableInfo t, String view) {
    List<String> select = new ArrayList<>();
    List<String> keys = t.keyColumns();
    for (String c : t.dataColumns()) {
      select.add(
          keys.contains(c)
              ? "t." + q(c)
              : "CASE WHEN m."
                  + q("tx_state")
                  + " IN (1, 2) THEN m."
                  + q("before_" + c)
                  + " ELSE t."
                  + q(c)
                  + " END AS "
                  + q(c));
    }
    String create =
        engine == Engine.SQLSERVER ? "CREATE OR ALTER VIEW " : "CREATE OR REPLACE VIEW ";
    return create
        + name(t.namespace(), view)
        + " AS SELECT "
        + String.join(", ", select)
        + " FROM "
        + name(t.namespace(), t.dataTable())
        + " t LEFT JOIN "
        + name(t.namespace(), metadataTable(t))
        + " m ON "
        + keyJoin(t)
        + " WHERE COALESCE(m."
        + q("tx_state")
        + ", 3) = 3 OR (m."
        + q("tx_state")
        + " IN (1, 2) AND (m."
        + q("before_tx_id")
        + " IS NOT NULL OR m."
        + q("before_tx_version")
        + " IS NOT NULL))";
  }

  /**
   * The index over in-flight rows: partial where the engine has partial indexes (PostgreSQL, SQL
   * Server), on {@code (tx_state, key)} elsewhere (design section 3, Indexes).
   */
  private String indexDdl(TableInfo t, String index) {
    List<String> keys = new ArrayList<>();
    for (String k : t.keyColumns()) {
      keys.add(q(k));
    }
    String on = name(t.namespace(), metadataTable(t));
    String state = q("tx_state");
    switch (engine) {
      case POSTGRESQL:
      case SQLSERVER:
        return "CREATE INDEX "
            + q(index)
            + " ON "
            + on
            + " ("
            + String.join(", ", keys)
            + ") WHERE "
            + state
            + " IN (1, 2)";
      case MYSQL:
      case MARIADB:
        return "CREATE INDEX "
            + q(index)
            + " ON "
            + on
            + " ("
            + state
            + ", "
            + String.join(", ", keys)
            + ")";
      case ORACLE:
        return "CREATE INDEX "
            + name(t.namespace(), index)
            + " ON "
            + on
            + " ("
            + state
            + ", "
            + String.join(", ", keys)
            + ")";
    }
    throw new AssertionError(engine);
  }

  private static String metadataTable(TableInfo t) {
    String m = t.metadataTable();
    if (m == null) {
      throw new IllegalStateException(t + " has no metadata table");
    }
    return m;
  }

  /**
   * The backfill of section 1 on an imported table: a metadata row for every data row without one,
   * and the sentinel stamp on every row whose {@code tx_id} is NULL, repeated until neither remains
   * and no in-flight row has a NULL {@code before_tx_id}.
   *
   * @return the number of rows stamped
   */
  // ponytail: one statement per step over the whole table; batch by key range for very large tables
  private long backfill(TableInfo t) throws SQLException {
    String data = name(t.namespace(), t.dataTable());
    String meta = name(t.namespace(), metadataTable(t));
    List<String> keys = new ArrayList<>();
    List<String> dataKeys = new ArrayList<>();
    for (String k : t.keyColumns()) {
      keys.add(q(k));
      dataKeys.add("t." + q(k));
    }
    String stamp =
        q("tx_id")
            + ", "
            + q("tx_state")
            + ", "
            + q("tx_version")
            + ", "
            + q("tx_prepared_at")
            + ", "
            + q("tx_committed_at");
    String missing =
        " FROM "
            + data
            + " t WHERE NOT EXISTS (SELECT 1 FROM "
            + meta
            + " m WHERE "
            + keyJoin(t)
            + ")";
    String insert =
        "INSERT INTO "
            + meta
            + " ("
            + String.join(", ", keys)
            + ", "
            + stamp
            + ") SELECT "
            + String.join(", ", dataKeys)
            + ", ?, 3, 1, ?, ?"
            + missing;
    String update =
        "UPDATE "
            + meta
            + " SET "
            + q("tx_id")
            + " = ?, "
            + q("tx_state")
            + " = 3, "
            + q("tx_version")
            + " = 1, "
            + q("tx_prepared_at")
            + " = ?, "
            + q("tx_committed_at")
            + " = ? WHERE "
            + q("tx_id")
            + " IS NULL";
    String remaining =
        "SELECT (SELECT COUNT(*)"
            + missing
            + ") + (SELECT COUNT(*) FROM "
            + meta
            + " WHERE "
            + q("tx_id")
            + " IS NULL) + (SELECT COUNT(*) FROM "
            + meta
            + " WHERE "
            + q("tx_state")
            + " IN (1, 2) AND "
            + q("before_tx_id")
            + " IS NULL)"
            + (engine == Engine.ORACLE ? " FROM dual" : "");
    long stamped = 0;
    long deadline = System.nanoTime() + backfillWait.toNanos();
    while (true) {
      long now = System.currentTimeMillis();
      try {
        stamped += stamp(insert, now);
      } catch (SQLException e) {
        if (e.getSQLState() == null || !e.getSQLState().startsWith("23")) {
          throw e;
        }
        // a ScalarDB transaction inserted one of these metadata rows first: count again
      }
      stamped += stamp(update, now);
      long left = count(remaining);
      if (left == 0) {
        return stamped;
      }
      if (System.nanoTime() > deadline) {
        throw new SQLException(
            "the backfill of "
                + t.namespace()
                + "."
                + t.table()
                + " is not complete: "
                + left
                + " rows still have no committed metadata or are in flight over such a row; run"
                + " the setup again once ScalarDB's transactions on the table have finished",
            "55000");
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SQLException("the backfill was interrupted", "57014", e);
      }
    }
  }

  private int stamp(String sql, long now) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, BACKFILL_TX_ID);
      ps.setLong(2, now);
      ps.setLong(3, now);
      return ps.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute(sql);
    }
  }
}
