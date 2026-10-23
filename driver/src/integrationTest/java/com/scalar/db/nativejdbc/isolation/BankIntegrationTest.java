package com.scalar.db.nativejdbc.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Upsert;
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
import java.util.Optional;
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
 * The bank test (testing plan T5): accounts in groups, transfers inside a group through the driver
 * and through ScalarDB, and concurrent driver reads of {@code SUM(balance)}, of the sums grouped by
 * group and of every row, the split's aggregate path under concurrency, which Elle's single-key
 * models do not reach.
 *
 * <p>After the run, a ScalarDB scan finds every group's total as it began and no balance below
 * zero, at every level: lost updates are blocked at every level (design section 9). A concurrent
 * read finds the totals too at SNAPSHOT and SERIALIZABLE, and at READ COMMITTED when only the
 * driver writes; at READ COMMITTED a ScalarDB commit reaches its rows one by one, and a read may
 * see one row of a transfer committed and the other's before image, which that level allows: those
 * reads are counted, not checked.
 *
 * <p>A plain vendor connection in the driver's place, which runs no protocol, must break a total.
 *
 * <p>{@code -Dnativejdbc.isolation=true}; {@code -Dnativejdbc.isolation.seconds} sets each run's
 * length (default 30), at least {@value MaelstromIntegrationTest#ANOMALY_SECONDS} at SERIALIZABLE,
 * where SQL Server commits few transactions. Each run's summary goes to {@code
 * build/isolation/<engine>/BANK/<name>.txt}.
 */
@EnabledIfSystemProperty(named = "nativejdbc.isolation", matches = "true")
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "PREDICTABLE_RANDOM"
    },
    justification = "the table's name, from the test; random transfers, no secret")
class BankIntegrationTest {

  private static final int GROUPS = 3;
  private static final int ACCOUNTS = 12;
  private static final int BALANCE = 100;
  private static final int WRITERS = 4;
  private static final int READERS = 2;
  private static final int KEPT = 20;
  // the driver keeps a catalog per database, user and TTL, and a table created after its load is
  // plain until the TTL passes (design 4.4, "Catalog cache"): each run's own TTL loads a catalog
  // after its table's setup, as the README's order for a new table has it
  private static final AtomicLong CATALOGS = new AtomicLong();

  static Stream<Arguments> runs() {
    List<Arguments> runs = new ArrayList<>();
    for (Engines e : engines().toList()) {
      for (Level level : Level.values()) {
        // Oracle refuses SERIALIZABLE (design section 5)
        if (level == Level.SERIALIZABLE && e.engine() == Engine.ORACLE) {
          continue;
        }
        for (boolean mixed : List.of(true, false)) {
          for (boolean inline : List.of(false, true)) {
            runs.add(Arguments.of(e, level, mixed, inline));
          }
        }
      }
    }
    return runs.stream();
  }

  static Stream<Engines> engines() {
    return Engines.selected().stream().filter(e -> e != Engines.MYSQL_LOWER_CASE);
  }

  @ParameterizedTest(name = "{0} {1} mixed={2} inline={3}")
  @MethodSource("runs")
  void totals_hold(Engines e, Level level, boolean mixed, boolean inline) throws Exception {
    String name = level + (mixed ? "-MIXED" : "-DRIVER") + (inline ? "-inline" : "");
    Run run =
        new Run(e, name, level, MaelstromIntegrationTest.scalarDbSettings(e, level), mixed, false);
    run.go(inline);
    assertThat(run.finalViolations).as(run.summary()).isEmpty();
    assertThat(run.transfers.get()).as(run.summary()).isPositive();
    if (mixed) {
      assertThat(run.scalarDbTransfers.get()).as(run.summary()).isPositive();
    }
    // todo 324: on SQL Server at SERIALIZABLE, reads of the whole table almost never commit while
    // ScalarDB writes it
    if (!(e.engine() == Engine.SQLSERVER && level == Level.SERIALIZABLE && mixed)) {
      assertThat(run.reads.get()).as(run.summary()).isPositive();
    }
    if (level != Level.READ_COMMITTED || !mixed) {
      assertThat(run.readViolations).as(run.summary()).isEmpty();
    }
  }

  /** A vendor connection runs no protocol: the totals break. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("engines")
  void totals_breakWithoutTheDriver(Engines e) throws Exception {
    Run run = new Run(e, "VENDOR", Level.READ_COMMITTED, new Properties(), true, true);
    run.go(true);
    assertThat(run.finalViolations).as(run.summary()).isNotEmpty();
  }

  /** One run: the table made and filled, the clients run, the totals checked. */
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
    final String catalogTtl = String.valueOf(3_000_000 - CATALOGS.incrementAndGet());
    final AtomicLong transfers = new AtomicLong();
    final AtomicLong scalarDbTransfers = new AtomicLong();
    final AtomicLong refused = new AtomicLong();
    final AtomicLong reads = new AtomicLong();
    final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();
    final Map<String, String> firstFailures = new ConcurrentHashMap<>();
    final List<String> readViolations = Collections.synchronizedList(new ArrayList<>());
    final AtomicLong readViolationCount = new AtomicLong();
    List<String> finalViolations = List.of();

