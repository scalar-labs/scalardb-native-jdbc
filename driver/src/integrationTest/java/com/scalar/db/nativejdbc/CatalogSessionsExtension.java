package com.scalar.db.nativejdbc;

import com.scalar.db.nativejdbc.testing.Engines;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Releases the driver's catalog sessions after each test class, registered for every class by
 * autodetection (junit-platform.properties). The test JVM gathers a catalog for nearly every test's
 * identity, each holding a database session, and on Oracle Free, 200 processes with about 80 of its
 * own, those sessions with ScalarDB's pools ran the suite's last classes into ORA-12516 (CI runs
 * 41305423814, 41322039258 and 41362044124). The catalogs the tests make themselves, through {@link
 * Engines#catalog}, are released too. A catalog used again reopens its session.
 */
public final class CatalogSessionsExtension implements AfterAllCallback {
  @Override
  public void afterAll(ExtensionContext context) {
    NativeJdbcDriver.releaseCatalogSessions();
    Engines.releaseCatalogSessions();
  }
}
