package com.scalar.db.nativejdbc.jdbc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The versions a transaction read at READ COMMITTED, per key: the anchors of its later writes to
 * those keys (design 4.2 and 4.3, milestone 4b). The first read of a key gives its anchor; a key
 * the transaction has written is checked against nothing more, since its row lock is held.
 */
// ponytail: one entry per key read in the transaction, unbounded; a transaction reading millions of
// rows holds as many, until its end
final class Anchors {

  /** A row's version: {@code (tx_id, tx_version)}, either NULL for a row without metadata. */
  static final class Version {
    final @Nullable String txId;
    final @Nullable Long version;

    Version(@Nullable String txId, @Nullable Object version) {
      this.txId = txId;
      this.version = version == null ? null : ((Number) version).longValue();
    }

    /** Whether a version read later is this one. */
    boolean is(@Nullable String txId, @Nullable Object version) {
      return Objects.equals(this.txId, txId)
          && Objects.equals(this.version, version == null ? null : ((Number) version).longValue());
    }
  }

  private final Map<String, Map<List<Object>, Version>> read = new HashMap<>();
  private final Map<String, Set<List<Object>>> written = new HashMap<>();

  /** A key's values as a map key: arrays by content. */
  private static List<Object> key(Object[] values) {
    List<Object> out = new ArrayList<>(values.length);
    for (Object v : values) {
      out.add(v instanceof byte[] ? ByteBuffer.wrap(((byte[]) v).clone()) : v);
    }
    return out;
  }

  /** Records a key's version as read, unless the transaction read the key before. */
  synchronized void read(String table, Object[] key, Version v) {
    read.computeIfAbsent(table, t -> new HashMap<>()).putIfAbsent(key(key), v);
  }

  /** The version the transaction read for a key it has not written, or null. */
  synchronized @Nullable Version anchor(String table, Object[] key) {
    List<Object> k = key(key);
    Set<List<Object>> w = written.get(table);
    if (w != null && w.contains(k)) {
      return null;
    }
    Map<List<Object>, Version> r = read.get(table);
    return r == null ? null : r.get(k);
  }

  /** Records a key the transaction wrote. */
  synchronized void written(String table, Object[] key) {
    written.computeIfAbsent(table, t -> new HashSet<>()).add(key(key));
  }

  /** At commit or rollback. */
  synchronized void transactionEnded() {
    read.clear();
    written.clear();
  }

  /**
   * At a rollback to a savepoint: a write after it is undone, so its keys are checked against their
   * first read again, which aborts where an earlier write of the key survived; safe, not exact.
   */
  synchronized void rolledBackToSavepoint() {
    written.clear();
  }
}
