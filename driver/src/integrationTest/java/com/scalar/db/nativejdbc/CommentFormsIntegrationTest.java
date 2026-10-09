package com.scalar.db.nativejdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parser refuses a comment form exactly where the engine executes it: each statement selects 1
 * where the comment is a comment and 2 where the engine runs part of it. The one deliberate
 * exception is MariaDB's {@code /*M!} form, refused on MySQL as well.
 */
class CommentFormsIntegrationTest {

  private static final List<String> FORMS =
      List.of(
          "SELECT 1 /*! + 1 */", // MySQL's executable comment
          "SELECT 1 /*M! + 1 */",
          "SELECT 1 --1", // "- -1" where "--" needs a space
          "SELECT 1 /* /* */ + 1 -- */"); // a nested comment, flat on some engines

  static Stream<Arguments> cases() {
    List<Arguments> out = new ArrayList<>();
    for (Engines e : Engines.selected()) {
      for (String form : FORMS) {
        out.add(Arguments.of(e, form));
      }
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("cases")
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the statements are the constant forms above")
  void theParserRefuses_exactlyWhatTheEngineExecutes(Engines e, String form) throws Exception {
    String sql = e == Engines.ORACLE ? form + "\nFROM dual" : form + "\n";
    int value;
    try (Connection c = e.open();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      value = rs.getInt(1);
    }
    boolean refused = !SqlAst.analyze(sql, e.engine(), true).isPresent();
    // one deliberate extra refusal: a mysql URL may be served by a MariaDB server, which runs /*M!
    boolean mariaDbFormOnMySql =
        form.contains("/*M!") && e.engine() == com.scalar.db.nativejdbc.engine.Engine.MYSQL;
    assertThat(refused)
        .as("%s returned %d", sql.strip(), value)
        .isEqualTo(value != 1 || mariaDbFormOnMySql);
  }
}
