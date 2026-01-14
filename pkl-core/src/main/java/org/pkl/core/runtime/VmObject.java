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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import java.util.*;
import java.util.function.BiFunction;
import org.graalvm.collections.UnmodifiableEconomicMap;
import org.pkl.core.ast.member.ObjectMember;
import org.pkl.core.util.CollectionUtils;
import org.pkl.core.util.DynamicObjectMapCursor;
import org.pkl.core.util.EconomicMaps;
import org.pkl.core.util.MapCursor;
import org.pkl.core.util.Nullable;
import org.pkl.core.util.PhaseTimer;

/**
 * Corresponds to `pkl.base#Object`.
 *
 * <p>Extends {@link DynamicObject} to leverage Truffle's optimized object storage and inline
 * caching capabilities. Cached property values are stored directly in this object using the Dynamic
 * Object Model.
 */
public abstract class VmObject extends DynamicObject implements VmObjectLike {
  // Moved from VmObjectLike
  protected final MaterializedFrame enclosingFrame;
  protected @Nullable Object extraStorage;

  @CompilationFinal protected @Nullable VmObject parent;
  protected final UnmodifiableEconomicMap<Object, ObjectMember> members;

  protected int cachedHash;
  private boolean forced;

  /**
   * Separate cache for local property values, keyed by ObjectMember identity.
   *
   * <p>Local properties need identity-based keying because the same property name can exist at
   * different declaration sites in an amends chain (e.g., foo1.l and foo2.l where foo2 amends
   * foo1). Using ObjectMember as the key ensures that reads from different declaration sites don't
   * collide.
   *
   * <p>This is kept separate from the DynamicObject storage to avoid shape transitions caused by
   * using ObjectMember objects as keys, which would destroy cache locality.
   */
  private @Nullable IdentityHashMap<ObjectMember, Object> localPropertyCache;

  // --- Forced flag accessors ---

  /**
   * Returns whether this object has been fully forced.
   *
   * @return true if this object has been forced
   */
  protected boolean isForced() {
    return forced;
  }

  /**
   * Sets whether this object has been fully forced.
   *
   * @param forced true to mark as forced, false to reset
   */
  protected void setForced(boolean forced) {
    this.forced = forced;
  }

  protected VmObject(
      Shape shape,
      MaterializedFrame enclosingFrame,
      @Nullable VmObject parent,
      UnmodifiableEconomicMap<Object, ObjectMember> members) {
    super(shape);
    this.enclosingFrame = enclosingFrame;
    this.parent = parent;
    this.members = members;
    assert parent != this;
  }

  // --- VmObjectLike implementation for enclosingFrame/extraStorage ---

  @Override
  public final MaterializedFrame getEnclosingFrame() {
    return enclosingFrame;
  }

  @Override
  public final @Nullable Object getExtraStorage() {
    return extraStorage;
  }

  @Override
  public final void setExtraStorage(@Nullable Object extraStorage) {
    this.extraStorage = extraStorage;
  }

  // --- Parent and members ---

  public final void lateInitParent(VmObject parent) {
    assert this.parent == null;
    this.parent = parent;
  }

  @Override
  public @Nullable VmObject getParent() {
    return parent;
  }

  @Override
  @TruffleBoundary
  public final boolean hasMember(Object key) {
    return EconomicMaps.containsKey(members, key);
  }

  @Override
  @TruffleBoundary
  public final @Nullable ObjectMember getMember(Object key) {
    return EconomicMaps.get(members, key);
  }

  @Override
  public final UnmodifiableEconomicMap<Object, ObjectMember> getMembers() {
    return members;
  }

  // --- Cached value operations using DynamicObject storage ---

  @Override
  @TruffleBoundary
  public @Nullable Object getCachedValue(Object key) {
    return DynamicObjectLibrary.getUncached().getOrDefault(this, key, null);
  }

  @Override
  @TruffleBoundary
  public void setCachedValue(Object key, Object value) {
    DynamicObjectLibrary.getUncached().put(this, key, value);
  }

  @Override
  @TruffleBoundary
  public boolean hasCachedValue(Object key) {
    return DynamicObjectLibrary.getUncached().containsKey(this, key);
  }

  @Override
  public MapCursor<Object, Object> getCachedValueEntries() {
    return new DynamicObjectMapCursor(this);
  }

  @Override
  @TruffleBoundary
  public int getCachedValueCount() {
    return DynamicObjectLibrary.getUncached().getKeyArray(this).length;
  }

  /**
   * Clean all cached values. Local or otherwise. Resets cached values to null without removing the
   * keys, preserving the object's shape for pre-allocated slots.
   */
  @TruffleBoundary
  public void cleanAllCachedValues() {
    // Clear local property cache
    if (localPropertyCache != null) {
      localPropertyCache.clear();
    }

    // Reset DynamicObject storage values to null (preserving keys/shape)
    var lib = DynamicObjectLibrary.getUncached();
    Object[] keys = lib.getKeyArray(this);
    for (Object key : keys) {
      lib.put(this, key, null);
    }

    // Reset forced flag since values are no longer cached
    forced = false;
  }

  // --- Local property cache operations ---
  // These use a separate IdentityHashMap to avoid shape transitions from ObjectMember keys.

  /**
   * Gets a cached local property value.
   *
   * @param property the ObjectMember representing the local property declaration
   * @return the cached value, or null if not cached
   */
  @TruffleBoundary
  public @Nullable Object getLocalCachedValue(ObjectMember property) {
    return localPropertyCache == null ? null : localPropertyCache.get(property);
  }

