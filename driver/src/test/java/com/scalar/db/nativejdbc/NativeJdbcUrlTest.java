package com.scalar.db.nativejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeJdbcUrlTest {

  @Test
  void parse_postgresql_stripsThePrefixAndKeepsTheVendorUrl() {
    NativeJdbcUrl url = NativeJdbcUrl.parse("jdbc:scalardb-native:postgresql://host:5432/db");

    assertThat(url.subProtocol()).isEqualTo("postgresql");
    assertThat(url.vendorUrl()).isEqualTo("jdbc:postgresql://host:5432/db");
  }

  @Test
  void parse_oracleThin_keepsTheVendorUrlAfterTheSubProtocol() {
    NativeJdbcUrl url = NativeJdbcUrl.parse("jdbc:scalardb-native:oracle:thin:@host:1521/db");

    assertThat(url.subProtocol()).isEqualTo("oracle");
    assertThat(url.vendorUrl()).isEqualTo("jdbc:oracle:thin:@host:1521/db");
  }

  @Test
  void parse_sqlServer_keepsThePropertiesInTheVendorUrl() {
    NativeJdbcUrl url =
        NativeJdbcUrl.parse("jdbc:scalardb-native:sqlserver://host:1433;databaseName=db");

    assertThat(url.subProtocol()).isEqualTo("sqlserver");
    assertThat(url.vendorUrl()).isEqualTo("jdbc:sqlserver://host:1433;databaseName=db");
  }

  @Test
  void accepts_ignoresCaseInThePrefix() {
    assertThat(NativeJdbcUrl.accepts("JDBC:ScalarDB-Native:mysql://host/db")).isTrue();
    assertThat(NativeJdbcUrl.accepts("jdbc:postgresql://host/db")).isFalse();
  }

  @Test
  void parse_lowerCasesTheSubProtocol_inTheVendorUrlToo() {
    NativeJdbcUrl url = NativeJdbcUrl.parse("jdbc:scalardb-native:MariaDB://host/db");
    assertThat(url.subProtocol()).isEqualTo("mariadb");
    assertThat(url.vendorUrl()).isEqualTo("jdbc:mariadb://host/db"); // vendors match it exactly
  }

  @Test
  void accepts_isFalseForNull() {
    assertThat(NativeJdbcUrl.accepts(null)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:scalardb-native:sqlite://u:s3cret@host/db",
        "jdbc:scalardb-native:sqlite://host/db?user=u&password=s3cret",
        "jdbc:scalardb-native:oracel:thin:u/s3cret@host:1521/db",
        "jdbc:scalardb-native:sqlserver2://host;user=u;password=s3cret",
        "jdbc:scalardb-nativ:postgresql://host/db?password=s3cret",
        "jdbc:scalardb-native:"
      })
  void parseErrors_neverRepeatTheUrl(String url) {
    assertThatThrownBy(() -> NativeJdbcUrl.parse(url))
        .isInstanceOf(IllegalArgumentException.class)
        .message()
        .doesNotContain("s3cret")
        .doesNotContain("host");
  }

  @Test
  void parse_rejectsAnUnsupportedSubProtocol() {
    assertThatThrownBy(() -> NativeJdbcUrl.parse("jdbc:scalardb-native:sqlite:file.db"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sqlite");
  }

  @Test
  void parse_rejectsAUrlWithoutASubProtocol() {
    assertThatThrownBy(() -> NativeJdbcUrl.parse("jdbc:scalardb-native:"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parse_rejectsAForeignUrl() {
    assertThatThrownBy(() -> NativeJdbcUrl.parse("jdbc:postgresql://host/db"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
