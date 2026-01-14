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
package org.pkl.core.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class for collecting phase timing metrics during Pkl execution.
 *
 * <p>Enable by setting the environment variable or system property {@code PKL_PHASE_TIMING} to a
 * file path where timing results should be written.
 *
 * <p>Example: {@code PKL_PHASE_TIMING=/tmp/pkl-timing.txt pkl eval -m .out file.pkl}
 */
public final class PhaseTimer {

  /** Phases that can be timed. */
  public enum Phase {
    /** Total time for writeMultipleFileOutput or writeOutput */
    TOTAL,
    /** Module resolution (resolving module key from source) */
    MODULE_RESOLUTION,
    /** Parsing Pkl source to syntax tree */
    PARSING,
    /** Building Truffle AST from syntax tree */
    AST_BUILDING,
    /** Module initialization (executing module body) */
    MODULE_INIT,
    /** Evaluating output.files or output.bytes */
    EVALUATION,
    /** Rendering values to output format */
    RENDERING,
    /** Writing files to disk */
    FILE_IO,
    /** Force (materializing lazy values) */
    FORCE,

    // Render sub-phases (for detailed profiling)
    /** Time spent in converter.convert() during rendering */
    RENDER_CONVERSION,
    /** Time spent visiting VmTyped objects */
    RENDER_VISIT_TYPED,
    /** Time spent visiting VmMapping objects */
    RENDER_VISIT_MAPPING,
    /** Time spent visiting VmListing objects */
    RENDER_VISIT_LISTING,
    /** Time spent visiting VmDynamic objects */
    RENDER_VISIT_DYNAMIC,
    /** Time spent escaping/emitting strings */
    RENDER_STRING_EMIT,
    /** Time spent iterating object members */
    RENDER_ITERATION,
    /** Time spent in StringBuilder operations */
    RENDER_STRING_BUILD,

    // VmObject sub-phases (for narrowing down)
    /** Time spent in VmObject.force() */
    VMOBJECT_FORCE,
    /** Time spent in iterateMembers (parent traversal + local) */
    VMOBJECT_ITERATE_MEMBERS,
    /** Time spent getting cached values */
    VMOBJECT_GET_CACHED,
    /** Time spent in VmUtils.doReadMember */
    VMOBJECT_READ_MEMBER
  }

  private static volatile @Nullable PhaseTimer instance;

  /** Gets the singleton instance, creating it lazily on first access. */
  private static PhaseTimer getInstance() {
    if (instance == null) {
      synchronized (PhaseTimer.class) {
        if (instance == null) {
          instance = new PhaseTimer();
        }
      }
    }
    return instance;
  }

  private final @Nullable String outputPath;
  private final boolean enabled;

  /** Per-phase accumulated time in nanoseconds. */
  private final ConcurrentHashMap<Phase, AtomicLong> phaseTimes = new ConcurrentHashMap<>();

  /** Per-phase invocation count. */
  private final ConcurrentHashMap<Phase, AtomicLong> phaseCounts = new ConcurrentHashMap<>();

  /** Start time of current run, for computing total wall time. */
  private volatile long runStartTime = 0;

  /** Constructor that reads configuration at runtime (not native-image build time). */
  private PhaseTimer() {
    String path = System.getenv("PKL_PHASE_TIMING");
    if (path == null) {
      path = System.getProperty("pkl.phaseTiming");
    }
    this.outputPath = path;
    this.enabled = path != null && !path.isEmpty();
  }

  /** Returns true if phase timing is enabled. */
  public static boolean isEnabled() {
    return getInstance().enabled;
  }

  /** Marks the start of a Pkl run. Call this at the beginning of evaluation. */
  public static void startRun() {
    var timer = getInstance();
    if (!timer.enabled) return;
    timer.runStartTime = System.nanoTime();
    timer.phaseTimes.clear();
    timer.phaseCounts.clear();
  }

