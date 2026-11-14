/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of one fusion locate: the ranked candidates, the chosen one
 * (if any), the fallback reason (if a fallback path was taken) and
 * timing information. Text data only — safe to log.
 */
public class FusionResult {

  private final List<FusionCandidate> ranking;
  private final FusionCandidate best;
  private final String fallbackReason;
  private final long elapsedMillis;
  private final int attempts;

  FusionResult(List<FusionCandidate> ranking, FusionCandidate best,
               String fallbackReason, long elapsedMillis, int attempts) {
    this.ranking = Collections.unmodifiableList(new ArrayList<>(ranking));
    this.best = best;
    this.fallbackReason = fallbackReason;
    this.elapsedMillis = elapsedMillis;
    this.attempts = attempts;
  }

  public boolean isSuccess() {
    return best != null;
  }

  /**
   * the chosen candidate or null if the locate failed
   */
  public FusionCandidate getBest() {
    return best;
  }

  /**
   * all candidates, best first (may be empty on failure)
   */
  public List<FusionCandidate> getRanking() {
    return ranking;
  }

  /**
   * why a fallback path was taken (e.g. "ocr-fallback", "theme-inverted",
   * "dpi-scaled") or null on the primary path
   */
  public String getFallbackReason() {
    return fallbackReason;
  }

  public long getElapsedMillis() {
    return elapsedMillis;
  }

  public int getAttempts() {
    return attempts;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("FusionResult[success=%s attempts=%d elapsed=%dms",
        isSuccess(), attempts, elapsedMillis));
    if (fallbackReason != null) {
      sb.append(" fallback=").append(fallbackReason);
    }
    sb.append("]");
    for (int i = 0; i < ranking.size(); i++) {
      sb.append(String.format("%n  #%d %s", i + 1, ranking.get(i)));
    }
    return sb.toString();
  }
}
