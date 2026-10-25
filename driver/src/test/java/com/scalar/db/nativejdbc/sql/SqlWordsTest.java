package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SqlWordsTest {

  private static final List<String> NAMES = List.of("orders", "orders_data", "my table");

  @Test
  void find_aNameAsAWord_inAnyCaseOrQuoting() {
    assertThat(SqlWords.find("UPDATE shop.ORDERS SET x = 1", Engine.POSTGRESQL, NAMES))
        .isEqualTo("orders");
    assertThat(SqlWords.find("DELETE FROM \"Orders_Data\"", Engine.POSTGRESQL, NAMES))
        .isEqualTo("orders_data");
    assertThat(SqlWords.find("TRUNCATE `orders`", Engine.MYSQL, NAMES)).isEqualTo("orders");
    assertThat(SqlWords.find("DELETE FROM [orders]", Engine.SQLSERVER, NAMES)).isEqualTo("orders");
    // a name that is not one word, as a run of text
    assertThat(SqlWords.find("DELETE FROM \"my table\"", Engine.POSTGRESQL, NAMES))
        .isEqualTo("my table");
  }

  @Test
  void find_aNameInALiteral_asInDynamicSqlOrAFunctionBody() {
    assertThat(
            SqlWords.find(
                "DO $$ BEGIN EXECUTE 'DELETE FROM shop.orders'; END $$", Engine.POSTGRESQL, NAMES))
        .isEqualTo("orders");
    assertThat(SqlWords.find("EXEC('UPDATE orders SET x = 1')", Engine.SQLSERVER, NAMES))
        .isEqualTo("orders");
  }

  @Test
  void find_noName_inCommentsOrPartsOfWords() {
    assertThat(SqlWords.find("VACUUM plain -- orders\n", Engine.POSTGRESQL, NAMES)).isNull();
    assertThat(SqlWords.find("VACUUM plain /* orders */", Engine.POSTGRESQL, NAMES)).isNull();
    assertThat(SqlWords.find("OPTIMIZE TABLE plain # orders", Engine.MYSQL, NAMES)).isNull();
    assertThat(SqlWords.find("SELECT * FROM orders2, old_orders", Engine.POSTGRESQL, NAMES))
        .isNull();
  }

  @Test
  void find_aCommentMarkInALiteral_isNoComment() {
    assertThat(SqlWords.find("SELECT '--' FROM orders", Engine.POSTGRESQL, NAMES))
        .isEqualTo("orders");
    assertThat(SqlWords.find("SELECT 'it''s /*' FROM orders", Engine.POSTGRESQL, NAMES))
        .isEqualTo("orders");
    assertThat(SqlWords.find("SELECT 'a\\' -- ' FROM orders", Engine.MYSQL, NAMES))
        .isEqualTo("orders");
    // # starts no comment on PostgreSQL
    assertThat(SqlWords.find("SELECT 1 # 2, x FROM orders", Engine.POSTGRESQL, NAMES))
        .isEqualTo("orders");
  }

  private static @Nullable String why(String sql, Engine engine) {
    return SqlWords.whyNotAsWritten(sql, engine, NAMES);
  }

  @Test
  void whyNotAsWritten_aStatementOnPlainTables_isSent() {
    assertThat(why("SELECT * FROM plain ORDER BY x USING <", Engine.POSTGRESQL)).isNull();
    assertThat(why("/* a */ WITH a AS (SELECT 1) SELECT * FROM a;", Engine.POSTGRESQL)).isNull();
    assertThat(why("(SELECT 1) UNION (SELECT 2)", Engine.POSTGRESQL)).isNull();
    assertThat(why("DELETE FROM t1 USING t1 JOIN t2 ON t1.i = t2.i", Engine.POSTGRESQL)).isNull();
    assertThat(why("CREATE TABLE plain2 (i int)", Engine.POSTGRESQL)).isNull();
    assertThat(why("DECLARE c CURSOR FOR SELECT * FROM plain", Engine.POSTGRESQL)).isNull();
    assertThat(why("FETCH 2 FROM c", Engine.POSTGRESQL)).isNull();
    assertThat(why("REPLACE INTO plain VALUES (1)", Engine.MYSQL)).isNull();
    assertThat(why("UPDATE plain SET x = 1 RETURNING x INTO :x", Engine.ORACLE)).isNull();
    // a semicolon in a literal reads as two statements: refused, which errs the safe way
    assertThat(why("SELECT 'a;b' FROM plain", Engine.POSTGRESQL)).isNotNull();
  }

  @Test
  void whyNotAsWritten_aScalarDbName_anywhere() {
    assertThat(why("SELECT * FROM orders ORDER BY x USING <", Engine.POSTGRESQL))
        .contains("orders");
    assertThat(why("SELECT * FROM plain /* orders */", Engine.POSTGRESQL)).contains("orders");
    // a dollar-quoted string is no comment, whatever it holds
    assertThat(why("SELECT $$--$$, x FROM orders", Engine.POSTGRESQL)).contains("orders");
    assertThat(why("SELECT * FROM plain WHERE x = 'ORDERS'", Engine.POSTGRESQL)).contains("orders");
  }

  @Test
  void whyNotAsWritten_transactionAndSessionControlAndCode_areRefused() {
    for (String sql :
        List.of(
            "END",
            "COMMIT AND CHAIN",
            "RELEASE SAVEPOINT a",
            "SET TRANSACTION READ ONLY",
            "RESET SESSION AUTHORIZATION",
            "DO $$ BEGIN PERFORM 1; END $$",
            "CALL p()",
            "EXECUTE s",
            "PREPARE s AS SELECT 1",
            "LOCK TABLE plain",
            "",
            "-- only a comment")) {
      assertThat(why(sql, Engine.POSTGRESQL)).as(sql).startsWith("only a query, DML or DDL");
    }
    assertThat(why("DECLARE @x int", Engine.SQLSERVER)).startsWith("only a query, DML or DDL");
    assertThat(why("CREATE OR REPLACE FUNCTION f() RETURNS int", Engine.POSTGRESQL))
        .contains("FUNCTION");
    assertThat(why("CREATE RULE r AS ON INSERT TO plain DO ALSO NOTIFY x", Engine.POSTGRESQL))
        .contains("RULE");
    assertThat(why("CREATE DEFINER = u TRIGGER t BEFORE INSERT ON plain", Engine.MYSQL))
        .contains("TRIGGER");
    assertThat(why("ALTER SESSION SET x = 1", Engine.ORACLE)).contains("SESSION");
    assertThat(why("SELECT 1; COMMIT", Engine.POSTGRESQL)).contains("more than one statement");
  }

  @Test
  void whyNotAsWritten_aNameSpelledOtherwise_orSqlAsText() {
    assertThat(why("SELECT * FROM U&\"\\0061\" USING <", Engine.POSTGRESQL)).contains("U&");
    assertThat(why("SELECT * FROM ord\u200Bers USING <", Engine.POSTGRESQL)).contains("invisible");
    assertThat(why("SELECT 1 /*!, (SELECT 1) */ FROM plain", Engine.MYSQL)).contains("/*!");
    assertThat(why("SELECT 1 /*M!, 2 */ FROM plain", Engine.MARIADB)).contains("/*!");
    assertThat(why("SELECT query_to_xml('select 1', true, true, '') USING <", Engine.POSTGRESQL))
        .contains("query_to_xml");
    assertThat(why("SELECT set_config('a', 'b', true) USING <", Engine.POSTGRESQL))
        .contains("set_config");
  }

  @Test
  void startsDdl_byTheFirstWord() {
    assertThat(SqlWords.startsDdl("/* x */ create table t (i int)", Engine.POSTGRESQL)).isTrue();
    assertThat(SqlWords.startsDdl("COMMENT ON TABLE t IS 'x'", Engine.POSTGRESQL)).isTrue();
    assertThat(SqlWords.startsDdl("SELECT 1", Engine.POSTGRESQL)).isFalse();
  }
}
