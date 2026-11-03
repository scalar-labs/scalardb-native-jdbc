import com.oltpbenchmark.WorkloadConfiguration;
import com.oltpbenchmark.api.Procedure;
import com.oltpbenchmark.api.StatementDialects;
import com.oltpbenchmark.types.DatabaseType;
import com.oltpbenchmark.util.RandomGenerator;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * BenchBase's TPC-H queries through the vendor URL and the driver's, on the tables run (c) left:
 * each query with one seed's parameters, a warm-up on each side, then {@code reps} timed executions
 * alternating the two, in a transaction each and in autocommit, or for a query whose vendor
 * warm-up took longer than {@code long ms} the warm-ups alone; the rows of every execution
 * compared with the vendor's first, numbers within a relative 1e-9 (a parallel sum's order moves
 * its last digits). Q15, which creates a view, is left out as in the runs. Prints a Markdown table
 * of the medians.
 *
 * <p>Run by BenchBaseBenchmark with BenchBase's classpath: {@code java TpchCheck.java <vendor url>
 * <driver url> <user> <password> <POSTGRES|ORACLE> <scale> <reps> <vendor level> <driver level>
 * <timeout s> <long ms>}.
 */
public class TpchCheck {
  public static void main(String[] a) throws Exception {
    DatabaseType type = DatabaseType.valueOf(a[4]);
    double scale = Double.parseDouble(a[5]);
    int reps = Integer.parseInt(a[6]);
    int vendorLevel = Integer.parseInt(a[7]);
    int driverLevel = Integer.parseInt(a[8]);
    int timeout = Integer.parseInt(a[9]);
    long longNanos = Long.parseLong(a[10]) * 1_000_000L;
    WorkloadConfiguration w = new WorkloadConfiguration();
    w.setDatabaseType(type);
    w.setBenchmarkName("tpch");
    StatementDialects dialects = new StatementDialects(w);
    StringBuilder out = new StringBuilder();
    boolean allSame = true;
    try (Connection v = DriverManager.getConnection(a[0], a[2], a[3]);
        Connection d = DriverManager.getConnection(a[1], a[2], a[3])) {
      for (boolean tx : List.of(true, false)) {
        out.append("\n")
            .append(tx ? "In a transaction each" : "In autocommit")
            .append(", median of ")
            .append(reps)
            .append(" after a warm-up, or the warm-up alone (runs 1) past ")
            .append(a[10])
            .append(" ms:\n\n")
            .append("| query | rows | runs | vendor ms | driver ms | driver/vendor | same rows |\n")
            .append("|---|--:|--:|--:|--:|--:|---|\n");
        v.setAutoCommit(!tx);
        d.setAutoCommit(!tx);
        if (tx) {
          v.setTransactionIsolation(vendorLevel);
          d.setTransactionIsolation(driverLevel);
        }
        for (int q = 1; q <= 22; q++) {
          if (q == 15) {
            continue;
          }
          out.append("| Q").append(q).append(" | ");
          try {
            long[] warm = new long[1];
            List<List<Object>> expected = run(v, q, type, dialects, scale, timeout, warm);
            long vendorWarm = warm[0];
            boolean same = sameRows(expected, run(d, q, type, dialects, scale, timeout, warm));
            int n = vendorWarm > longNanos ? 1 : reps;
            long[] vt = {vendorWarm};
            long[] dt = {warm[0]};
            if (n > 1) {
              vt = new long[n];
              dt = new long[n];
            }
            for (int i = 0; n > 1 && i < n; i++) {
              long[] t = new long[1];
              same &= sameRows(expected, run(v, q, type, dialects, scale, timeout, t));
              vt[i] = t[0];
              same &= sameRows(expected, run(d, q, type, dialects, scale, timeout, t));
              dt[i] = t[0];
            }
            allSame &= same;
            double vm = median(vt) / 1e6;
            double dm = median(dt) / 1e6;
            out.append(expected.size())
                .append(" | ")
                .append(n)
                .append(" | ")
                .append(String.format("%.1f | %.1f | %.2fx | ", vm, dm, dm / vm))
                .append(same ? "yes" : "**no**")
                .append(" |\n");
          } catch (Exception e) {
            allSame = false;
            Throwable c = e.getCause() != null ? e.getCause() : e;
            out.append("| | | | | **")
                .append(String.valueOf(c.getMessage()).replace('\n', ' ').replace('|', '/'))
                .append("** |\n");
            for (Connection c2 : List.of(v, d)) {
              if (!c2.getAutoCommit()) {
                c2.rollback();
              }
            }
          }
        }
      }
    }
    System.out.print(out);
    System.out.println(allSame ? "\nEvery execution returned the vendor's rows." : "");
  }

  /** One execution, in a transaction of its own outside autocommit; its time in {@code took}. */
  private static List<List<Object>> run(
      Connection c,
      int q,
      DatabaseType type,
      StatementDialects dialects,
      double scale,
      int timeout,
      long[] took)
      throws Exception {
    Class<?> k = Class.forName("com.oltpbenchmark.benchmarks.tpch.procedures.Q" + q);
    Procedure p = (Procedure) k.getDeclaredConstructor().newInstance();
    Method init = Procedure.class.getDeclaredMethod("initialize", DatabaseType.class);
    init.setAccessible(true);
    init.invoke(p, type);
    Method dialect = Procedure.class.getDeclaredMethod("loadSQLDialect", StatementDialects.class);
    dialect.setAccessible(true);
    dialect.invoke(p, dialects);
    Method get =
        k.getDeclaredMethod(
            "getStatement", Connection.class, RandomGenerator.class, double.class);
    get.setAccessible(true);
    List<List<Object>> rows = new ArrayList<>();
    long start = System.nanoTime();
    try (PreparedStatement s =
        (PreparedStatement) get.invoke(p, c, new RandomGenerator(q), scale)) {
      s.setQueryTimeout(timeout);
      try (ResultSet rs = s.executeQuery()) {
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
          List<Object> row = new ArrayList<>(n);
          for (int i = 1; i <= n; i++) {
            row.add(rs.getObject(i));
          }
          rows.add(row);
        }
      }
    }
    if (!c.getAutoCommit()) {
      c.commit();
    }
    if (took != null) {
      took[0] = System.nanoTime() - start;
    }
    return rows;
  }

  private static boolean sameRows(List<List<Object>> a, List<List<Object>> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int r = 0; r < a.size(); r++) {
      List<Object> x = a.get(r);
      List<Object> y = b.get(r);
      if (x.size() != y.size()) {
        return false;
      }
      for (int i = 0; i < x.size(); i++) {
        Object p = x.get(i);
        Object q = y.get(i);
        if (p instanceof Number && q instanceof Number) {
          double u = ((Number) p).doubleValue();
          double w = ((Number) q).doubleValue();
          if (Math.abs(u - w) > 1e-9 * Math.max(1, Math.max(Math.abs(u), Math.abs(w)))) {
            return false;
          }
        } else if (!Objects.equals(p, q)) {
          return false;
        }
      }
    }
    return true;
  }

  private static double median(long[] t) {
    long[] s = t.clone();
    Arrays.sort(s);
    return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2.0;
  }
}
