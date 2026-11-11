package example;

import static example.Transactions.queryInt;
import static example.Transactions.update;
import static example.Transactions.updateOne;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The EC system's application: customers top up a prepaid wallet and buy with it, in local
 * transactions on the EC database, or buy with points, in a global transaction across the EC and
 * point databases.
 */
public final class EcApp {

  /** The EC database, through ScalarDB Native JDBC: local transactions. */
  private static final String LOCAL = "jdbc:scalardb-native:postgresql://localhost:5432/postgres";

  /** ScalarDB's PostgreSQL frontend over both databases: global transactions. */
  private static final String GLOBAL = "jdbc:postgresql://localhost:15432/ec";

  public static void main(String[] args) throws SQLException {
    String usage =
        "usage: topup <customer> <amount> | buy <customer> <item> <price>"
            + " | buy-with-points <customer> <item> <price> <points>";
    if (args.length < 3) {
      throw new IllegalArgumentException(usage);
    }
    try {
      run(args, usage);
    } catch (SQLException e) {
      // the transaction rolled back: nothing it wrote is kept
      System.out.println("rolled back: " + e.getMessage());
    }
  }

  private static void run(String[] args, String usage) throws SQLException {
    int customer = Integer.parseInt(args[1]);
    switch (args[0]) {
      case "topup":
        try (Connection c = local()) {
          int amount = Integer.parseInt(args[2]);
          Transactions.run(c, tx -> topUp(tx, customer, amount));
          printWallet(c, customer);
        }
        break;
      case "buy":
        try (Connection c = local()) {
          String item = args[2];
          int price = Integer.parseInt(args[3]);
          Transactions.run(c, tx -> buy(tx, customer, item, price));
          printWallet(c, customer);
        }
        break;
      case "buy-with-points":
        try (Connection c = DriverManager.getConnection(GLOBAL, "postgres", "postgres")) {
          String item = args[2];
          int price = Integer.parseInt(args[3]);
          int points = Integer.parseInt(args[4]);
          Transactions.run(c, tx -> buyWithPoints(tx, customer, item, price, points));
          printWallet(c, customer);
          System.out.printf(
              "points of customer %d: %d%n",
              customer,
              queryInt(c, "SELECT points FROM point.point_account WHERE customer_id = ?", customer));
        }
        break;
      default:
        throw new IllegalArgumentException(usage);
    }
  }

  private static Connection local() throws SQLException {
    Connection c = DriverManager.getConnection(LOCAL, "postgres", "postgres");
    // SNAPSHOT, the level of ScalarDB's transactions (ScalarDB's default)
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    return c;
  }

  /** A local transaction: the wallet and its ordinary history table. */
  static void topUp(Connection c, int customer, int amount) throws SQLException {
    updateOne(c, "UPDATE ec.wallet SET balance = balance + ? WHERE customer_id = ?", amount, customer);
    update(c, "INSERT INTO ec.topup_history (customer_id, amount) VALUES (?, ?)", customer, amount);
  }

  /** A local transaction: pay the whole price from the wallet. */
  static void buy(Connection c, int customer, String item, int price) throws SQLException {
    payMoney(c, customer, price);
    insertOrder(c, customer, item, price, price, 0);
  }

  /** A global transaction: pay part of the price with points, the rest from the wallet. */
  static void buyWithPoints(Connection c, int customer, String item, int price, int points)
      throws SQLException {
    String orderId = insertOrder(c, customer, item, price, price - points, points);
    payPoints(c, customer, points, orderId);
    payMoney(c, customer, price - points);
  }

  private static void payMoney(Connection c, int customer, int amount) throws SQLException {
    int balance = queryInt(c, "SELECT balance FROM ec.wallet WHERE customer_id = ?", customer);
    if (balance < amount) {
      throw new SQLException("not enough money: " + balance + " < " + amount);
    }
    updateOne(c, "UPDATE ec.wallet SET balance = balance - ? WHERE customer_id = ?", amount, customer);
  }

  private static void payPoints(Connection c, int customer, int points, String orderId)
      throws SQLException {
    int balance =
        queryInt(c, "SELECT points FROM point.point_account WHERE customer_id = ?", customer);
    if (balance < points) {
      throw new SQLException("not enough points: " + balance + " < " + points);
    }
    updateOne(
        c,
        "UPDATE point.point_account SET points = points - ? WHERE customer_id = ?",
        points,
        customer);
    update(
        c,
        "INSERT INTO point.point_history (history_id, customer_id, delta, reason) VALUES (?, ?, ?, ?)",
        UUID.randomUUID().toString(),
        customer,
        -points,
        "order " + orderId);
  }

  private static String insertOrder(
      Connection c, int customer, String item, int price, int paidMoney, int paidPoints)
      throws SQLException {
    String orderId = UUID.randomUUID().toString();
    update(
        c,
        "INSERT INTO ec.orders (order_id, customer_id, item, price, paid_money, paid_points)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        orderId,
        customer,
        item,
        price,
        paidMoney,
        paidPoints);
    return orderId;
  }

  private static void printWallet(Connection c, int customer) throws SQLException {
    System.out.printf(
        "wallet of customer %d: %d%n",
        customer,
        queryInt(c, "SELECT balance FROM ec.wallet WHERE customer_id = ?", customer));
  }
}
