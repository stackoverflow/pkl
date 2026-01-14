/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.core.runtime;

import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;

/**
 * Factory and utilities for Truffle {@link Shape} instances used by Pkl objects.
 *
 * <p>Shapes define the structure of dynamic objects (which properties exist and their storage
 * layout). Objects with the same property structure share a Shape, enabling efficient inline
 * caching and partial evaluation by the Graal compiler.
 *
 * <p>Usage pattern:
 *
 * <ul>
 *   <li>Each VmClass will have a base shape for its instances
 *   <li>Amending an object creates a new shape with additional properties
 *   <li>Cached property values are stored as shape properties
 * </ul>
 */
public final class PklShape {

  /** The root shape for all VmTyped instances. Uses Object layout for flexibility. */
  private static final Shape ROOT_SHAPE = Shape.newBuilder().build();

  private PklShape() {
    // Utility class - no instantiation
  }

  /**
   * Returns the root shape for VmTyped objects.
   *
   * <p>This is the base shape from which all VmTyped instance shapes derive. It contains no
   * predefined properties - those are added dynamically as properties are cached.
   */
  public static Shape getRootShape() {
    return ROOT_SHAPE;
  }

  /**
   * Creates a new shape by adding a property to an existing shape.
   *
   * <p>This is used when caching a property value for the first time. The shape transition is
   * tracked by Truffle for inline caching optimization.
   *
   * @param currentShape the current shape of the object
   * @param key the property key (typically an {@link Identifier})
   * @return a new shape with the property added
   */
  public static Shape addProperty(Shape currentShape, Object key) {
    return currentShape.defineProperty(key, null, 0);
  }

  /**
   * Gets the uncached DynamicObjectLibrary for direct object access.
   *
   * <p>This should only be used in slow paths (e.g., interpreter) or when creating objects. For
   * fast property access in compiled code, use a cached {@link DynamicObjectLibrary} instance via
   * Truffle DSL's {@code @CachedLibrary}.
   */
  public static DynamicObjectLibrary getUncachedLibrary() {
    return DynamicObjectLibrary.getUncached();
  }
}
