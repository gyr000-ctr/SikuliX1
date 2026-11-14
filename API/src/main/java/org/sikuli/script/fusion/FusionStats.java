/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counters and timings of a {@link FusionFinder}: locate count, failure
 * count, average locate time, fallback/failure reasons and the candidate
 * ranking of the last locate.
 * <p>
 * Holds text data only — screenshots are never stored here, hence the
 * stats are safe to dump into logs.
 */
public class FusionStats {

  private long locateCount;
  private long failureCount;
  private long totalMillis;
  private final Map<String, Integer> reasons = new LinkedHashMap<>();
  private List<String> lastRanking = new ArrayList<>();

  synchronized void record(long millis, boolean success, String reason, List<FusionCandidate> ranking) {
    locateCount++;
    if (!success) {
      failureCount++;
    }
    totalMillis += millis;
    if (reason != null) {
      reasons.merge(reason, 1, Integer::sum);
    }
    List<String> snap = new ArrayList<>();
    for (int i = 0; i < ranking.size(); i++) {
      snap.add("#" + (i + 1) + " " + ranking.get(i).toString());
    }
    lastRanking = snap;
  }

  public synchronized long getLocateCount() {
    return locateCount;
  }

  public synchronized long getFailureCount() {
    return failureCount;
  }

  public synchronized double getAverageLocateMillis() {
    return locateCount == 0 ? 0 : (double) totalMillis / locateCount;
  }

  /**
   * fallback and failure reasons with their counts, e.g. ocr-fallback=3
   */
  public synchronized Map<String, Integer> getReasons() {
    return new LinkedHashMap<>(reasons);
  }

  /**
   * candidate ranking of the last locate as text lines (best first)
   */
  public synchronized List<String> getLastRanking() {
    return Collections.unmodifiableList(new ArrayList<>(lastRanking));
  }

  public synchronized void reset() {
    locateCount = 0;
    failureCount = 0;
    totalMillis = 0;
    reasons.clear();
    lastRanking = new ArrayList<>();
  }

  @Override
  public synchronized String toString() {
    return String.format("FusionStats[locates=%d failures=%d avg=%.1fms reasons=%s]",
        locateCount, failureCount, getAverageLocateMillis(), reasons);
  }
}
