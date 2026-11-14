/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.awt.image.BufferedImage;

/**
 * Provides the pixels the fusion locator works on.
 * <p>
 * Implementations capture the current content of the search region
 * (usually a screen region). One capture is taken per locate attempt
 * and reused for all signals (template, OCR, anchors) of that attempt,
 * so signals are always evaluated on the same screen state.
 */
public interface ScreenSource {

  /**
   * captures the current content of the search region
   *
   * @return the pixels of the search region, never null
   */
  BufferedImage capture();
}
