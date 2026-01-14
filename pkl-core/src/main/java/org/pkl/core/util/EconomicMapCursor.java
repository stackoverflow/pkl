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

import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.collections.UnmodifiableMapCursor;

/**
 * Adapts EconomicMap's cursor to our MapCursor interface.
 *
 * <p>This allows uniform iteration across different map implementations used by VmObject
 * subclasses.
 */
public final class EconomicMapCursor<K, V> implements MapCursor<K, V> {
  private final UnmodifiableMapCursor<K, V> delegate;

  public EconomicMapCursor(UnmodifiableEconomicMap<K, V> map) {
    this.delegate = map.getEntries();
  }

  @Override
  public boolean advance() {
    return delegate.advance();
  }

  @Override
  public K getKey() {
    return delegate.getKey();
  }

  @Override
  public V getValue() {
    return delegate.getValue();
  }
}