  /**
   * Records time spent in a phase.
   *
   * @param phase the phase
   * @param nanos time in nanoseconds
   */
  public static void record(Phase phase, long nanos) {
    var timer = getInstance();
    if (!timer.enabled) return;
    timer.phaseTimes.computeIfAbsent(phase, k -> new AtomicLong()).addAndGet(nanos);
    timer.phaseCounts.computeIfAbsent(phase, k -> new AtomicLong()).incrementAndGet();
  }

  /**
   * Starts timing a phase. Returns the start time (System.nanoTime()). Use with {@link #end(Phase,
   * long)} to record the elapsed time.
   */
  public static long start() {
    if (!getInstance().enabled) return 0;
    return System.nanoTime();
  }

  /**
   * Ends timing a phase and records the elapsed time.
   *
   * @param phase the phase
   * @param startTime the value returned by {@link #start()}
   */
  public static void end(Phase phase, long startTime) {
    if (!getInstance().enabled) return;
    long elapsed = System.nanoTime() - startTime;
    record(phase, elapsed);
  }

  private long getTime(Phase phase) {
    AtomicLong time = phaseTimes.get(phase);
    return time != null ? time.get() : 0;
  }

  private long getCount(Phase phase) {
    AtomicLong count = phaseCounts.get(phase);
    return count != null ? count.get() : 0;
  }

  /**
   * Ends a Pkl run and writes timing results to the configured output file. Call this at the end of
   * evaluation.
   */
  public static void endRun() {
    getInstance().doEndRun();
  }

