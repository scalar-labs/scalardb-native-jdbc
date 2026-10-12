package com.scalar.db.nativejdbc.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What snsql prints reveals no password and carries no terminal escape sequence. */
class OutputSafetyTest {

  @Test
  void theUrlShown_dropsCredentials() {
    assertThat(Main.withoutCredentials("jdbc:scalardb-native:postgresql://h:5432/db?password=s"))
        .isEqualTo("jdbc:scalardb-native:postgresql://h:5432/db");
    assertThat(Main.withoutCredentials("jdbc:scalardb-native:sqlserver://h:1433;password=s"))
        .isEqualTo("jdbc:scalardb-native:sqlserver://h:1433");
    assertThat(Main.withoutCredentials("jdbc:scalardb-native:mariadb://u:s@h:3306/db"))
        .isEqualTo("jdbc:scalardb-native:mariadb://h:3306/db");
    assertThat(Main.withoutCredentials("jdbc:scalardb-native:oracle:thin:u/s@//h:1521/db"))
        .isEqualTo("jdbc:scalardb-native:oracle:thin:@//h:1521/db");
    assertThat(Main.withoutCredentials("jdbc:scalardb-native:oracle:thin:@//h:1521/db"))
        .isEqualTo("jdbc:scalardb-native:oracle:thin:@//h:1521/db");
  }

  @Test
  void controlCharacters_areEscaped() {
    assertThat(Printer.printable("a\u001b]52;c;x\u0007\nb\tc\u009bd"))
        .isEqualTo("a\\x1b]52;c;x\\x07\\nb\\tc\\x9bd");
    assertThat(Printer.printable("日本")).isEqualTo("日本");
  }
}
