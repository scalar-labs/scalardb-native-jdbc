package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The versions a transaction read, kept as read and indexed by key when first asked (todo 334). */
class AnchorsTest {

  private static Object[] key(Object... values) {
    return values;
  }

  private static Anchors.Version v(String txId, long version) {
    return new Anchors.Version(txId, version);
  }

  @Test
  void aKeyReadTwice_keepsItsFirstVersion() {
    Anchors a = new Anchors();
    a.read(a.epoch(), "t", key(1), v("a", 1));
    a.read(a.epoch(), "t", key(1), v("b", 2));
    assertThat(a.version("t", key(1)).txId).isEqualTo("a");
    assertThat(a.unchanged("t", key(1), "a", 1L)).isTrue();
    assertThat(a.unchanged("t", key(1), "b", 2L)).isFalse();
  }

  @Test
  void recorded_withoutAndWithTheIndex() {
    Anchors a = new Anchors();
    assertThat(a.recorded("t")).isFalse();
    a.read(a.epoch(), "t", key(1), v("a", 1));
    assertThat(a.recorded("t")).isTrue();
    assertThat(a.recorded("u")).isFalse();
    assertThat(a.version("t", key(2))).isNull();
    assertThat(a.recorded("t")).isTrue();
    assertThat(a.version("u", key(1))).isNull();
  }

  @Test
  void aReadAfterTheTransactionEnded_recordsNothing() {
    Anchors a = new Anchors();
    long at = a.epoch();
    a.read(at, "t", key(1), v("a", 1));
    a.transactionEnded();
    assertThat(a.recorded("t")).isFalse();
    a.read(at, "t", key(1), v("a", 1));
    assertThat(a.recorded("t")).isFalse();
    assertThat(a.version("t", key(1))).isNull();
  }

  @Test
  void readsAfterALookup_areFoldedIn_theFirstStillWinning() {
    Anchors a = new Anchors();
    a.read(a.epoch(), "t", key(1), v("a", 1));
    assertThat(a.version("t", key(1)).txId).isEqualTo("a");
    a.read(a.epoch(), "t", key(2), v("b", 1));
    a.read(a.epoch(), "t", key(1), v("c", 2));
    assertThat(a.version("t", key(1)).txId).isEqualTo("a");
    assertThat(a.version("t", key(2)).txId).isEqualTo("b");
  }

  @Test
  void manyReadsOfOneKey_holdTheFirst() {
    Anchors a = new Anchors();
    for (int i = 0; i < Anchors.UNINDEXED * 3; i++) {
      a.read(a.epoch(), "t", key(1), v("x" + i, i));
    }
    assertThat(a.version("t", key(1)).txId).isEqualTo("x0");
  }

  @Test
  void bytesKeys_matchByContent() {
    Anchors a = new Anchors();
    a.read(a.epoch(), "t", key(new byte[] {1, 2}, 3), v("a", 1));
    assertThat(a.version("t", key(new byte[] {1, 2}, 3)).txId).isEqualTo("a");
    assertThat(a.version("t", key(new byte[] {1, 3}, 3))).isNull();
  }

  @Test
  void anOwnStamp_isUnchanged() {
    Anchors a = new Anchors();
    a.read(a.epoch(), "t", key(1), v("a", 1));
    a.wrote("mine");
    assertThat(a.unchanged("t", key(1), "mine", 9L)).isTrue();
    assertThat(a.unchanged("t", key(1), "other", 9L)).isFalse();
  }

  @Test
  void aRowWithoutMetadata_matchesOneWithout() {
    Anchors a = new Anchors();
    a.read(a.epoch(), "t", key(1), new Anchors.Version(null, null));
    assertThat(a.unchanged("t", key(1), null, 0L)).isTrue();
    assertThat(a.unchanged("t", key(1), "w", 1L)).isFalse();
  }
}
