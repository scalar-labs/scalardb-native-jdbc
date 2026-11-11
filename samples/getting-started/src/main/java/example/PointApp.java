package example;

import static example.Transactions.queryInt;
import static example.Transactions.update;
import static example.Transactions.updateOne;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** The point system's application: customers earn points. Local transactions on the point database. */
public final class PointApp {

  private static final String URL = "jdbc:scalardb-native:postgresql://localhost:5433/postgres";

  public static void main(String[] args) throws SQLException {
    if (args.length != 4 || !args[0].equals("earn")) {
      throw new IllegalArgumentException("usage: earn <customer> <points> <reason>");
    }
    int customer = Integer.parseInt(args[1]);
    int points = Integer.parseInt(args[2]);
    String reason = args[3];
    try (Connection c = DriverManager.getConnection(URL, "postgres", "postgres")) {
      // SNAPSHOT, the level of ScalarDB's transactions (ScalarDB's default)
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      Transactions.run(c, tx -> earn(tx, customer, points, reason));
      System.out.printf(
          "points of customer %d: %d%n",
          customer,
          queryInt(c, "SELECT points FROM point.point_account WHERE customer_id = ?", customer));
    }
  }

  static void earn(Connection c, int customer, int points, String reason) throws SQLException {
    updateOne(
        c,
        "UPDATE point.point_account SET points = points + ? WHERE customer_id = ?",
        points,
        customer);
    update(
        c,
        "INSERT INTO point.point_history (history_id, customer_id, delta, reason) VALUES (?, ?, ?, ?)",
        UUID.randomUUID().toString(),
        customer,
        points,
        reason);
  }
}