    Run(Engines e, String name, Level level, Properties scalarDb, boolean mixed, boolean vendor) {
      this.e = e;
      this.name = name;
      this.level = level;
      this.scalarDb = scalarDb;
      this.mixed = mixed;
      this.vendor = vendor;
      String t = "bank_" + name.toLowerCase(Locale.ROOT).replace('-', '_');
      this.table = "iso." + t;
      this.namespace = e.storedSchema("iso");
      this.stored = e.storedTable(t);
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
                .addColumn(e.storedColumn("id"), DataType.INT)
                .addColumn(e.storedColumn("grp"), DataType.INT)
                .addColumn(e.storedColumn("balance"), DataType.INT)
                .addPartitionKey(e.storedColumn("id"))
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
        for (int id = 0; id < ACCOUNTS; id++) {
          tx.insert(
              Insert.newBuilder()
                  .namespace(namespace)
                  .table(stored)
                  .partitionKey(Key.ofInt(e.storedColumn("id"), id))
                  .intValue(e.storedColumn("grp"), id % GROUPS)
                  .intValue(e.storedColumn("balance"), BALANCE)
                  .build());
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
        finalViolations = finalCheck(m);
      }
      Path dir = Path.of("build", "isolation", e.name(), "BANK");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(name + ".txt"), summary(), StandardCharsets.UTF_8);
    }

    String summary() {
      return String.format(
          Locale.ROOT,
          "%s %s: transfers %d (ScalarDB %d), refused for funds %d, reads %d, reads off %d,"
              + " failures %s%nfinal: %s%nreads off, the first %d: %s%nfirst failures: %s%n",
          e,
          name,
          transfers.get(),
          scalarDbTransfers.get(),
          refused.get(),
          reads.get(),
          readViolationCount.get(),
          new TreeMap<>(failures),
          finalViolations.isEmpty() ? "totals hold" : finalViolations,
          KEPT,
          readViolations,
          new TreeMap<>(firstFailures));
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
        p.setProperty("scalardbnative.catalogTtlMs", catalogTtl);
        c = e.openDriver(p);
      }
      c.setTransactionIsolation(level.jdbc);
      c.setAutoCommit(false);
      return c;
    }

    // the driver's side

    private void driverWriter(long end) throws SQLException {
      Connection c = connection();
      try {
        while (System.nanoTime() < end) {
          int[] pair = pair();
          int amount = ThreadLocalRandom.current().nextInt(1, 30);
          try {
            boolean moved =
                ThreadLocalRandom.current().nextBoolean()
                    ? readThenWrite(c, pair[0], pair[1], amount)
                    : inPlace(c, pair[0], pair[1], amount);
            if (moved) {
              c.commit();
              transfers.incrementAndGet();
            } else {
              c.rollback();
              refused.incrementAndGet();
            }
          } catch (SQLException ex) {
            fail("driver " + ex.getSQLState(), ex);
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

    /** Reads both balances, then sets them: the anchored writes of design section 4.3. */
    private boolean readThenWrite(Connection c, int from, int to, int amount) throws SQLException {
      int fromBalance = -1;
      int toBalance = -1;
      try (PreparedStatement ps =
          c.prepareStatement("SELECT id, balance FROM " + table + " WHERE id IN (?, ?)")) {
        ps.setInt(1, from);
        ps.setInt(2, to);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            if (rs.getInt(1) == from) {
              fromBalance = rs.getInt(2);
            } else {
              toBalance = rs.getInt(2);
            }
          }
        }
      }
      if (fromBalance < amount || toBalance < 0) {
        return false;
      }
      set(c, from, fromBalance - amount);
      set(c, to, toBalance + amount);
      return true;
    }

    private void set(Connection c, int id, int balance) throws SQLException {
      try (PreparedStatement ps =
          c.prepareStatement("UPDATE " + table + " SET balance = ? WHERE id = ?")) {
        ps.setInt(1, balance);
        ps.setInt(2, id);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
    }

    /** Moves the amount in the UPDATEs themselves, the funds checked in the WHERE. */
    private boolean inPlace(Connection c, int from, int to, int amount) throws SQLException {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE " + table + " SET balance = balance - ? WHERE id = ? AND balance >= ?")) {
        ps.setInt(1, amount);
        ps.setInt(2, from);
        ps.setInt(3, amount);
        if (ps.executeUpdate() == 0) {
          return false;
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement("UPDATE " + table + " SET balance = balance + ? WHERE id = ?")) {
        ps.setInt(1, amount);
        ps.setInt(2, to);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      return true;
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

    /** The three reads in one transaction, each checked against the totals. */
    private void read(Connection c) throws SQLException {
      List<String> off = new ArrayList<>();
      try (PreparedStatement ps = c.prepareStatement("SELECT SUM(balance) FROM " + table);
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        long sum = rs.getLong(1);
        if (sum != (long) ACCOUNTS * BALANCE) {
          off.add("SUM " + sum);
        }
      }
      try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT grp, SUM(balance) FROM " + table + " GROUP BY grp ORDER BY grp");
          ResultSet rs = ps.executeQuery()) {
        Map<Integer, Long> sums = new TreeMap<>();
        while (rs.next()) {
          sums.put(rs.getInt(1), rs.getLong(2));
        }
        if (!sums.equals(expectedGroups())) {
          off.add("GROUP BY " + sums);
        }
      }
      try (PreparedStatement ps =
              c.prepareStatement("SELECT id, grp, balance FROM " + table + " ORDER BY id");
          ResultSet rs = ps.executeQuery()) {
        Map<Integer, Long> sums = new TreeMap<>();
        int rows = 0;
        while (rs.next()) {
          rows++;
          sums.merge(rs.getInt(2), (long) rs.getInt(3), Long::sum);
          if (rs.getInt(3) < 0) {
            off.add("account " + rs.getInt(1) + " at " + rs.getInt(3));
          }
        }
        if (rows != ACCOUNTS || !sums.equals(expectedGroups())) {
          off.add("rows " + rows + " " + sums);
        }
      }
      if (!off.isEmpty()) {
        readViolationCount.incrementAndGet();
        if (readViolations.size() < KEPT) {
          readViolations.add(String.join("; ", off));
        }
      }
    }

    // ScalarDB's side

    private void scalarDbWriter(DistributedTransactionManager m, long end) {
      while (System.nanoTime() < end) {
        int[] pair = pair();
        int amount = ThreadLocalRandom.current().nextInt(1, 30);
        DistributedTransaction tx;
        try {
          tx = m.begin();
        } catch (TransactionException ex) {
          fail("ScalarDB begin " + ex.getClass().getSimpleName(), ex);
          continue;
        }
        try {
          int from = balance(tx, pair[0]);
          int to = balance(tx, pair[1]);
          if (from < amount) {
            tx.abort();
            refused.incrementAndGet();
            continue;
          }
          put(tx, pair[0], from - amount);
          put(tx, pair[1], to + amount);
          tx.commit();
          transfers.incrementAndGet();
          scalarDbTransfers.incrementAndGet();
        } catch (TransactionException ex) {
          fail("ScalarDB " + ex.getClass().getSimpleName(), ex);
          try {
            tx.abort();
          } catch (TransactionException ignored) {
            // lazy recovery takes it
          }
        }
      }
    }

    private int balance(DistributedTransaction tx, int id) throws TransactionException {
      Optional<Result> r =
          tx.get(
              Get.newBuilder()
                  .namespace(namespace)
                  .table(stored)
                  .partitionKey(Key.ofInt(e.storedColumn("id"), id))
                  .build());
      return r.orElseThrow().getInt(e.storedColumn("balance"));
    }

    private void put(DistributedTransaction tx, int id, int balance) throws TransactionException {
      tx.upsert(
          Upsert.newBuilder()
              .namespace(namespace)
              .table(stored)
              .partitionKey(Key.ofInt(e.storedColumn("id"), id))
              .intValue(e.storedColumn("balance"), balance)
              .build());
    }

    /**
     * Every group's total and every balance, read by a ScalarDB scan, which recovers the
     * transactions left in flight first; retried while it meets them.
     */
    private List<String> finalCheck(DistributedTransactionManager m) throws Exception {
      @Nullable Exception last = null;
      for (int attempt = 0; attempt < 30; attempt++) {
        DistributedTransaction tx = m.begin();
        try {
          List<Result> rows =
              tx.scan(Scan.newBuilder().namespace(namespace).table(stored).all().build());
          tx.commit();
          List<String> violations = new ArrayList<>();
          Map<Integer, Long> sums = new TreeMap<>();
          for (Result r : rows) {
            int balance = r.getInt(e.storedColumn("balance"));
            sums.merge(r.getInt(e.storedColumn("grp")), (long) balance, Long::sum);
            if (balance < 0) {
              violations.add("account " + r.getInt(e.storedColumn("id")) + " at " + balance);
            }
          }
          if (rows.size() != ACCOUNTS || !sums.equals(expectedGroups())) {
            violations.add(rows.size() + " accounts, group totals " + sums);
          }
          return violations;
        } catch (TransactionException ex) {
          last = ex;
          try {
            tx.abort();
          } catch (TransactionException ignored) {
            // lazy recovery takes it
          }
          Thread.sleep(1000);
        }
      }
      throw new IllegalStateException("the final scan kept failing", last);
    }

    // accounts

    /** Two accounts of one group. */
    private static int[] pair() {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      int group = r.nextInt(GROUPS);
      int perGroup = ACCOUNTS / GROUPS;
      int a = r.nextInt(perGroup);
      int b = (a + 1 + r.nextInt(perGroup - 1)) % perGroup;
      return new int[] {group + a * GROUPS, group + b * GROUPS};
    }

    private static Map<Integer, Long> expectedGroups() {
      Map<Integer, Long> sums = new TreeMap<>();
      for (int g = 0; g < GROUPS; g++) {
        sums.put(g, (long) (ACCOUNTS / GROUPS) * BALANCE);
      }
      return sums;
    }
  }
}
