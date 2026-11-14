/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import org.sikuli.script.Match;
import org.sikuli.script.OCR;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR provider backed by the SikuliX {@link OCR} (Tesseract) integration.
 */
public class SikuliOcrProvider implements OcrProvider {

  @Override
  public List<TextWord> readWords(BufferedImage img) {
    List<TextWord> words = new ArrayList<>();
    for (Match m : OCR.readWords(img)) {
      String text = m.getText();
      if (text == null || text.trim().isEmpty()) {
        continue;
      }
      double conf = m.getScore() > 0 ? m.getScore() : 0.5;
      words.add(new TextWord(text.trim(), conf, new Rectangle(m.x, m.y, m.w, m.h)));
    }
    return words;
  }
}
