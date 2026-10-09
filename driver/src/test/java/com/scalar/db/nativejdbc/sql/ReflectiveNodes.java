package com.scalar.db.nativejdbc.sql;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The oracle for the syntax-tree walk of {@link SqlAst}: every parser object reachable from a
 * statement through its fields. Reflection is fine in a test, which runs with the parser on the
 * class path; the driver itself must not depend on it. An inaccessible field is an error, never
 * skipped, so the oracle cannot go blind.
 */
final class ReflectiveNodes {

  private ReflectiveNodes() {}

  /** Every {@code net.sf.jsqlparser} object reachable from {@code root}, by identity. */
  @SuppressWarnings("IdentityHashMapUsage")
  static Set<Object> reachable(Object root) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Object> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      Object o = stack.pop();
      Class<?> k = o.getClass();
      String name = k.getName();
      boolean node =
          name.startsWith("net.sf.jsqlparser.") && !name.startsWith("net.sf.jsqlparser.parser.");
      if (node && (!seen.add(o) || o instanceof Enum<?>)) {
        continue;
      }
      if (o instanceof Collection<?>) {
        for (Object e : (Collection<?>) o) {
          pushIfPresent(stack, e);
        }
      } else if (o instanceof Map<?, ?>) {
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
          pushIfPresent(stack, e.getKey());
          pushIfPresent(stack, e.getValue());
        }
      } else if (k.isArray() && !k.getComponentType().isPrimitive()) {
        for (int i = 0, n = Array.getLength(o); i < n; i++) {
          pushIfPresent(stack, Array.get(o, i));
        }
      }
      if (!node) {
        continue;
      }
      for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
        if (!c.getName().startsWith("net.sf.jsqlparser.")) {
          continue;
        }
        for (Field f : c.getDeclaredFields()) {
          if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
            continue;
          }
          try {
            f.setAccessible(true);
            pushIfPresent(stack, f.get(o));
          } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + f, e);
          }
        }
      }
    }
    return seen;
  }

  private static void pushIfPresent(Deque<Object> stack, Object o) {
    if (o != null) {
      stack.push(o);
    }
  }
}