  private void doEndRun() {
    if (!enabled || outputPath == null) return;

    long totalWallTime = System.nanoTime() - runStartTime;

    try {
      Path outPath = Path.of(outputPath);
      try (PrintWriter writer =
          new PrintWriter(
              Files.newBufferedWriter(
                  outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

        writer.println("=== Pkl Phase Timing Results ===");
        writer.println();
        writer.printf(
            "Total wall time: %.3f ms (%.3f s)%n",
            totalWallTime / 1_000_000.0, totalWallTime / 1_000_000_000.0);
        writer.println();

        // Raw phase times (may overlap)
        writer.println("Raw phase times (may overlap):");
        writer.println("-".repeat(70));
        writer.printf(
            "%-20s %12s %12s %12s %8s%n", "Phase", "Total (ms)", "Count", "Avg (ms)", "% Wall");
        writer.println("-".repeat(70));

        for (Phase phase : Phase.values()) {
          long time = getTime(phase);
          long cnt = getCount(phase);
          if (time > 0) {
            double timeMs = time / 1_000_000.0;
            double avgMs = cnt > 0 ? timeMs / cnt : 0;
            double pctWall = totalWallTime > 0 ? (time * 100.0 / totalWallTime) : 0;
            writer.printf(
                "%-20s %12.3f %12d %12.3f %7.1f%%%n", phase.name(), timeMs, cnt, avgMs, pctWall);
          }
        }
        writer.println("-".repeat(70));

        // Calculate non-overlapping (exclusive) times
        // Phase nesting: FILE_IO contains RENDERING
        // EVALUATION measures just the output reading, not module loading
        // Module loading phases (PARSING, AST_BUILDING, MODULE_INIT) are separate
        writer.println();
        writer.println("Exclusive times (non-overlapping):");
        writer.println("-".repeat(70));
        writer.printf("%-20s %12s %8s%n", "Phase", "Time (ms)", "% Wall");
        writer.println("-".repeat(70));

        long parsing = getTime(Phase.PARSING);
        long astBuilding = getTime(Phase.AST_BUILDING);
        long moduleInit = getTime(Phase.MODULE_INIT);
        long moduleResolution = getTime(Phase.MODULE_RESOLUTION);
        long evaluation = getTime(Phase.EVALUATION);
        long rendering = getTime(Phase.RENDERING);
        long fileIo = getTime(Phase.FILE_IO);
        long force = getTime(Phase.FORCE);

        // Disk I/O = FILE_IO - RENDERING (since rendering happens inside FILE_IO)
        long diskIo = fileIo - rendering;
        if (diskIo < 0) diskIo = 0; // safety check

        // Module loading = PARSING + AST_BUILDING + MODULE_INIT
        long moduleLoading = parsing + astBuilding + moduleInit;

        // Print exclusive phases
        printExclusive(writer, "Module Resolution", moduleResolution, totalWallTime);
        printExclusive(writer, "Parsing", parsing, totalWallTime);
        printExclusive(writer, "AST Building", astBuilding, totalWallTime);
        printExclusive(writer, "Module Init", moduleInit, totalWallTime);
        printExclusive(writer, "Evaluation", evaluation, totalWallTime);
        printExclusive(writer, "Rendering", rendering, totalWallTime);
        printExclusive(writer, "Disk I/O", diskIo, totalWallTime);
        if (force > 0) {
          printExclusive(writer, "Force", force, totalWallTime);
        }

        writer.println("-".repeat(70));

        // Calculate accounted time (non-overlapping)
        long accountedTime = moduleResolution + moduleLoading + evaluation + rendering + diskIo;
        long unaccounted = totalWallTime - accountedTime;

        printExclusive(writer, "ACCOUNTED", accountedTime, totalWallTime);
        printExclusive(writer, "UNACCOUNTED", unaccounted, totalWallTime);

        writer.println("-".repeat(70));

        // Summary
        writer.println();
        writer.println("Summary:");
        writer.printf(
            "  Module loading (parse+AST+init): %12.3f ms (%5.1f%%)%n",
            moduleLoading / 1_000_000.0, moduleLoading * 100.0 / totalWallTime);
        writer.printf(
            "  Evaluation (force+read output):  %12.3f ms (%5.1f%%)%n",
            evaluation / 1_000_000.0, evaluation * 100.0 / totalWallTime);
        writer.printf(
            "  Rendering:                       %12.3f ms (%5.1f%%)%n",
            rendering / 1_000_000.0, rendering * 100.0 / totalWallTime);
        writer.printf(
            "  Disk I/O:                        %12.3f ms (%5.1f%%)%n",
            diskIo / 1_000_000.0, diskIo * 100.0 / totalWallTime);
        writer.printf(
            "  Unaccounted (JIT, GC, other):    %12.3f ms (%5.1f%%)%n",
            unaccounted / 1_000_000.0, unaccounted * 100.0 / totalWallTime);

        writer.println();
        writer.println("Counts:");
        writer.printf("  Modules parsed: %d%n", getCount(Phase.PARSING));
        writer.printf("  Renders: %d%n", getCount(Phase.RENDERING));
        writer.printf("  Files written: %d%n", getCount(Phase.FILE_IO));

        // Render sub-phase breakdown (if any data collected)
        long renderConversion = getTime(Phase.RENDER_CONVERSION);
        long renderVisitTyped = getTime(Phase.RENDER_VISIT_TYPED);
        long renderVisitMapping = getTime(Phase.RENDER_VISIT_MAPPING);
        long renderVisitListing = getTime(Phase.RENDER_VISIT_LISTING);
        long renderVisitDynamic = getTime(Phase.RENDER_VISIT_DYNAMIC);
        long renderStringEmit = getTime(Phase.RENDER_STRING_EMIT);
        long renderIteration = getTime(Phase.RENDER_ITERATION);
        long renderStringBuild = getTime(Phase.RENDER_STRING_BUILD);

        long totalRenderSub =
            renderConversion
                + renderVisitTyped
                + renderVisitMapping
                + renderVisitListing
                + renderVisitDynamic
                + renderStringEmit
                + renderIteration
                + renderStringBuild;

        if (totalRenderSub > 0) {
          writer.println();
          writer.println("Render sub-phases (may overlap):");
          writer.println("-".repeat(70));
          writer.printf(
              "%-20s %12s %12s %12s %8s%n",
              "Sub-phase", "Total (ms)", "Count", "Avg (us)", "% Render");
          writer.println("-".repeat(70));

          printRenderSubPhase(
              writer, "Conversion", renderConversion, getCount(Phase.RENDER_CONVERSION), rendering);
          printRenderSubPhase(
              writer,
              "Visit Typed",
              renderVisitTyped,
              getCount(Phase.RENDER_VISIT_TYPED),
              rendering);
          printRenderSubPhase(
              writer,
              "Visit Mapping",
              renderVisitMapping,
              getCount(Phase.RENDER_VISIT_MAPPING),
              rendering);
          printRenderSubPhase(
              writer,
              "Visit Listing",
              renderVisitListing,
              getCount(Phase.RENDER_VISIT_LISTING),
              rendering);
          printRenderSubPhase(
              writer,
              "Visit Dynamic",
              renderVisitDynamic,
              getCount(Phase.RENDER_VISIT_DYNAMIC),
              rendering);
          printRenderSubPhase(
              writer,
              "String Emit",
              renderStringEmit,
              getCount(Phase.RENDER_STRING_EMIT),
              rendering);
          printRenderSubPhase(
              writer, "Iteration", renderIteration, getCount(Phase.RENDER_ITERATION), rendering);
          printRenderSubPhase(
              writer,
              "String Build",
              renderStringBuild,
              getCount(Phase.RENDER_STRING_BUILD),
              rendering);

          writer.println("-".repeat(70));
        }

        // VmObject sub-phase breakdown (if any data collected)
        long vmForce = getTime(Phase.VMOBJECT_FORCE);
        long vmIterateMembers = getTime(Phase.VMOBJECT_ITERATE_MEMBERS);
        long vmGetCached = getTime(Phase.VMOBJECT_GET_CACHED);
        long vmReadMember = getTime(Phase.VMOBJECT_READ_MEMBER);

        long totalVmSub = vmForce + vmIterateMembers + vmGetCached + vmReadMember;

        if (totalVmSub > 0) {
          writer.println();
          writer.println("VmObject sub-phases (may overlap):");
          writer.println("-".repeat(70));
          writer.printf(
              "%-20s %12s %12s %12s %8s%n",
              "Sub-phase", "Total (ms)", "Count", "Avg (us)", "% Render");
          writer.println("-".repeat(70));

          printRenderSubPhase(writer, "Force", vmForce, getCount(Phase.VMOBJECT_FORCE), rendering);
          printRenderSubPhase(
              writer,
              "Iterate Members",
              vmIterateMembers,
              getCount(Phase.VMOBJECT_ITERATE_MEMBERS),
              rendering);
          printRenderSubPhase(
              writer, "Get Cached", vmGetCached, getCount(Phase.VMOBJECT_GET_CACHED), rendering);
          printRenderSubPhase(
              writer, "Read Member", vmReadMember, getCount(Phase.VMOBJECT_READ_MEMBER), rendering);

          writer.println("-".repeat(70));
        }
      }

      System.err.println("[PhaseTimer] Results written to: " + outputPath);

    } catch (IOException e) {
      System.err.println("[PhaseTimer] Failed to write results: " + e.getMessage());
    }
  }

  private static void printExclusive(
      PrintWriter writer, String name, long nanos, long totalWallTime) {
    double timeMs = nanos / 1_000_000.0;
    double pctWall = totalWallTime > 0 ? (nanos * 100.0 / totalWallTime) : 0;
    writer.printf("%-20s %12.3f %7.1f%%%n", name, timeMs, pctWall);
  }

  private static void printRenderSubPhase(
      PrintWriter writer, String name, long nanos, long count, long totalRenderTime) {
    if (nanos == 0) return;
    double timeMs = nanos / 1_000_000.0;
    double avgUs = count > 0 ? (nanos / 1000.0) / count : 0;
    double pctRender = totalRenderTime > 0 ? (nanos * 100.0 / totalRenderTime) : 0;
    writer.printf("%-20s %12.3f %12d %12.3f %7.1f%%%n", name, timeMs, count, avgUs, pctRender);
  }
}
