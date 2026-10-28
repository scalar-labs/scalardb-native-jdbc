package com.scalar.db.nativejdbc.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The expressions a read takes a row in flight's columns by (todos 303, 356). */
class TableInfoTest {

  /** A decoupled table keyed by k, with v, each listed with the type to cast it to. */
  private static TableInfo table(String type) {
    return new TableInfo(
        "ns",
        "t",
        Layout.DECOUPLED_IMPORTED,
        "t",
        "t_tx_metadata",
        "t_scalardb",
        List.of("k"),
        List.of(),
        List.of("k", "v"),
        List.of(),
        List.of(),
        List.of(),
        Map.of("k", type, "v", type),
        true);
  }

  @Test
  void metadataKey_isCastOnPostgresqlAndSqlServer_only() {
    assertThat(table("SMALLINT)").metadataKey(Engine.POSTGRESQL, "m", "k"))
        .isEqualTo("CAST(m.\"k\" AS SMALLINT)");
    assertThat(table("SMALLINT)").metadataKey(Engine.SQLSERVER, "m", "k"))
        .isEqualTo("CAST(m.[k] AS SMALLINT)");
    // a cast key loses the metadata table's key index: MySQL's VARCHAR key stays as it is
    String varchar = "CHAR(20) CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci";
    assertThat(table(varchar).metadataKey(Engine.MYSQL, "m", "k")).isEqualTo("m.`k`");
    assertThat(table(varchar).metadataKey(Engine.MARIADB, "m", "k")).isEqualTo("m.`k`");
  }

  @Test
  void beforeImage_isCastWhereATypeIsListed() {
    assertThat(table("SMALLINT)").beforeImage(Engine.POSTGRESQL, "m", "v"))
        .isEqualTo("CAST(m.\"before_v\" AS SMALLINT)");
    String varchar = "CHAR(20) CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci";
    assertThat(table(varchar).beforeImage(Engine.MYSQL, "m", "v"))
        .isEqualTo("CAST(m.`before_v` AS " + varchar);
  }
}
