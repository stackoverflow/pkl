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
package org.pkl.core.ast.member;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import org.pkl.core.ast.PklNode;
import org.pkl.core.runtime.*;

/**
 * Truffle node for forcing VmTyped objects with PE-friendly iteration.
 *
 * <p>This node uses class-level forcible member arrays (shared by all instances) for better cache
 * locality and reduced memory overhead. The chain owner is computed at force time by walking up
 * from the receiver.
 *
 * <p>The node uses length-based caching: objects with the same number of forcible members share
 * specializations, enabling {@code @ExplodeLoop} optimization regardless of the specific class.
 */
public abstract class ForceTypedNode extends PklNode {

  /** Cached CallTarget for invoking force from outside the AST. */
  private static volatile RootCallTarget cachedCallTarget;

  /**
   * Gets the CallTarget for invoking force from outside the AST.
   *
   * <p>The ForceTypedNode is wrapped in a RootNode so it can be properly adopted, which is required
   * for @CachedLibrary to work.
   *
   * @return the CallTarget for invoking force
   */
  public static RootCallTarget getCallTarget() {
    if (cachedCallTarget == null) {
      synchronized (ForceTypedNode.class) {
        if (cachedCallTarget == null) {
          cachedCallTarget = new ForceTypedRootNode().getCallTarget();
        }
      }
    }
    return cachedCallTarget;
  }

  /**
   * Forces all members of the given VmTyped object.
   *
   * @param object the VmTyped to force
   * @param allowUndefinedValues if true, undefined values are skipped; if false, they throw
   * @param recurse if true, recursively force member values
   */
  public abstract void execute(VmTyped object, boolean allowUndefinedValues, boolean recurse);

  /**
   * Creates a new ForceTypedNode.
   *
   * @return a new ForceTypedNode instance
   */
  public static ForceTypedNode create() {
    return ForceTypedNodeGen.create();
  }

  /**
   * Fast path: Cached class and length enables {@code @ExplodeLoop} for loop unrolling.
   *
   * <p>Creates specializations for classes with different numbers of forcible members (up to 8
   * different lengths). Classes with more than 32 members use the generic path to avoid excessive
   * code expansion.
   */
  @Specialization(
      guards = {
        "object.getVmClass() == cachedClass",
        "memberKeys.length == cachedLength",
        "cachedLength <= 32"
      },
      limit = "8")
  @ExplodeLoop
  protected void forceCached(
      VmTyped object,
      boolean allowUndefinedValues,
      boolean recurse,
      @Cached("object.getVmClass()") VmClass cachedClass,
      @Cached(value = "cachedClass.getForcibleMemberKeys()", dimensions = 1) Object[] memberKeys,
      @Cached("memberKeys.length") int cachedLength,
      @CachedLibrary(limit = "3") @Shared("objectLibrary") DynamicObjectLibrary objectLibrary,
      @Cached("create()") @Shared("callNode") IndirectCallNode callNode) {

    for (int i = 0; i < cachedLength; i++) {
      forceMember(object, memberKeys[i], allowUndefinedValues, recurse, objectLibrary, callNode);
    }
  }

  /**
   * Slow path: Generic forcing for megamorphic sites or large objects.
   *
   * <p>Used when the specialization limit is exceeded or for objects with more than 32 members.
   */
  @Specialization(replaces = "forceCached")
  protected void forceGeneric(
      VmTyped object,
      boolean allowUndefinedValues,
      boolean recurse,
      @CachedLibrary(limit = "3") @Shared("objectLibrary") DynamicObjectLibrary objectLibrary,
      @Cached("create()") @Shared("callNode") IndirectCallNode callNode) {

    Object[] memberKeys = object.getVmClass().getForcibleMemberKeys();

    for (int i = 0; i < memberKeys.length; i++) {
      forceMember(object, memberKeys[i], allowUndefinedValues, recurse, objectLibrary, callNode);
    }
  }

  /**
   * Forces a single member, handling caching and recursion.
   *
   * @param receiver the object being forced
   * @param memberKey the cache key for this member
   * @param allowUndefinedValues if true, skip undefined values
   * @param recurse if true, recursively force the member value
   * @param objectLibrary cached library for DynamicObject access
   * @param callNode cached call node for evaluating members
   */
  private void forceMember(
      VmTyped receiver,
      Object memberKey,
      boolean allowUndefinedValues,
      boolean recurse,
      DynamicObjectLibrary objectLibrary,
      IndirectCallNode callNode) {

    // Check if already cached
    Object cachedValue = receiver.getCachedValue(memberKey, objectLibrary);
    if (cachedValue != null) {
      if (recurse) {
        VmValue.force(cachedValue, allowUndefinedValues);
      }
      return;
    }

    // Read the member value - this handles all cases:
    // - Finding the correct member in the receiver's prototype chain
    // - Const member evaluation on owner
    // - Constant value type checking
    // - Computed value evaluation
    Object memberValue;
    try {
      memberValue = VmUtils.readMemberOrNull(receiver, memberKey, true, callNode);
    } catch (VmUndefinedValueException e) {
      if (!allowUndefinedValues) throw e;
      return;
    }

    if (memberValue == null) {
      // Member not found in this object's chain - skip
      return;
    }

    // Recursively force if needed
    if (recurse) {
      VmValue.force(memberValue, allowUndefinedValues);
    }
  }

  /**
   * RootNode wrapper for ForceTypedNode.
   *
   * <p>This enables ForceTypedNode to be called from outside the AST via a CallTarget, while still
   * being properly adopted so @CachedLibrary works correctly.
   *
   * <p>Arguments: [0] = VmTyped object, [1] = allowUndefinedValues (boolean), [2] = recurse
   * (boolean)
   */
  private static final class ForceTypedRootNode extends RootNode {
    @Child private ForceTypedNode forceNode = ForceTypedNode.create();

    ForceTypedRootNode() {
      super(null);
    }

    @Override
    public Object execute(VirtualFrame frame) {
      Object[] args = frame.getArguments();
      VmTyped object = (VmTyped) args[0];
      boolean allowUndefinedValues = (boolean) args[1];
      boolean recurse = (boolean) args[2];

      forceNode.execute(object, allowUndefinedValues, recurse);
      return null;
    }

    @Override
    public String getName() {
      return "ForceTyped";
    }
  }
}
