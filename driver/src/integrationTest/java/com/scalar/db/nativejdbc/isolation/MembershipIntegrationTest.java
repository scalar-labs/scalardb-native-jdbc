package com.scalar.db.nativejdbc.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.isolation.MaelstromIntegrationTest.Level;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Scans by predicate against inserts and deletes (testing plan T5), which Elle's single-key models
 * do not reach: rows that come and go, through the driver and through ScalarDB, each transaction
 * deciding what to write from what a scan of a group returned. Three regions of one table:
 *
 * <ul>
 *   <li><b>Moves</b>, groups 0 to 2: a transaction scans a group, deletes one of its rows and
 *       inserts a row under an unused key into a group, or increments a row it read by key, which
 *       must not bring back a row deleted since. The number of rows never changes, at every level.
 *   <li><b>At least one</b>, groups 10 to 12: a transaction deletes a row of a group whose scan
 *       shows more than one, or adds one to a group with fewer than three. At SERIALIZABLE no group
 *       is ever empty; at SNAPSHOT write skew may empty one.
 *   <li><b>At most one</b>, groups 20 to 22: a transaction inserts a row into a group whose scan
 *       shows none, or deletes the group's rows by predicate. At SERIALIZABLE no group ever holds
 *       two; below it a phantom may add a second.
 * </ul>
 *
 * <p>After the run, a ScalarDB scan checks the count at every level and the two groups' rules at
 * SERIALIZABLE; concurrent driver reads in one transaction check the same, the count except at READ
 * COMMITTED with ScalarDB writing (a ScalarDB commit reaches its rows one by one). Where a rule may
 * break, its breaks are counted, not checked. A plain vendor connection in the driver's place,
 * which runs no protocol, must break one.
 *
 * <p>{@code -Dnativejdbc.isolation=true}; {@code -Dnativejdbc.isolation.seconds} sets each run's
 * length (default 30), at least {@value MaelstromIntegrationTest#ANOMALY_SECONDS} at SERIALIZABLE.
 * Each run's summary goes to {@code build/isolation/<engine>/MEMBERSHIP/<name>.txt}.
 */
@EnabledIfSystemProperty(named = "nativejdbc.isolation", matches = "true")
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "PREDICTABLE_RANDOM"
    },
    justification = "the table's name, from the test; random operations, no secret")
class MembershipIntegrationTest {

  private static final int MOVE_GROUPS = 3;
  private static final int MOVE_ROWS = 24;
  private static final int MOVE_KEYS = 60; // keys 0 to 59, of which 24 are used at any time
  private static final int AT_LEAST = 10; // groups 10 to 12, keys 100 to 199
  private static final int AT_MOST = 20; // groups 20 to 22, keys 200 to 299
  private static final int RULE_GROUPS = 3;
  private static final int WRITERS = 4;
  private static final int READERS = 2;
  private static final int KEPT = 20;
  // a catalog of its own per run (BankIntegrationTest)
  private static final AtomicLong CATALOGS = new AtomicLong(1_000);

  static Stream<Arguments> runs() {
    return BankIntegrationTest.runs();
  }

  static Stream<Engines> engines() {
    return BankIntegrationTest.engines();
  }

  @ParameterizedTest(name = "{0} {1} mixed={2} inline={3}")
  @MethodSource("runs")
  void rules_hold(Engines e, Level level, boolean mixed, boolean inline) throws Exception {
    String name = level + (mixed ? "-MIXED" : "-DRIVER") + (inline ? "-inline" : "");
    Run run =
        new Run(e, name, level, MaelstromIntegrationTest.scalarDbSettings(e, level), mixed, false);
    run.go(inline);
    assertThat(run.finalCount).as(run.summary()).isEqualTo(MOVE_ROWS);
    // todo 326: on SQL Server at SERIALIZABLE, scans of a group almost never commit while ScalarDB
    // writes the table, and the writers that scan first with them; with the driver's writers only,
    // on a 4-vCPU runner, their locking scans deadlock each other as often
    if (!(e.engine() == Engine.SQLSERVER && level == Level.SERIALIZABLE)) {
      for (String kind : List.of("move", "add", "remove", "claim", "release")) {
        assertThat(run.done("driver " + kind)).as(run.summary()).isPositive();
        if (mixed) {
          assertThat(run.done("ScalarDB " + kind)).as(run.summary()).isPositive();
        }
      }
    }
    if (level == Level.SERIALIZABLE) {
      assertThat(run.finalRules).as(run.summary()).isEmpty();
      assertThat(run.readViolations).as(run.summary()).isEmpty();
    } else if (level != Level.READ_COMMITTED || !mixed) {
      assertThat(run.countViolations).as(run.summary()).isEmpty();
    }
  }

