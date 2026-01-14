/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core.util;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * An immutable map implementation using parallel arrays with linear search. Optimized for small
 * maps with identity-comparable keys. Designed for maximum Truffle PE performance.
 *
 * <p>Optimizations over IdentityArrayMap:
 *
 * <ul>
 *   <li>{@code @CompilationFinal} arrays - contents treated as constants by PE
 *   <li>No size tracking
 *   <li>No mutation code paths (more inlinable methods)
 *   <li>Exactly-sized arrays (no empty slots)
 * </ul>
 */
public final class ImmutableIdentityArrayMap<K, V> {
  @CompilationFinal(dimensions = 1)
  private final K[] keys;

  @CompilationFinal(dimensions = 1)
  private final V[] values;

  private ImmutableIdentityArrayMap(K[] keys, V[] values) {
    this.keys = keys;
    this.values = values;
  }

  /** Creates an empty immutable map. */
  @SuppressWarnings("unchecked")
  public static <K, V> ImmutableIdentityArrayMap<K, V> empty() {
    return new ImmutableIdentityArrayMap<>((K[]) new Object[0], (V[]) new Object[0]);
  }

  /** Creates an immutable map from parallel key and value arrays. Arrays are copied. */
  @SuppressWarnings("unchecked")
  public static <K, V> ImmutableIdentityArrayMap<K, V> of(K[] keys, V[] values) {
    if (keys.length != values.length) {
      throw new IllegalArgumentException("Keys and values arrays must have the same length");
    }
    if (keys.length == 0) {
      return empty();
    }
    K[] keysCopy = (K[]) new Object[keys.length];
    V[] valuesCopy = (V[]) new Object[values.length];
    System.arraycopy(keys, 0, keysCopy, 0, keys.length);
    System.arraycopy(values, 0, valuesCopy, 0, values.length);
    return new ImmutableIdentityArrayMap<>(keysCopy, valuesCopy);
  }

  /** Creates an immutable map from a mutable IdentityArrayMap. */
  @SuppressWarnings("unchecked")
  public static <K, V> ImmutableIdentityArrayMap<K, V> fromMutable(IdentityArrayMap<K, V> map) {
    int size = map.size();
    if (size == 0) {
      return empty();
    }
    K[] keys = (K[]) new Object[size];
    V[] values = (V[]) new Object[size];
    map.copyTo(keys, values);
    return new ImmutableIdentityArrayMap<>(keys, values);
  }

  /** Get the value mapped to this key, or null, using identity comparison. */
  public @Nullable V get(K key) {
    final K[] k = this.keys;
    final V[] v = this.values;

    for (int i = 0; i < k.length; i++) {
      if (k[i] == key) {
        return v[i];
      }
    }
    return null;
  }

  /** Check if key exists using identity comparison. */
  public boolean containsKey(K key) {
    final K[] k = this.keys;

    for (int i = 0; i < k.length; i++) {
      if (k[i] == key) {
        return true;
      }
    }
    return false;
  }

  /** Returns the number of key-value pairs in this map. */
  public int size() {
    return keys.length;
  }

  /** Returns true if this map contains no key-value pairs. */
  public boolean isEmpty() {
    return keys.length == 0;
  }

  /** Returns an iterable over the keys in this map. */
  public Iterable<K> getKeys() {
    return KeyIterator::new;
  }

  /** Returns an iterable over the values in this map. */
  public Iterable<V> getValues() {
    return ValueIterator::new;
  }

  private final class KeyIterator implements java.util.Iterator<K> {
    private int index = 0;

    @Override
    public boolean hasNext() {
      return index < keys.length;
    }

    @Override
    public K next() {
      return keys[index++];
    }
  }

  private final class ValueIterator implements java.util.Iterator<V> {
    private int index = 0;

    @Override
    public boolean hasNext() {
      return index < values.length;
    }

    @Override
    public V next() {
      return values[index++];
    }
  }

  /** Returns a cursor for iterating over all entries without allocation. */
  public MapCursor<K, V> getEntries() {
    return new CursorImpl();
  }

  private final class CursorImpl implements MapCursor<K, V> {
    private int index = -1;

    @Override
    public boolean advance() {
      return ++index < keys.length;
    }

    @Override
    public K getKey() {
      return keys[index];
    }

    @Override
    public V getValue() {
      return values[index];
    }
  }

  /** Creates an immutable map with a single key-value pair. */
  @SuppressWarnings("unchecked")
  public static <K, V> ImmutableIdentityArrayMap<K, V> of(K key, V value) {
    return new ImmutableIdentityArrayMap<>((K[]) new Object[] {key}, (V[]) new Object[] {value});
  }

  /** Returns a new builder for constructing an immutable map. */
  public static <K, V> Builder<K, V> builder() {
    return new Builder<>();
  }

  /** Returns a new builder with the specified initial capacity. */
  public static <K, V> Builder<K, V> builder(int initialCapacity) {
    return new Builder<>(initialCapacity);
  }

  /** Builder for constructing ImmutableIdentityArrayMap instances. */
  public static final class Builder<K, V> {
    private final IdentityArrayMap<K, V> map;

    public Builder() {
      this.map = new IdentityArrayMap<>();
    }

    public Builder(int initialCapacity) {
      this.map = new IdentityArrayMap<>(initialCapacity);
    }

    /** Adds a key-value pair. If key exists (by identity), updates the value. */
    public Builder<K, V> put(K key, V value) {
      map.put(key, value);
      return this;
    }

    /** Builds the immutable map. The builder should not be used after this call. */
    public ImmutableIdentityArrayMap<K, V> build() {
      return fromMutable(map);
    }
  }
}
