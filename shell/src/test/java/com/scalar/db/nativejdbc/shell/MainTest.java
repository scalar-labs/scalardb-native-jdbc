package com.scalar.db.nativejdbc.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class MainTest {

  private static final String P = "jdbc:scalardb-native:";

  /** {@code \c dbname} swaps the database in the URL, per engine. */
  @Test
  void withDatabase() {
    assertThat(Main.withDatabase(P + "postgresql://h:5432/a?ssl=true", "b"))
        .isEqualTo(P + "postgresql://h:5432/b?ssl=true");
    assertThat(Main.withDatabase(P + "postgresql://h:5432", "b"))
        .isEqualTo(P + "postgresql://h:5432/b");
    assertThat(Main.withDatabase(P + "mysql://h/a?permitMysqlScheme=true", "b"))
        .isEqualTo(P + "mysql://h/b?permitMysqlScheme=true");
    assertThat(Main.withDatabase(P + "sqlserver://h:1433;databaseName=a;encrypt=false", "b"))
        .isEqualTo(P + "sqlserver://h:1433;databaseName=b;encrypt=false");
    assertThat(Main.withDatabase(P + "sqlserver://h:1433", "b"))
        .isEqualTo(P + "sqlserver://h:1433;databaseName=b");
    assertThat(Main.withDatabase(P + "oracle:thin:@//h:1521/a", "b"))
        .isEqualTo(P + "oracle:thin:@//h:1521/b");
    assertThat(Main.withDatabase(P + "oracle:thin:@h:1521:a", "b"))
        .isEqualTo(P + "oracle:thin:@h:1521:b");
  }

  /** Only a name: anything else would change the URL beyond its database. */
  @Test
  void withDatabase_takesANameOnly() {
    for (String bad :
        new String[] {"db?socketFactory=x", "db;serverName=evil", "db/x", "x@evil", "a b", ""}) {
      assertThatThrownBy(() -> Main.withDatabase(P + "sqlserver://h:1433", bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(Main.withDatabase(P + "postgresql://h/a", "日本_db-1")).endsWith("/日本_db-1");
  }

  /** The unit tests run without vendor drivers, as a zip whose lib/ lost one. */
  @Test
  void aMissingVendorDriver_isNamed() {
    StringWriter err = new StringWriter();
    int exit =
        Main.run(
            new PrintWriter(new StringWriter(), true),
            new PrintWriter(err, true),
            P + "sqlserver://localhost:1433");

    assertThat(exit).isEqualTo(2);
    assertThat(err.toString())
        .contains("no JDBC driver for jdbc:sqlserver:")
        .contains("mssql-jdbc");
  }
}
