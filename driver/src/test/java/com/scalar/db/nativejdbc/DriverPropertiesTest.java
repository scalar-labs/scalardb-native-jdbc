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
    assertThat(p.passthrough).isFalse();
  }

  @Test
  void readsProperties_andTheUrlWins() throws SQLException {
    Properties info = new Properties();
    info.setProperty("scalardbnative.metadataSchema", "fromProperties");
    info.setProperty("scalardbnative.catalogTtlMs", "5000");
    info.setProperty("scalardbnative.allowNoCatalog", "TRUE");
    DriverProperties p =
        DriverProperties.read(Map.of("scalardbnative.metadataSchema", "fromUrl"), info);
    assertThat(p.metadataSchema).isEqualTo("fromUrl");
    assertThat(p.catalogTtl).isEqualTo(Duration.ofSeconds(5));
    assertThat(p.allowNoCatalog).isTrue();
  }

  @Test
  void malformedOrUnknownProperties_failTheConnect() {
    Properties bad = new Properties();
    bad.setProperty("scalardbnative.catalogTtlMs", "-1");
    assertThatThrownBy(() -> DriverProperties.read(Map.of(), bad))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("catalogTtlMs")
        .extracting("SQLState")
        .isEqualTo("08001");
    assertThatThrownBy(() -> DriverProperties.read(Map.of("scalardbnative.typo", "x"), null))
        .hasMessageContaining("Unknown driver property scalardbnative.typo");
    assertThatThrownBy(
            () -> DriverProperties.read(Map.of("scalardbnative.passthrough", "yes"), null))
        .hasMessageContaining("true or false");
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
            "scalardbnative.passthrough");
    assertThat(infos[1].value).isEqualTo("1000");
    assertThat(infos[2].choices).containsExactly("true", "false");
  }
}
