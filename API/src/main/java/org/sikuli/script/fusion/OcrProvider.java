/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Provides OCR words for a captured image.
 * <p>
 * Implementations may throw a RuntimeException when the OCR engine is
 * not available; the fusion locator then degrades gracefully (the text
 * signal is treated as unavailable, noted as reason "ocr-unavailable")
 * instead of failing silently or guessing.
 */
public interface OcrProvider {

  /**
   * reads the words of the given image
   *
   * @param img the captured search region
   * @return recognized words with positions, maybe empty, never null
   */
  List<TextWord> readWords(BufferedImage img);
}
