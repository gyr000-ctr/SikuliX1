/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.awt.Rectangle;

/**
 * One word recognized by OCR with its position and confidence.
 */
public class TextWord {

  public final String text;
  public final double confidence;
  public final Rectangle rect;

  public TextWord(String text, double confidence, Rectangle rect) {
    this.text = text;
    this.confidence = confidence;
    this.rect = rect;
  }

  public int centerX() {
    return rect.x + rect.width / 2;
  }

  public int centerY() {
    return rect.y + rect.height / 2;
  }

  @Override
  public String toString() {
    return String.format("Word[\"%s\" %.2f (%d,%d %dx%d)]",
        text, confidence, rect.x, rect.y, rect.width, rect.height);
  }
}
