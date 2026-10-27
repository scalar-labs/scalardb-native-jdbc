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
  void driverParameters_areTakenOutOfTheVendorUrl() {
    NativeJdbcUrl pg =
        NativeJdbcUrl.parse(
            "jdbc:scalardb-native:postgresql://h/db?ssl=true&scalar.db.native_jdbc.inflight_wait_millis=50"
                + "&scalar.db.native_jdbc.catalog_ttl_millis=1000");
    assertThat(pg.vendorUrl()).isEqualTo("jdbc:postgresql://h/db?ssl=true");
    assertThat(pg.driverParameters())
        .containsEntry("scalar.db.native_jdbc.inflight_wait_millis", "50")
        .containsEntry("scalar.db.native_jdbc.catalog_ttl_millis", "1000");

    NativeJdbcUrl only =
        NativeJdbcUrl.parse(
            "jdbc:scalardb-native:mysql://h/db?scalar.db.native_jdbc.allow_no_catalog=true");
    assertThat(only.vendorUrl()).isEqualTo("jdbc:mysql://h/db");

    // an old key too, for the driver to refuse with its new name rather than the vendor to ignore
    NativeJdbcUrl old =
        NativeJdbcUrl.parse("jdbc:scalardb-native:postgresql://h/db?scalardbnative.catalogTtlMs=1");
    assertThat(old.vendorUrl()).isEqualTo("jdbc:postgresql://h/db");
    assertThat(old.driverParameters()).containsEntry("scalardbnative.catalogTtlMs", "1");

    NativeJdbcUrl mssql =
        NativeJdbcUrl.parse(
            "jdbc:scalardb-native:sqlserver://h:1433;databaseName=db;"
                + "scalar.db.native_jdbc.metadata_schema=meta;encrypt=false");
    assertThat(mssql.vendorUrl())
        .isEqualTo("jdbc:sqlserver://h:1433;databaseName=db;encrypt=false");
    assertThat(mssql.driverParameters())
        .containsEntry("scalar.db.native_jdbc.metadata_schema", "meta");

    // a braced SQL Server value is one value: nothing inside it is taken for a driver key
    NativeJdbcUrl braced =
        NativeJdbcUrl.parse(
            "jdbc:scalardb-native:sqlserver://h;databaseName=db;"
                + "password={a;scalar.db.native_jdbc.allow_no_catalog=true;b};encrypt=false");
    assertThat(braced.vendorUrl())
        .isEqualTo(
            "jdbc:sqlserver://h;databaseName=db;"
                + "password={a;scalar.db.native_jdbc.allow_no_catalog=true;b};encrypt=false");
    assertThat(braced.driverParameters()).isEmpty();

    NativeJdbcUrl none = NativeJdbcUrl.parse("jdbc:scalardb-native:postgresql://h/db?ssl=true");
    assertThat(none.vendorUrl()).isEqualTo("jdbc:postgresql://h/db?ssl=true");
    assertThat(none.driverParameters()).isEmpty();
  }

  @Test
  void sqlServerValues_areReadAsMssqlJdbcReadsThem() {
    // }} inside braces is a literal }: everything up to the real closing brace is the password
    NativeJdbcUrl escaped =
        NativeJdbcUrl.parse(
            "jdbc:scalardb-native:sqlserver://h;password={a}};scalar.db.native_jdbc.catalog_ttl_millis=1;"
                + "x=};encrypt=false");
    assertThat(escaped.driverParameters()).isEmpty();
    assertThat(escaped.vendorUrl())
        .isEqualTo(
            "jdbc:sqlserver://h;password={a}};scalar.db.native_jdbc.catalog_ttl_millis=1;x=};encrypt=false");
    // a brace not at the start of a value is an ordinary character
    NativeJdbcUrl inner =
        NativeJdbcUrl.parse(
            "jdbc:scalardb-native:sqlserver://h;applicationName=a{b;scalar.db.native_jdbc.catalog_ttl_millis=1");
    assertThat(inner.driverParameters())
        .containsEntry("scalar.db.native_jdbc.catalog_ttl_millis", "1");
    // an unclosed brace, or text after a closing one, is refused
    assertThatThrownBy(() -> NativeJdbcUrl.parse("jdbc:scalardb-native:sqlserver://h;password={a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("password=");
    assertThatThrownBy(
            () -> NativeJdbcUrl.parse("jdbc:scalardb-native:sqlserver://h;password={a}b;x=1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aQuestionMarkInsideTheHostPart_isRefused() {
    assertThatThrownBy(
            () ->
                NativeJdbcUrl.parse(
                    "jdbc:scalardb-native:mysql://u:p?scalar.db.native_jdbc.catalog_ttl_millis=1@h/db"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguous");
    // a query after the path may hold an '@', as in a user name
    assertThat(NativeJdbcUrl.parse("jdbc:scalardb-native:mysql://h/db?user=me@corp").vendorUrl())
        .isEqualTo("jdbc:mysql://h/db?user=me@corp");
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
