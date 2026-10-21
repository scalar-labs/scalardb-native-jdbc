package com.scalar.db.nativejdbc.coverage;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Upsert;
import com.scalar.db.exception.transaction.PreparationConflictException;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.BooleanColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.DateColumn;
import com.scalar.db.io.DoubleColumn;
import com.scalar.db.io.FloatColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import com.scalar.db.io.TimestampColumn;
import com.scalar.db.io.TimestampTZColumn;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.testing.Engines;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Milestone T2's ScalarDB transactions: over a share of the keys of every imported table of both
 * layouts, updates, deletes and inserts of new keys, spread over several transactions that each
 * reach several tables, and left prepared. The plain schema is not touched, so it stays the
 * committed image of every ScalarDB table.
 */
final class InFlight {

  /** Transactions per layout, so the rows of one table are held by several. */
  private static final int TRANSACTIONS = 3;

  private static final List<String> NAMES = List.of("ann", "bob", "zed", "ann ", "Bob");

  private InFlight() {}

  /**
   * Prepares the transactions and adds them to {@code held}, which the caller rolls back.
   *
   * @param share the share of each table's keys to put in flight, and a fifth of it as new keys
   * @param keys filled with the keys held, by {@code <schema>.<table>} as the corpus names them,
   *     each as {@link WriteCorpusIntegrationTest#normal} spells its values
   */
  static void prepare(
      Engines e,
      Catalog catalog,
      TwoPhaseCommitTransactionManager tpc,
      String set,
      double share,
      Random random,
      List<TwoPhaseCommitTransaction> held,
      Map<String, Set<List<String>>> keys)
      throws Exception {
    try (DistributedTransactionAdmin admin = e.admin()) {
      for (String layout : List.of("d", "i")) {
        String ns = e.storedSchema(set + layout);
        List<TwoPhaseCommitTransaction> txs = new ArrayList<>();
        Map<TwoPhaseCommitTransaction, Map<String, Set<List<String>>>> byTx = new HashMap<>();
        for (int i = 0; i < TRANSACTIONS; i++) {
          TwoPhaseCommitTransaction tx = tpc.begin();
          held.add(tx);
          txs.add(tx);
        }
        for (String t : List.of("c", "o", "k")) {
          String table = e.storedTable(t);
          String scalarDb =
              layout.equals("d")
                  ? Objects.requireNonNull(catalog.lookup(List.of(), ns, table).virtualTable())
                  : table;
          TableMetadata md = Objects.requireNonNull(admin.getTableMetadata(ns, scalarDb));
          if (!md.getClusteringKeyNames().isEmpty()) {
            throw new IllegalStateException(table + ": the corpus's tables have no clustering key");
          }
          List<String> keyNames = new ArrayList<>(md.getPartitionKeyNames());
          List<String> values = new ArrayList<>();
          for (String c : md.getColumnNames()) {
            // ScalarDB's metadata lists the transaction columns too; the corpus's names none so
            if (!keyNames.contains(c) && !c.startsWith("tx_") && !c.startsWith("before_")) {
              values.add(c);
            }
          }
          int fresh = 0;
          List<Result> rows = committed(e, ns, scalarDb);
          boolean any = false;
          for (int r = 0; r < rows.size(); r++) {
            Result row = rows.get(r);
            // at least the last row, so every table holds some
            if (random.nextDouble() >= share && (any || r < rows.size() - 1)) {
              continue;
            }
            any = true;
            TwoPhaseCommitTransaction tx = txs.get(random.nextInt(txs.size()));
            Set<List<String>> mine =
                byTx.computeIfAbsent(tx, x -> new HashMap<>())
                    .computeIfAbsent(set + layout + "." + t, x -> new HashSet<>());
            mine.add(spelled(row, keyNames));
            Key partition = key(row, md.getPartitionKeyNames());
            if (random.nextInt(10) < 6) {
              var u = Upsert.newBuilder().namespace(ns).table(scalarDb).partitionKey(partition);
              for (String c : values) {
                if (random.nextBoolean()) {
                  u.value(value(c, md.getColumnDataType(c), random));
                }
              }
              tx.upsert(u.build());
            } else {
              tx.delete(
                  Delete.newBuilder()
                      .namespace(ns)
                      .table(scalarDb)
                      .partitionKey(partition)
                      .build());
            }
            if (random.nextInt(5) == 0) {
              // a new key: the row's own with the first key column moved out of every key's way
              fresh++;
              Key.Builder p = Key.newBuilder();
              for (String k : md.getPartitionKeyNames()) {
                p.add(keyNames.get(0).equals(k) ? moved(col(row, k), fresh) : col(row, k));
              }
              Key inserted = p.build();
              List<String> spelled = new ArrayList<>();
              for (Column<?> c : inserted.getColumns()) {
                spelled.add(WriteCorpusIntegrationTest.normal(c.getValueAsObject()));
              }
              mine.add(spelled);
              var ins = Insert.newBuilder().namespace(ns).table(scalarDb).partitionKey(inserted);
              for (String c : values) {
                ins.value(value(c, md.getColumnDataType(c), random));
              }
              tx.insert(ins.build());
            }
          }
        }
        for (TwoPhaseCommitTransaction tx : txs) {
          try {
            tx.prepare();
            byTx.getOrDefault(tx, Map.of())
                .forEach((k, v) -> keys.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
          } catch (PreparationConflictException ex) {
            // Oracle's SERIALIZABLE refuses a prepare for a block another changed (ORA-08177):
            // fewer rows in flight, which the caller's count still sees
            tx.rollback();
            held.remove(tx);
          }
        }
      }
    }
  }

  /** Every committed row of a table, read in a transaction of its own. */
  private static List<Result> committed(Engines e, String ns, String table) throws Exception {
    try (var tm = e.scalarDb().getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      List<Result> rows = tx.scan(Scan.newBuilder().namespace(ns).table(table).all().build());
      tx.commit();
      return rows;
    }
  }

  /** A row's key values as the write corpus spells them. */
  private static List<String> spelled(Result row, List<String> names) {
    List<String> out = new ArrayList<>();
    for (String n : names) {
      out.add(WriteCorpusIntegrationTest.normal(col(row, n).getValueAsObject()));
    }
    return out;
  }

  private static Key key(Result row, Iterable<String> names) {
    Key.Builder b = Key.newBuilder();
    for (String n : names) {
      b.add(col(row, n));
    }
    return b.build();
  }

  private static Column<?> col(Result row, String name) {
    return Objects.requireNonNull(row.getColumns().get(name), name);
  }

  /** An integer key moved past every existing one. */
  private static Column<?> moved(Column<?> c, int n) {
    return c instanceof BigIntColumn
        ? BigIntColumn.of(c.getName(), 1_000_000L + n)
        : IntColumn.of(c.getName(), 1_000_000 + n);
  }

  /**
   * A value of the column's type, NULL one time in five, small enough for every engine's column: an
   * Oracle {@code NUMBER(1)} holds 0 to 9, a {@code VARCHAR(20)} the names.
   */
  private static Column<?> value(String name, DataType type, Random r) {
    boolean isNull = r.nextInt(5) == 0;
    return switch (type) {
      case INT -> isNull ? IntColumn.ofNull(name) : IntColumn.of(name, r.nextInt(10));
      case BIGINT -> isNull ? BigIntColumn.ofNull(name) : BigIntColumn.of(name, r.nextInt(10));
      case FLOAT -> isNull ? FloatColumn.ofNull(name) : FloatColumn.of(name, r.nextInt(16) * 0.25f);
      case DOUBLE ->
          isNull ? DoubleColumn.ofNull(name) : DoubleColumn.of(name, r.nextInt(16) * 0.25);
      case BOOLEAN -> isNull ? BooleanColumn.ofNull(name) : BooleanColumn.of(name, r.nextBoolean());
      case TEXT ->
          isNull
              ? TextColumn.ofNull(name)
              : TextColumn.of(name, NAMES.get(r.nextInt(NAMES.size())));
      case DATE ->
          isNull
              ? DateColumn.ofNull(name)
              : DateColumn.of(name, LocalDate.of(1970 + r.nextInt(60), 1 + r.nextInt(12), 1));
      case TIMESTAMP ->
          isNull
              ? TimestampColumn.ofNull(name)
              : TimestampColumn.of(
                  name,
                  LocalDateTime.of(2025 + r.nextInt(2), 1 + r.nextInt(12), 1, r.nextInt(24), 0));
      case TIMESTAMPTZ ->
          isNull
              ? TimestampTZColumn.ofNull(name)
              : TimestampTZColumn.of(
                  name,
                  LocalDateTime.of(2025 + r.nextInt(2), 1 + r.nextInt(12), 1, r.nextInt(24), 0)
                      .toInstant(ZoneOffset.UTC));
      default ->
          throw new IllegalArgumentException(name + " has a type the corpus does not use: " + type);
    };
  }
}
