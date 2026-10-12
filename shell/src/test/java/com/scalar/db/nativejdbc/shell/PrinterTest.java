package com.scalar.db.nativejdbc.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PrinterTest {

  private static String print(Printer.Rows rows, @Nullable String title, boolean expanded) {
    StringWriter s = new StringWriter();
    Printer.print(rows, title, expanded, true, new PrintWriter(s, true));
    return s.toString();
  }

  private static Printer.Rows rows() {
    Printer.Rows rows = new Printer.Rows("id", "name");
    rows.numeric[0] = true;
    return rows.add("1", "日本").add("20", null);
  }

  @Test
  void aligned_asPsql() {
    assertThat(print(rows(), "List", false))
        .isEqualTo(
            """
               List
             id | name
            ----+------
              1 | 日本
             20 |
            (2 rows)

            """);
  }

  @Test
  void expanded_asPsqlWithX() {
    assertThat(print(rows(), null, true))
        .isEqualTo(
            """
            -[ RECORD 1 ]
            id   | 1
            name | 日本
            -[ RECORD 2 ]
            id   | 20
            name |

            """);
    assertThat(print(new Printer.Rows("a"), null, true)).isEqualTo("(0 rows)\n\n");
  }
}
