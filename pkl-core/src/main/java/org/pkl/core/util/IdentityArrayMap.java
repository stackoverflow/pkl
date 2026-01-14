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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.Arrays;

/**
 * A simple map implementation using parallel arrays with linear search. Optimized for small maps
 * with identity-comparable keys. Designed to be PE-friendly.
 *
 * <p>Trade-offs:
 *
 * <ul>
 *   <li>O(n) lookup - but n is typically small for Pkl objects
 *   <li>O(1) insertion (amortized)
 *   <li>Excellent cache behavior - sequential memory access
 *   <li>No hashing overhead
 * </ul>
 */
public final class IdentityArrayMap<K, V> {
  private K[] keys;
  private V[] values;
  private int size;

  private static final int INITIAL_CAPACITY = 8;

  @SuppressWarnings("unchecked")
  public IdentityArrayMap() {
    this.keys = (K[]) new Object[INITIAL_CAPACITY];
    this.values = (V[]) new Object[INITIAL_CAPACITY];
    this.size = 0;
  }

  @SuppressWarnings("unchecked")
  public IdentityArrayMap(int initialCapacity) {
    this.keys = (K[]) new Object[initialCapacity];
    this.values = (V[]) new Object[initialCapacity];
    this.size = 0;
  }

  /** Get the value mapped to this key, or null, using identity comparison. */
  public @Nullable V get(K key) {
    final K[] k = this.keys;
    final V[] v = this.values;
    final int s = this.size;

    for (int i = 0; i < s; i++) {
      if (k[i] == key) {
        return v[i];
      }
    }
    return null;
  }

  /** Check if key exists using identity comparison. */
  public boolean containsKey(K key) {
    final K[] k = this.keys;
    final int s = this.size;

    for (int i = 0; i < s; i++) {
      if (k[i] == key) {
        return true;
      }
    }
    return false;
  }

  /** Put a key-value pair. If key exists (by identity), update the value. */
  @TruffleBoundary
  public @Nullable V put(K key, V value) {
    // Check if key exists (update case)
    for (int i = 0; i < size; i++) {
      if (keys[i] == key) {
        var old = values[i];
        values[i] = value;
        return old;
      }
    }

    // New key - ensure capacity
    if (size >= keys.length) {
      int newCapacity = keys.length * 2;
      keys = Arrays.copyOf(keys, newCapacity);
      values = Arrays.copyOf(values, newCapacity);
    }

    keys[size] = key;
    values[size] = value;
    size++;
    return null;
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** Returns an immutable copy of this map. */
  public ImmutableIdentityArrayMap<K, V> toImmutable() {
    return ImmutableIdentityArrayMap.fromMutable(this);
  }

  /** Returns an iterable over the keys in this map. */
  public Iterable<K> getKeys() {
    return KeyIterator::new;
  }

  /** Returns an iterable over the values in this map. */
  public Iterable<V> getValues() {
    return ValueIterator::new;
  }

  /** Creates an immutable map with a single key-value pair. */
  public static <K, V> IdentityArrayMap<K, V> of(K key, V value) {
    var map = new IdentityArrayMap<K, V>();
    map.put(key, value);
    return map;
  }

  private final class KeyIterator implements java.util.Iterator<K> {
    private final int capturedSize = size;
    private int index = 0;

    @Override
    public boolean hasNext() {
      return index < capturedSize;
    }

    @Override
    public K next() {
      return keys[index++];
    }
  }

  private final class ValueIterator implements java.util.Iterator<V> {
    private final int capturedSize = size;
    private int index = 0;

    @Override
    public boolean hasNext() {
      return index < capturedSize;
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
    private final int capturedSize = size;
    private int index = -1;

    @Override
    public boolean advance() {
      return ++index < capturedSize;
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

  /**
   * Copies the keys and values to the provided arrays. Arrays must be at least {@link #size()}
   * elements long.
   */
  void copyTo(K[] destKeys, V[] destValues) {
    System.arraycopy(keys, 0, destKeys, 0, size);
    System.arraycopy(values, 0, destValues, 0, size);
  }
}
