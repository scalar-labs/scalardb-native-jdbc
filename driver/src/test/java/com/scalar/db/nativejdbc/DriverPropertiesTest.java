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
    assertThat(p.catalogTtl).isEqualTo(Duration.ofSeconds(60));
    assertThat(p.allowNoCatalog).isFalse();
    assertThat(p.inflightWait).isEqualTo(Duration.ZERO);
  }

  @Test
  void readsProperties_andTheUrlWinsForTheSettingsItMaySet() throws SQLException {
    Properties info = new Properties();
    info.setProperty("scalardbnative.metadataSchema", "meta");
    info.setProperty("scalardbnative.catalogTtlMs", "5000");
    info.setProperty("scalardbnative.allowNoCatalog", "TRUE");
    DriverProperties p = DriverProperties.read(Map.of("scalardbnative.catalogTtlMs", "7000"), info);
    assertThat(p.metadataSchema).isEqualTo("meta");
    assertThat(p.catalogTtl).isEqualTo(Duration.ofSeconds(7));
    assertThat(p.allowNoCatalog).isTrue();
  }

  @Test
  void settingsThatCanTurnTheProtectionOff_areRefusedFromTheUrl() {
    for (String key :
        new String[] {
          "scalardbnative.metadataSchema",
          "scalardbnative.allowNoCatalog",
          "scalardbnative.passthrough"
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
    ttl.setProperty("scalardbnative.catalogTtlMs", "9223372036854775807");
    assertThatThrownBy(() -> DriverProperties.read(Map.of(), ttl))
        .hasMessageContaining("at most 3600000");
    Properties schema = new Properties();
    schema.setProperty("scalardbnative.metadataSchema", "a; drop");
    assertThatThrownBy(() -> DriverProperties.read(Map.of(), schema))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("plain identifier");
  }

  @Test
  void vendorProperties_dropTheDriversKeys_only() {
    Properties info = new Properties();
    info.setProperty("user", "u");
    info.setProperty("password", "p");
    info.setProperty("scalardbnative.metadataSchema", "s");
    Properties vendor = DriverProperties.vendorProperties(info);
    assertThat(vendor.stringPropertyNames()).containsExactlyInAnyOrder("user", "password");
  }

  @Test
  void describe_listsEveryProperty_withItsCurrentValue() throws SQLException {
    DriverPropertyInfo[] infos =
        DriverProperties.describe(
            DriverProperties.read(Map.of("scalardbnative.catalogTtlMs", "1000"), null));
    assertThat(infos)
        .extracting(i -> i.name)
        .containsExactly(
            "scalardbnative.metadataSchema",
            "scalardbnative.catalogTtlMs",
            "scalardbnative.allowNoCatalog",
            "scalardbnative.inflightWaitMs",
            "scalardbnative.predicateChecks");
    assertThat(infos[1].value).isEqualTo("1000");
    assertThat(infos[2].choices).containsExactly("true", "false");
  }
}
