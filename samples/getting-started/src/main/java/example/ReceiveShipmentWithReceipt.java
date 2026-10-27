package example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@link ReceiveShipment}, and records each item of the shipment in the ordinary table {@code
 * warehouse.receipt}, in the same transaction.
 */
public final class ReceiveShipmentWithReceipt {

  private static final String URL = "jdbc:scalardb-native:postgresql://localhost:5432/postgres";

  public static void main(String[] args) throws SQLException {
    try (Connection c = DriverManager.getConnection(URL, "postgres", "postgres")) {
      // SNAPSHOT, the level the order service's ScalarDB transactions run at (ScalarDB's default):
      // the driver and ScalarDB run at the same level
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      // 30 bolts and 20 nuts
      receive(c, new int[] {1, 2}, new int[] {30, 20});
      try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT item_id, name, quantity FROM warehouse.stock ORDER BY item_id");
          ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          System.out.printf("%d %s %d%n", rs.getInt(1), rs.getString(2), rs.getInt(3));
        }
      }
    }
  }

  static void receive(Connection c, int[] items, int[] quantities) throws SQLException {
    for (int attempt = 1; ; attempt++) {
      c.setAutoCommit(false);
      try {
        for (int i = 0; i < items.length; i++) {
          addStock(c, items[i], quantities[i]);
          recordReceipt(c, items[i], quantities[i]);
        }
        c.commit();
        return;
      } catch (SQLException e) {
        c.rollback();
        // 40001: a conflict with another transaction, a ScalarDB transaction included; retry
        if (!"40001".equals(e.getSQLState()) || attempt == 3) {
          throw e;
        }
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  private static void addStock(Connection c, int item, int quantity) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE warehouse.stock SET quantity = quantity + ? WHERE item_id = ?")) {
      ps.setInt(1, quantity);
      ps.setInt(2, item);
      if (ps.executeUpdate() != 1) {
        throw new SQLException("no item " + item);
      }
    }
  }

  private static void recordReceipt(Connection c, int item, int quantity) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("INSERT INTO warehouse.receipt (item_id, quantity) VALUES (?, ?)")) {
      ps.setInt(1, item);
      ps.setInt(2, quantity);
      ps.executeUpdate();
    }
  }
}
