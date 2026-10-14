package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The router's own state, without a database. */
class RouterTest {

  /** A connection in autocommit mode whose nativeSQL marks the text; anything else is recorded. */
  private static Connection vendor(List<String> calls) {
    return (Connection)
        Proxy.newProxyInstance(
            RouterTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (p, m, args) -> {
              return switch (m.getName()) {
                case "getAutoCommit" -> true;
                case "nativeSQL" -> "vendor: " + args[0];
                default -> {
                  calls.add(m.getName());
                  throw new SQLException("not here");
                }
              };
            });
  }

  private static Router router() {
    Catalog catalog =
        new Catalog(
            Engine.POSTGRESQL,
            Catalog.DEFAULT_METADATA_SCHEMA,
            () -> {
              throw new SQLException("no catalog here");
            });
    return new Router(Engine.POSTGRESQL, catalog, new Session(Engine.POSTGRESQL));
  }

  @Test
  void routesFromSeveralThreads_keepTheCacheWorking() throws Exception {
    Router router = router();
    router.setPassthrough(true);
    Connection vendor = vendor(Collections.synchronizedList(new ArrayList<>()));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<?>> done = new ArrayList<>();
    for (int t = 0; t < 8; t++) {
      int seed = t;
      done.add(
          pool.submit(
              () -> {
                for (int i = 0; i < 20_000; i++) {
                  String sql = "SELECT " + ((i * 31 + seed) % 400);
                  assertThat(router.route(vendor, sql).sql).isEqualTo(sql);
                }
                return null;
              }));
    }
    for (Future<?> f : done) {
      f.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();
    // unsynchronized, the access-ordered map grew past its bound and lost its order (todo 125)
    Field f = Router.class.getDeclaredField("routes");
    f.setAccessible(true);
    Map<?, ?> routes = (Map<?, ?>) f.get(router);
    assertThat(routes.size()).isLessThanOrEqualTo(256);
    assertThat(new ArrayList<>(routes.keySet())).hasSize(routes.size());
  }

  @Test
  void nativeSql_runsNothing_andLeavesWhatTheDriverForwardsToTheVendor() throws SQLException {
    List<String> calls = new ArrayList<>();
    Connection vendor = vendor(calls);
    Router router = router();
    // refused, and unresolvable here: translated by the vendor, never an error (todo 127)
    assertThat(router.nativeSql(vendor, "{call p(?)}")).isEqualTo("vendor: {call p(?)}");
    assertThat(router.nativeSql(vendor, "RELEASE SAVEPOINT s"))
        .isEqualTo("vendor: RELEASE SAVEPOINT s");
    router.setPassthrough(true);
    assertThat(router.nativeSql(vendor, "SELECT 1")).isEqualTo("vendor: SELECT 1");
    // no count and no EXPLAIN; reading the session, stale on a new connection, is all it may do
    assertThat(calls).doesNotContain("createStatement", "setSavepoint");
  }
}
