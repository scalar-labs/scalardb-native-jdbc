package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.List;
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
}
