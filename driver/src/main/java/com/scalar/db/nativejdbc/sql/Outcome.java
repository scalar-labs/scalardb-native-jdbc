package com.scalar.db.nativejdbc.sql;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a classification produced: a value, or the reason the statement was refused. The reason is
 * written for the application developer who must change the statement, and becomes the message of
 * the {@code 0AN01} the driver raises (docs/design.md, "Errors").
 *
 * @param <T> the value's type
 */
public final class Outcome<T> {
  private final @Nullable T value;
  private final @Nullable String reason;
  private final boolean unreadable;

  private Outcome(@Nullable T value, @Nullable String reason, boolean unreadable) {
    this.value = value;
    this.reason = reason;
    this.unreadable = unreadable;
  }

  static <T> Outcome<T> of(T value) {
    return new Outcome<>(Objects.requireNonNull(value), null, false);
  }

  static <T> Outcome<T> refused(String reason) {
    return new Outcome<>(null, Objects.requireNonNull(reason), false);
  }

  /** Refused since the driver cannot tell which tables the statement names (todo 314). */
  static <T> Outcome<T> unreadable(String reason) {
    return new Outcome<>(null, Objects.requireNonNull(reason), true);
  }

  /**
   * Returns whether there is a value.
   *
   * @return whether the statement was accepted
   */
  public boolean isPresent() {
    return value != null;
  }

  /**
   * Returns the value.
   *
   * @return the value
   * @throws IllegalStateException if the statement was refused
   */
  public T get() {
    if (value == null) {
      throw new IllegalStateException("refused: " + reason);
    }
    return value;
  }

  /**
   * Returns why the statement was refused.
   *
   * @return the reason
   * @throws IllegalStateException if the statement was accepted
   */
  public String reason() {
    if (reason == null) {
      throw new IllegalStateException("not refused");
    }
    return reason;
  }

  /**
   * Returns whether the statement was refused only because the driver cannot tell which tables it
   * names: it could not be parsed, or its table references could not all be found. Such a statement
   * may still be sent as written when its words name no ScalarDB table (todo 314).
   *
   * @return whether the refusal is of that kind
   */
  public boolean isUnreadable() {
    return unreadable;
  }

  /**
   * Returns the value, or empty when refused.
   *
   * @return the optional value
   */
  public Optional<T> toOptional() {
    return Optional.ofNullable(value);
  }

  <U> Outcome<U> mapRefusal() {
    return new Outcome<>(null, reason(), unreadable);
  }

  @Override
  public String toString() {
    return value != null ? "Outcome[" + value + "]" : "Outcome[refused: " + reason + "]";
  }
}
