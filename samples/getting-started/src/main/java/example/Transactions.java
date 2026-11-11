package example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Runs a unit of work as one transaction, retrying it when it conflicts with another. */
final class Transactions {

  interface Work {
    void run(Connection c) throws SQLException;
  }

  private Transactions() {}

  static void run(Connection c, Work work) throws SQLException {
    for (int attempt = 1; ; attempt++) {
      c.setAutoCommit(false);
      try {
        work.run(c);
        c.commit();
        return;
      } catch (SQLException e) {
        c.rollback();
        // 40001: a conflict with another transaction, local or global; retry
        if (!"40001".equals(e.getSQLState()) || attempt == 3) {
          throw e;
        }
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  /** Runs an update that must change exactly one row. */
  static void updateOne(Connection c, String sql, Object... params) throws SQLException {
    if (update(c, sql, params) != 1) {
      throw new SQLException("no row for " + params[params.length - 1]);
    }
  }

  static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      return ps.executeUpdate();
    }
  }

  /** Reads one int, such as a balance. */
  static int queryInt(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new SQLException("no row for " + params[params.length - 1]);
        }
        return rs.getInt(1);
      }
    }
  }
}
