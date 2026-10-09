package com.scalar.db.nativejdbc.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class EngineTest {

  @Test
  void of_selectsBySubProtocol_inAnyCase() {
    assertThat(Engine.of("postgresql")).isEqualTo(Engine.POSTGRESQL);
    assertThat(Engine.of("MariaDB")).isEqualTo(Engine.MARIADB);
    assertThat(Engine.of("sqlserver")).isEqualTo(Engine.SQLSERVER);
    assertThatThrownBy(() -> Engine.of("sqlite")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unquotedIdentifiers_foldTheWayTheEngineFolds() {
    assertThat(Engine.POSTGRESQL.resolveIdentifier("Orders")).isEqualTo("orders");
    assertThat(Engine.ORACLE.resolveIdentifier("Orders")).isEqualTo("ORDERS");
    assertThat(Engine.MYSQL.resolveIdentifier("Orders")).isEqualTo("Orders");
    assertThat(Engine.MARIADB.resolveIdentifier("Orders")).isEqualTo("Orders");
    assertThat(Engine.SQLSERVER.resolveIdentifier("Orders")).isEqualTo("Orders");
  }

  @Test
  void nonAsciiNames_foldAsTheEnginesDo() {
    // PostgreSQL folds A-Z only
    assertThat(Engine.POSTGRESQL.resolveIdentifier("Ärger")).isEqualTo("Ärger");
    assertThat(Engine.POSTGRESQL.resolveIdentifier("ÄrgerX")).isEqualTo("Ärgerx");
    // Oracle: a non-ASCII unquoted name is left to the server; Java never expands ß into SS
    assertThat(Engine.ORACLE.needsServerFold("größe")).isTrue();
    assertThat(Engine.ORACLE.needsServerFold("\"größe\"")).isFalse();
    assertThat(Engine.ORACLE.needsServerFold("orders")).isFalse();
    assertThat(Engine.POSTGRESQL.needsServerFold("größe")).isFalse();
    assertThat(Engine.ORACLE.resolveIdentifier("straße")).isEqualTo("STRAßE");
  }

  @Test
  void doubleQuotes_quoteIdentifiersByDefault_exceptOnMySqlAndMariaDb() {
    assertThat(Engine.POSTGRESQL.doubleQuotesQuoteIdentifiersByDefault()).isTrue();
    assertThat(Engine.ORACLE.doubleQuotesQuoteIdentifiersByDefault()).isTrue();
    assertThat(Engine.SQLSERVER.doubleQuotesQuoteIdentifiersByDefault()).isTrue();
    assertThat(Engine.MYSQL.doubleQuotesQuoteIdentifiersByDefault()).isFalse();
    assertThat(Engine.MARIADB.doubleQuotesQuoteIdentifiersByDefault()).isFalse();
  }

  @Test
  void quotedIdentifiers_areKept_inAnyQuotingStyle() {
    for (Engine e : Engine.values()) {
      assertThat(e.resolveIdentifier("\"Orders\"")).isEqualTo("Orders");
      assertThat(e.resolveIdentifier("`Orders`")).isEqualTo("Orders");
      assertThat(e.resolveIdentifier("[Orders]")).isEqualTo("Orders");
      assertThat(e.resolveIdentifier("\"a\"\"b\"")).isEqualTo("a\"b");
      assertThat(e.resolveIdentifier("[a]]b]")).isEqualTo("a]b");
    }
  }

  @Test
  void quote_usesTheEngineDelimiters_andDoublesAnEmbeddedOne() {
    assertThat(Engine.POSTGRESQL.quote("full_table_name")).isEqualTo("\"full_table_name\"");
    assertThat(Engine.ORACLE.quote("a\"b")).isEqualTo("\"a\"\"b\"");
    assertThat(Engine.MYSQL.quote("x")).isEqualTo("`x`");
    assertThat(Engine.SQLSERVER.quote("a]b")).isEqualTo("[a]]b]");
  }

  @Test
  void columnsCompare_exactlyOnPostgresqlAndOracle_withoutCaseElsewhere() {
    assertThat(Engine.POSTGRESQL.columnKey("Amount"))
        .isNotEqualTo(Engine.POSTGRESQL.columnKey("amount"));
    assertThat(Engine.ORACLE.columnKey("AMOUNT")).isNotEqualTo(Engine.ORACLE.columnKey("amount"));
    assertThat(Engine.MYSQL.columnKey("Amount")).isEqualTo(Engine.MYSQL.columnKey("amount"));
    assertThat(Engine.MARIADB.columnKey("Amount")).isEqualTo(Engine.MARIADB.columnKey("AMOUNT"));
    assertThat(Engine.SQLSERVER.columnKey("Amount"))
        .isEqualTo(Engine.SQLSERVER.columnKey("amount"));
  }

  @Test
  void namesLocalToAStatement_compareWithoutCaseOnSqlServerOnly() {
    assertThat(Engine.SQLSERVER.nameKey("Orders")).isEqualTo(Engine.SQLSERVER.nameKey("orders"));
    for (Engine e : new Engine[] {Engine.POSTGRESQL, Engine.MYSQL, Engine.MARIADB, Engine.ORACLE}) {
      assertThat(e.nameKey("Orders")).isNotEqualTo(e.nameKey("orders"));
    }
  }

  @Test
  void isMissingTable_readsEachEnginesOwnSignal() {
    assertThat(Engine.POSTGRESQL.isMissingTable(new SQLException("x", "42P01"))).isTrue();
    assertThat(Engine.MYSQL.isMissingTable(new SQLException("x", "42S02", 1146))).isTrue();
    assertThat(Engine.MARIADB.isMissingTable(new SQLException("x", "42000", 1049))).isTrue();
    assertThat(Engine.SQLSERVER.isMissingTable(new SQLException("x", "S0002", 208))).isTrue();
    assertThat(Engine.ORACLE.isMissingTable(new SQLException("x", "42000", 942))).isTrue();
    assertThat(Engine.ORACLE.isMissingTable(new SQLException("x", "42000", 904))).isFalse();
    assertThat(Engine.POSTGRESQL.isMissingTable(new SQLException("x", "42501"))).isFalse();
  }
}
