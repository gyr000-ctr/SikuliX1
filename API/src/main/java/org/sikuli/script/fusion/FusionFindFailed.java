/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import org.sikuli.script.FindFailed;

/**
 * Thrown when a fusion locate did not find a trustworthy target within
 * the time budget. The message always states the reason (below
 * threshold, ambiguous margin, OCR unavailable, timeout) plus the best
 * candidate ranking — the locator fails explicitly instead of clicking
 * a wrong position.
 */
public class FusionFindFailed extends FindFailed {

  public FusionFindFailed(String message) {
    super(message);
  }
}
