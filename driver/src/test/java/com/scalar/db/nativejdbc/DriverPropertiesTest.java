package com.scalar.db.nativejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DriverPropertiesTest {

  @Test
  void defaults() throws SQLException {
    DriverProperties p = DriverProperties.read(Map.of(), null);
    assertThat(p.metadataSchema).isEqualTo("scalardb");
    assertThat(p.coordinatorNamespace).isEqualTo("coordinator");
    assertThat(p.catalogTtl).isEqualTo(Duration.ofSeconds(60));
    assertThat(p.allowNoCatalog).isFalse();
    assertThat(p.inflightWait).isEqualTo(Duration.ZERO);
  }

  @Test
  void readsProperties_andTheUrlWinsForTheSettingsItMaySet() throws SQLException {
    Properties info = new Properties();
    info.setProperty("scalar.db.native_jdbc.metadata_schema", "meta");
    info.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "5000");
    info.setProperty("scalar.db.native_jdbc.allow_no_catalog", "TRUE");
    info.setProperty("scalar.db.native_jdbc.coordinator_namespace", "coord");
    DriverProperties p =
        DriverProperties.read(Map.of("scalar.db.native_jdbc.catalog_ttl_millis", "7000"), info);
    assertThat(p.metadataSchema).isEqualTo("meta");
    assertThat(p.catalogTtl).isEqualTo(Duration.ofSeconds(7));
    assertThat(p.allowNoCatalog).isTrue();
    assertThat(p.coordinatorNamespace).isEqualTo("coord");
  }

  @Test
  void settingsThatCanTurnTheProtectionOff_areRefusedFromTheUrl() {
    for (String key :
        new String[] {
          "scalar.db.native_jdbc.metadata_schema",
          "scalar.db.native_jdbc.coordinator_namespace",
          "scalar.db.native_jdbc.allow_no_catalog",
          "scalar.db.native_jdbc.passthrough"
        }) {
      assertThatThrownBy(() -> DriverProperties.read(Map.of(key, "x"), null))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("08001");
    }
  }

  @Test
  void theTtlIsCapped_andTheSchemaMustBeAnIdentifier() {
    Properties ttl = new Properties();
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "9223372036854775807");
    assertThatThrownBy(() -> DriverProperties.read(Map.of(), ttl))
        .hasMessageContaining("at most 3600000");
    Properties schema = new Properties();
    schema.setProperty("scalar.db.native_jdbc.metadata_schema", "a; drop");
    assertThatThrownBy(() -> DriverProperties.read(Map.of(), schema))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("plain identifier");
    Properties coordinator = new Properties();
    coordinator.setProperty("scalar.db.native_jdbc.coordinator_namespace", "c d");
    assertThatThrownBy(() -> DriverProperties.read(Map.of(), coordinator))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("plain identifier");
  }

  @Test
  void vendorProperties_dropTheDriversKeys_only() {
    Properties info = new Properties();
    info.setProperty("user", "u");
    info.setProperty("password", "p");
    info.setProperty("scalar.db.native_jdbc.metadata_schema", "s");
    Properties vendor = DriverProperties.vendorProperties(info);
    assertThat(vendor.stringPropertyNames()).containsExactlyInAnyOrder("user", "password");
  }

  @Test
  void describe_listsEveryProperty_withItsCurrentValue() throws SQLException {
    DriverPropertyInfo[] infos =
        DriverProperties.describe(
            DriverProperties.read(
                Map.of("scalar.db.native_jdbc.catalog_ttl_millis", "1000"), null));
    assertThat(infos)
        .extracting(i -> i.name)
        .containsExactly(
            "scalar.db.native_jdbc.metadata_schema",
            "scalar.db.native_jdbc.coordinator_namespace",
            "scalar.db.native_jdbc.catalog_ttl_millis",
            "scalar.db.native_jdbc.allow_no_catalog",
            "scalar.db.native_jdbc.inflight_wait_millis",
            "scalar.db.native_jdbc.predicate_checks");
    assertThat(infos[2].value).isEqualTo("1000");
    assertThat(infos[3].choices).containsExactly("true", "false");
  }
}