  /** A vendor connection runs no protocol: the count breaks. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("engines")
  void count_breaksWithoutTheDriver(Engines e) throws Exception {
    Run run = new Run(e, "VENDOR", Level.READ_COMMITTED, new Properties(), true, true);
    run.go(true);
    assertThat(run.finalCount).as(run.summary()).isNotEqualTo(MOVE_ROWS);
  }

  /** A transaction that reads what it needs and finds nothing to do. */
  private static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;

    Refused() {
      super(null, null, false, false);
    }
  }

  /** One run: the table made and filled, the clients run, the rules checked. */
  private static final class Run {
    final Engines e;
    final String name;
    final Level level;
    final Properties scalarDb;
    final boolean mixed;
    final boolean vendor;
    final String table;
    final String namespace;
    final String stored;
    final String id;
    final String grp;
    final String v;
    final String catalogTtl = String.valueOf(3_000_000 - CATALOGS.incrementAndGet());
    final Map<String, AtomicLong> done = new ConcurrentHashMap<>();
    final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();
    final Map<String, String> firstFailures = new ConcurrentHashMap<>();
    final AtomicLong reads = new AtomicLong();
    final List<String> readViolations = Collections.synchronizedList(new ArrayList<>());
    final List<String> countViolations = Collections.synchronizedList(new ArrayList<>());
    final AtomicLong readViolationCount = new AtomicLong();
    volatile long finalCount = -1;
    List<String> finalRules = List.of();

    Run(Engines e, String name, Level level, Properties scalarDb, boolean mixed, boolean vendor) {
      this.e = e;
      this.name = name;
      this.level = level;
      this.scalarDb = new Properties();
      this.scalarDb.putAll(scalarDb);
      // ScalarDB's scan of a group, by a column that is not a key
      this.scalarDb.setProperty("scalar.db.cross_partition_scan.enabled", "true");
      this.scalarDb.setProperty("scalar.db.cross_partition_scan.filtering.enabled", "true");
      this.mixed = mixed;
      this.vendor = vendor;
      String t = "member_" + name.toLowerCase(Locale.ROOT).replace('-', '_');
      this.table = "iso." + t;
      this.namespace = e.storedSchema("iso");
      this.stored = e.storedTable(t);
      this.id = e.storedColumn("id");
      this.grp = e.storedColumn("grp");
      this.v = e.storedColumn("v");
    }

    long done(String kind) {
      AtomicLong n = done.get(kind);
      return n == null ? 0 : n.get();
    }

    void go(boolean inline) throws Exception {
      try (DistributedTransactionAdmin admin = e.scalarDb(scalarDb).getTransactionAdmin()) {
        admin.createCoordinatorTables(true);
        admin.createNamespace(namespace, true);
        admin.dropTable(namespace, stored, true);
        admin.createTable(
            namespace,
            stored,
            TableMetadata.newBuilder()
                .addColumn(id, DataType.INT)
                .addColumn(grp, DataType.INT)
                .addColumn(v, DataType.INT)
                .addPartitionKey(id)
                .build(),
            inline ? Map.of() : Map.of("transaction-metadata-decoupling", "true"));
      }
      try (Connection c = e.open()) {
        assertThat(
                Setup.run(
                    new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                    c,
                    t -> t.namespace().equals(namespace),
                    Duration.ofMinutes(1),
                    List.of()))
            .allMatch(Setup.Result::succeeded, "setup succeeded");
      }
      try (DistributedTransactionManager m = e.scalarDb(scalarDb).getTransactionManager()) {
        DistributedTransaction tx = m.begin();
        for (int k = 0; k < MOVE_ROWS; k++) {
          tx.insert(insert(k, k % MOVE_GROUPS));
        }
        for (int g = 0; g < RULE_GROUPS; g++) {
          tx.insert(insert(100 + 2 * g, AT_LEAST + g));
          tx.insert(insert(101 + 2 * g, AT_LEAST + g));
        }
        tx.commit();

        long seconds = Long.parseLong(System.getProperty("nativejdbc.isolation.seconds", "30"));
        if (level == Level.SERIALIZABLE) {
          seconds = Math.max(seconds, MaelstromIntegrationTest.ANOMALY_SECONDS);
        }
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS);
        try {
          List<Future<?>> clients = new ArrayList<>();
          for (int i = 0; i < WRITERS; i++) {
            boolean throughScalarDb = mixed && i % 2 != 0;
            clients.add(
                pool.submit(
                    () -> {
                      if (throughScalarDb) {
                        scalarDbWriter(m, end);
                      } else {
                        driverWriter(end);
                      }
                      return null;
                    }));
          }
          for (int i = 0; i < READERS; i++) {
            clients.add(
                pool.submit(
                    () -> {
                      reader(end);
                      return null;
                    }));
          }
          for (Future<?> f : clients) {
            f.get();
          }
        } finally {
          pool.shutdownNow();
        }
        finalCheck(m);
      }
      Path dir = Path.of("build", "isolation", e.name(), "MEMBERSHIP");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(name + ".txt"), summary(), StandardCharsets.UTF_8);
    }

    String summary() {
      return String.format(
          Locale.ROOT,
          "%s %s: done %s, reads %d, reads off %d, failures %s%nfinal: %d moved rows, %s%n"
              + "count off in reads: %s%nreads off, the first %d: %s%nfirst failures: %s%n",
          e,
          name,
          new TreeMap<>(done),
          reads.get(),
          readViolationCount.get(),
          new TreeMap<>(failures),
          finalCount,
          finalRules.isEmpty() ? "rules hold" : finalRules,
          countViolations,
          KEPT,
          readViolations,
          new TreeMap<>(firstFailures));
    }

    private void did(String kind) {
      done.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
    }

    private void fail(String what, Exception ex) {
      failures.computeIfAbsent(what, w -> new AtomicLong()).incrementAndGet();
      firstFailures.putIfAbsent(what, String.valueOf(ex.getMessage()));
    }

    private Connection connection() throws SQLException {
      Connection c;
      if (vendor) {
        c = e.open();
      } else {
        Properties p = new Properties();
        p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", catalogTtl);
        c = e.openDriver(p);
      }
      c.setTransactionIsolation(level.jdbc);
      c.setAutoCommit(false);
      return c;
    }

    // the operations, the same on both sides

    /** What a side does to the table. */
    private interface Side {
      List<Integer> scan(int group) throws Exception;

      boolean exists(int key) throws Exception;

      void insert(int key, int group) throws Exception;

      /** Deletes the row by key; false if there was none. */
      boolean delete(int key) throws Exception;

      /** Deletes the group's rows by predicate, or by key on ScalarDB's side. */
      void deleteGroup(int group, List<Integer> keys) throws Exception;

      /** Increments the row read by key; false if there was none to read. */
      boolean increment(int key) throws Exception;
    }

    /** One transaction's operations, its kind; {@link Refused} when there is nothing to do. */
    private static String operation(Side s) throws Exception {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      return switch (r.nextInt(5)) {
        case 0, 1 -> {
          List<Integer> rows = s.scan(r.nextInt(MOVE_GROUPS));
          int fresh = r.nextInt(MOVE_KEYS);
          if (rows.isEmpty() || s.exists(fresh)) {
            throw new Refused();
          }
          if (!s.delete(rows.get(r.nextInt(rows.size())))) {
            // deleted since the scan, as READ COMMITTED lets a statement see
            throw new Refused();
          }
          s.insert(fresh, r.nextInt(MOVE_GROUPS));
          yield "move";
        }
        case 2 -> {
          if (!s.increment(r.nextInt(MOVE_KEYS))) {
            throw new Refused();
          }
          yield "increment";
        }
        case 3 -> {
          int g = AT_LEAST + r.nextInt(RULE_GROUPS);
          List<Integer> rows = s.scan(g);
          if (r.nextBoolean()) {
            if (rows.size() <= 1) {
              throw new Refused();
            }
            s.delete(rows.get(r.nextInt(rows.size())));
            yield "remove";
          }
          int fresh = 100 + r.nextInt(100);
          if (rows.size() >= 3 || s.exists(fresh)) {
            throw new Refused();
          }
          s.insert(fresh, g);
          yield "add";
        }
        default -> {
          int g = AT_MOST + r.nextInt(RULE_GROUPS);
          List<Integer> rows = s.scan(g);
          if (r.nextBoolean()) {
            if (rows.isEmpty()) {
              throw new Refused();
            }
            s.deleteGroup(g, rows);
            yield "release";
          }
          int fresh = 200 + r.nextInt(100);
          if (!rows.isEmpty() || s.exists(fresh)) {
            throw new Refused();
          }
          s.insert(fresh, g);
          yield "claim";
        }
      };
    }

    // the driver's side

    private void driverWriter(long end) throws SQLException {
      Connection c = connection();
      try {
        while (System.nanoTime() < end) {
          Connection current = c;
          try {
            String kind = operation(new DriverSide(current));
            c.commit();
            did("driver " + kind);
          } catch (Refused ex) {
            c.rollback();
          } catch (SQLException ex) {
            fail("driver " + ex.getSQLState(), ex);
            try {
              c.rollback();
            } catch (SQLException closed) {
              c.close();
              c = connection();
            }
          } catch (Exception ex) {
            fail("driver " + ex, ex);
            c.rollback();
          }
        }
      } finally {
        c.close();
      }
    }

    private final class DriverSide implements Side {
      private final Connection c;

      DriverSide(Connection c) {
        this.c = c;
      }

      @Override
      public List<Integer> scan(int group) throws SQLException {
        try (PreparedStatement ps =
            c.prepareStatement("SELECT id FROM " + table + " WHERE grp = ? ORDER BY id")) {
          ps.setInt(1, group);
          return keys(ps);
        }
      }

      @Override
      public boolean exists(int key) throws SQLException {
        try (PreparedStatement ps =
            c.prepareStatement("SELECT id FROM " + table + " WHERE id = ?")) {
          ps.setInt(1, key);
          return !keys(ps).isEmpty();
        }
      }

      @Override
      public void insert(int key, int group) throws SQLException {
        try (PreparedStatement ps =
            c.prepareStatement("INSERT INTO " + table + " (id, grp, v) VALUES (?, ?, 0)")) {
          ps.setInt(1, key);
          ps.setInt(2, group);
          ps.executeUpdate();
        }
      }

      @Override
      public boolean delete(int key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
          ps.setInt(1, key);
          return ps.executeUpdate() == 1;
        }
      }

      @Override
      public void deleteGroup(int group, List<Integer> keys) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE grp = ?")) {
          ps.setInt(1, group);
          ps.executeUpdate();
        }
      }

      @Override
      public boolean increment(int key) throws SQLException {
        if (!exists(key)) {
          return false;
        }
        try (PreparedStatement ps =
            c.prepareStatement("UPDATE " + table + " SET v = v + 1 WHERE id = ?")) {
          ps.setInt(1, key);
          ps.executeUpdate();
        }
        return true;
      }

      private List<Integer> keys(PreparedStatement ps) throws SQLException {
        List<Integer> keys = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            keys.add(rs.getInt(1));
          }
        }
        return keys;
      }
    }

    private void reader(long end) throws SQLException {
      Connection c = connection();
      try {
        while (System.nanoTime() < end) {
          try {
            read(c);
            c.commit();
            reads.incrementAndGet();
          } catch (SQLException ex) {
            fail("read " + ex.getSQLState(), ex);
            try {
              c.rollback();
            } catch (SQLException closed) {
              c.close();
              c = connection();
            }
          }
        }
      } finally {
        c.close();
      }
    }

    /** The count and the groups' rules, read in one transaction. */
    private void read(Connection c) throws SQLException {
      List<String> off = new ArrayList<>();
      try (PreparedStatement ps =
              c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE grp < " + AT_LEAST);
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        long count = rs.getLong(1);
        if (count != MOVE_ROWS) {
          off.add("count " + count);
          if (countViolations.size() < KEPT) {
            countViolations.add("count " + count);
          }
        }
      }
      try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT grp, COUNT(*) FROM "
                      + table
                      + " WHERE grp >= "
                      + AT_LEAST
                      + " GROUP BY grp ORDER BY grp");
          ResultSet rs = ps.executeQuery()) {
        Map<Integer, Long> rows = new TreeMap<>();
        while (rs.next()) {
          rows.put(rs.getInt(1), rs.getLong(2));
        }
        off.addAll(rules(rows));
      }
      if (!off.isEmpty()) {
        readViolationCount.incrementAndGet();
        if (readViolations.size() < KEPT) {
          readViolations.add(String.join("; ", off));
        }
      }
    }

    /** The two groups' rules over their rows per group. */
    private static List<String> rules(Map<Integer, Long> rows) {
      List<String> off = new ArrayList<>();
      for (int g = 0; g < RULE_GROUPS; g++) {
        if (rows.getOrDefault(AT_LEAST + g, 0L) < 1) {
          off.add("group " + (AT_LEAST + g) + " empty");
        }
        if (rows.getOrDefault(AT_MOST + g, 0L) > 1) {
          off.add("group " + (AT_MOST + g) + " has " + rows.get(AT_MOST + g));
        }
      }
      return off;
    }

    // ScalarDB's side

    private void scalarDbWriter(DistributedTransactionManager m, long end) {
      while (System.nanoTime() < end) {
        DistributedTransaction tx;
        try {
          tx = m.begin();
        } catch (TransactionException ex) {
          fail("ScalarDB begin " + ex.getClass().getSimpleName(), ex);
          continue;
        }
        try {
          String kind = operation(new ScalarDbSide(tx));
          tx.commit();
          did("ScalarDB " + kind);
        } catch (Refused ex) {
          abort(tx);
        } catch (Exception ex) {
          fail("ScalarDB " + ex.getClass().getSimpleName(), ex);
          abort(tx);
        }
      }
    }

    private static void abort(DistributedTransaction tx) {
      try {
        tx.abort();
      } catch (TransactionException ignored) {
        // lazy recovery takes it
      }
    }

    private final class ScalarDbSide implements Side {
      private final DistributedTransaction tx;

      ScalarDbSide(DistributedTransaction tx) {
        this.tx = tx;
      }

      @Override
      public List<Integer> scan(int group) throws TransactionException {
        List<Integer> keys = new ArrayList<>();
        for (Result r :
            tx.scan(
                Scan.newBuilder()
                    .namespace(namespace)
                    .table(stored)
                    .all()
                    .where(ConditionBuilder.column(grp).isEqualToInt(group))
                    .build())) {
          keys.add(r.getInt(id));
        }
        Collections.sort(keys);
        return keys;
      }

      @Override
      public boolean exists(int key) throws TransactionException {
        return tx.get(
                Get.newBuilder()
                    .namespace(namespace)
                    .table(stored)
                    .partitionKey(Key.ofInt(id, key))
                    .build())
            .isPresent();
      }

      @Override
      public void insert(int key, int group) throws TransactionException {
        tx.insert(Run.this.insert(key, group));
      }

      @Override
      public boolean delete(int key) throws TransactionException {
        if (!exists(key)) {
          return false;
        }
        tx.delete(
            Delete.newBuilder()
                .namespace(namespace)
                .table(stored)
                .partitionKey(Key.ofInt(id, key))
                .build());
        return true;
      }

      @Override
      public void deleteGroup(int group, List<Integer> keys) throws TransactionException {
        for (int key : keys) {
          delete(key);
        }
      }

      @Override
      public boolean increment(int key) throws TransactionException {
        @Nullable Result r =
            tx.get(
                    Get.newBuilder()
                        .namespace(namespace)
                        .table(stored)
                        .partitionKey(Key.ofInt(id, key))
                        .build())
                .orElse(null);
        if (r == null) {
          return false;
        }
        tx.update(
            Update.newBuilder()
                .namespace(namespace)
                .table(stored)
                .partitionKey(Key.ofInt(id, key))
                .intValue(v, r.getInt(v) + 1)
                .build());
        return true;
      }
    }

    private Insert insert(int key, int group) {
      return Insert.newBuilder()
          .namespace(namespace)
          .table(stored)
          .partitionKey(Key.ofInt(id, key))
          .intValue(grp, group)
          .intValue(v, 0)
          .build();
    }

    /**
     * The count and the rules, read by a ScalarDB scan, which recovers the transactions left in
     * flight first; retried while it meets them.
     */
    private void finalCheck(DistributedTransactionManager m) throws Exception {
      @Nullable Exception last = null;
      for (int attempt = 0; attempt < 30; attempt++) {
        DistributedTransaction tx = m.begin();
        try {
          List<Result> rows =
              tx.scan(Scan.newBuilder().namespace(namespace).table(stored).all().build());
          tx.commit();
          Map<Integer, Long> groups = new TreeMap<>();
          long moved = 0;
          for (Result r : rows) {
            int g = r.getInt(grp);
            if (g < AT_LEAST) {
              moved++;
            } else {
              groups.merge(g, 1L, Long::sum);
            }
          }
          finalCount = moved;
          finalRules = rules(groups);
          return;
        } catch (TransactionException ex) {
          last = ex;
          abort(tx);
          Thread.sleep(1000);
        }
      }
      throw new IllegalStateException("the final scan kept failing", last);
    }
  }
}
