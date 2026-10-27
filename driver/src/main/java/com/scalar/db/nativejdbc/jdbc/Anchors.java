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
 * those keys (design 4.2 and 4.3, milestone 4b). The first read of a key gives its anchor. A row
 * carrying one of the transaction's own write stamps counts as unchanged: only the transaction's
 * own uncommitted write, under its row lock, can have put it there.
 */
// ponytail: one entry per key read in the transaction, unbounded; a transaction reading millions of
// rows holds as many, until its end (a read-only connection records nothing)
final class Anchors {

  /** A row's version: {@code (tx_id, tx_version)}, either NULL for a row without metadata. */
  static final class Version {
    final @Nullable String txId;
    final @Nullable Long version;

    Version(@Nullable String txId, @Nullable Object version) {
      this.txId = txId;
      this.version = version == null ? null : ((Number) version).longValue();
    }

    /**
     * Whether a version read later is this one. Without a {@code tx_id} the version does not tell
     * rows apart, as the CAS's {@code tx_id IS NULL} does not: a ScalarDB prepare over such a row
     * writes version 0, its rollback NULL, and every write that changes the row sets a {@code
     * tx_id} (todo 145).
     */
    boolean is(@Nullable String txId, @Nullable Object version) {
      if (this.txId == null && txId == null) {
        return true;
      }
      return Objects.equals(this.txId, txId)
          && Objects.equals(this.version, version == null ? null : ((Number) version).longValue());
    }
  }

  private final Map<String, Map<List<Object>, Version>> read = new HashMap<>();
  private final Set<String> ownStamps = new HashSet<>();
  // moves at every transaction's end, so that a result set read across it records nothing
  private long epoch;

  /** A key's values as a map key: arrays by content. */
  static List<Object> key(Object[] values) {
    List<Object> out = new ArrayList<>(values.length);
    for (Object v : values) {
      out.add(v instanceof byte[] ? ByteBuffer.wrap(((byte[]) v).clone()) : v);
    }
    return out;
  }

  /** The current transaction, as a result set records it at its execute. */
  synchronized long epoch() {
    return epoch;
  }

  /**
   * Records a key's version as read by a statement executed in transaction {@code at}, unless the
   * transaction has ended since (todo 141) or read the key before.
   */
  synchronized void read(long at, String table, Object[] key, Version v) {
    if (at == epoch) {
      read.computeIfAbsent(table, t -> new HashMap<>()).putIfAbsent(key(key), v);
    }
  }

  /** Whether a key's version is still the one the transaction read: true when it read none. */
  synchronized boolean unchanged(
      String table, Object[] key, @Nullable String txId, @Nullable Object version) {
    if (txId != null && ownStamps.contains(txId)) {
      return true; // the transaction's own write (todo 142)
    }
    Map<List<Object>, Version> r = read.get(table);
    Version v = r == null ? null : r.get(key(key));
    return v == null || v.is(txId, version);
  }

  /** The version the transaction read of a key, as {@link #key} matches it; null where none. */
  synchronized @Nullable Version version(String table, Object[] key) {
    Map<List<Object>, Version> r = read.get(table);
    return r == null ? null : r.get(key(key));
  }

  /** Records the stamp of one of the transaction's writes. */
  synchronized void wrote(String txId) {
    ownStamps.add(txId);
  }

  /** Whether the transaction recorded a version of any key of {@code table}. */
  synchronized boolean recorded(String table) {
    Map<List<Object>, Version> r = read.get(table);
    return r != null && !r.isEmpty();
  }

  /** At commit or rollback. */
  synchronized void transactionEnded() {
    read.clear();
    ownStamps.clear();
    epoch++;
  }
}
