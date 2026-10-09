package com.scalar.db.nativejdbc.sql;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.List;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

/**
 * Run by the {@code modulePathCheck} Gradle task with JSQLParser as a named module, where private
 * fields are inaccessible: every check of the classifier must give the same answers as on the class
 * path, never a silently empty one.
 */
public final class ModulePathCheck {

  private ModulePathCheck() {}

  public static void main(String[] args) {
    Module parser = CCJSqlParserUtil.class.getModule();
    check(parser.isNamed(), "JSQLParser must be loaded as a named module here, got " + parser);

    SqlAst ast =
        SqlAst.parse("select * from t where id = ? and (k = :k or n = abs(?))", Engine.POSTGRESQL)
            .orElseThrow();
    check(ast.parameterCount() == 3, "parameterCount: " + ast.parameterCount());
    StatementShape shape = StatementShape.of(ast).orElseThrow();
    check(
        shape.whereColumns().equals(List.of("id", "k", "n")),
        "whereColumns: " + shape.whereColumns());
    check(
        !shape.isKeyEquality(List.of("id")),
        "a predicate with more than the key is not a key equality");
    check(
        StatementShape.of("select * from t where id = ?", Engine.POSTGRESQL)
            .orElseThrow()
            .isKeyEquality(List.of("id")),
        "key equality");
    check(
        StatementShape.of("select count(*) from t", Engine.POSTGRESQL).orElseThrow().grouped(),
        "a function is seen");
    check(
        StatementShape.of("select * from a join b on a.id = b.id", Engine.POSTGRESQL).isEmpty(),
        "a join is refused");
    check(
        StatementShape.of("insert ignore into t (id) values (?)", Engine.POSTGRESQL).isEmpty(),
        "INSERT IGNORE is refused");

    StringBuilder wide = new StringBuilder("select * from t where c0 = ?");
    for (int i = 1; i < 100; i++) {
      wide.append(" or c").append(i).append(" = ?");
    }
    SqlAst deep = SqlAst.parse(wide.toString(), Engine.POSTGRESQL).orElseThrow();
    check(deep.parameterCount() == 100, "a 100-term predicate is walked in full");
    check(
        StatementShape.of(deep).orElseThrow().whereColumns().size() == 100,
        "every column of a 100-term predicate is seen");
    System.out.println("module-path check passed with " + parser.getName());
  }

  private static void check(boolean ok, String what) {
    if (!ok) {
      throw new AssertionError(what);
    }
  }
}