  /**
   * Sets a cached local property value.
   *
   * @param property the ObjectMember representing the local property declaration
   * @param value the value to cache
   */
  @TruffleBoundary
  public void setLocalCachedValue(ObjectMember property, Object value) {
    if (localPropertyCache == null) {
      // Start small since most objects have few local properties
      localPropertyCache = new IdentityHashMap<>(4);
    }
    localPropertyCache.put(property, value);
  }

  @Override
  @TruffleBoundary
  public final boolean iterateMemberValues(VmObjectLike.MemberValueConsumer consumer) {
    var visited = new HashSet<>();
    return iterateMembers(
        (key, member) -> {
          var alreadyVisited = !visited.add(key);
          // important to record hidden member as visited before skipping it
          // because any overriding member won't carry a `hidden` identifier
          if (alreadyVisited || member.isLocalOrExternalOrHidden()) return true;
          return consumer.accept(key, member, getCachedValue(key));
        });
  }

  @Override
  @TruffleBoundary
  public final boolean forceAndIterateMemberValues(
      VmObjectLike.ForcedMemberValueConsumer consumer) {
    force(false, false);
    return iterateAlreadyForcedMemberValues(consumer);
  }

  @Override
  @TruffleBoundary
  public final boolean iterateAlreadyForcedMemberValues(
      VmObjectLike.ForcedMemberValueConsumer consumer) {
    var visited = new HashSet<>();
    var iterStart = PhaseTimer.start();
    var result =
        iterateMembers(
            (key, member) -> {
              var alreadyVisited = !visited.add(key);
              // important to record hidden member as visited before skipping it
              // because any overriding member won't carry a `hidden` identifier
              if (alreadyVisited || member.isLocalOrExternalOrHidden()) return true;
              var getCachedStart = PhaseTimer.start();
              Object cachedValue = getCachedValue(key);
              PhaseTimer.end(PhaseTimer.Phase.VMOBJECT_GET_CACHED, getCachedStart);
              assert cachedValue != null; // forced
              return consumer.accept(key, member, cachedValue);
            });
    PhaseTimer.end(PhaseTimer.Phase.VMOBJECT_ITERATE_MEMBERS, iterStart);
    return result;
  }

  @Override
  @TruffleBoundary
  public final boolean iterateMembers(BiFunction<Object, ObjectMember, Boolean> consumer) {
    var parent = getParent();
    if (parent != null) {
      var completed = parent.iterateMembers(consumer);
      if (!completed) return false;
    }
    var entries = members.getEntries();
    while (entries.advance()) {
      var member = entries.getValue();
      if (member.isLocal()) continue;
      if (!consumer.apply(entries.getKey(), member)) return false;
    }
    return true;
  }

  /** Evaluates this object's members. Skips local, hidden, and external members. */
  @Override
  @TruffleBoundary
  public void force(boolean allowUndefinedValues, boolean recurse) {
    if (forced) return;

    var forceStart = PhaseTimer.start();
    if (recurse) forced = true;

    // Use cached call node from this object's class to avoid getUncached() overhead
    var callNode = getVmClass().getCachedCallNode();

    try {
      for (VmObjectLike owner = this; owner != null; owner = owner.getParent()) {
        var cursor = EconomicMaps.getEntries(owner.getMembers());
        var clazz = owner.getVmClass();
        while (cursor.advance()) {
          var memberKey = cursor.getKey();
          var member = cursor.getValue();
          // isAbstract() can occur when VmAbstractObject.toString() is called
          // on a prototype of an abstract class (e.g., in the Java debugger)
          if (member.isLocalOrExternalOrAbstract() || clazz.isHiddenProperty(memberKey)) {
            continue;
          }

          var getCachedStart = PhaseTimer.start();
          var memberValue = getCachedValue(memberKey);
          PhaseTimer.end(PhaseTimer.Phase.VMOBJECT_GET_CACHED, getCachedStart);

          if (memberValue == null) {
            try {
              var readStart = PhaseTimer.start();
              memberValue = VmUtils.doReadMember(this, owner, memberKey, member, true, callNode);
              PhaseTimer.end(PhaseTimer.Phase.VMOBJECT_READ_MEMBER, readStart);
            } catch (VmUndefinedValueException e) {
              if (!allowUndefinedValues) throw e;
              continue;
            }
          }

          if (recurse) {
            VmValue.force(memberValue, allowUndefinedValues);
          }
        }
      }
    } catch (Throwable t) {
      forced = false;
      throw t;
    } finally {
      PhaseTimer.end(PhaseTimer.Phase.VMOBJECT_FORCE, forceStart);
    }
  }

  @Override
  public final void force(boolean allowUndefinedValues) {
    force(allowUndefinedValues, true);
  }

  public final String toString() {
    force(true, true);
    return VmValueRenderer.singleLine(Integer.MAX_VALUE).render(this);
  }

  /**
   * Exports this object's members. Skips local members, hidden members, class definitions, and type
   * aliases. Members that haven't been forced have a `null` value.
   */
  @TruffleBoundary
  protected final Map<String, Object> exportMembers() {
    var result = CollectionUtils.<String, Object>newLinkedHashMap(getCachedValueCount());

    iterateMemberValues(
        (key, member, value) -> {
          if (member.isClass() || member.isTypeAlias()) return true;

          result.put(key.toString(), VmValue.exportNullable(value));
          return true;
        });

    return result;
  }
}
