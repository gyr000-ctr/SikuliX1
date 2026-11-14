/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import org.sikuli.script.Finder;
import org.sikuli.script.Match;
import org.sikuli.script.Pattern;
import org.sikuli.script.ScreenImage;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Template matcher backed by the classic SikuliX {@link Finder} (OpenCV).
 * <p>
 * This is the default in production. It reuses the exact matching
 * semantics of single image find, hence fused template scores are
 * comparable with the similarity values users already know.
 */
public class SikuliTemplateMatcher implements TemplateMatcher {

  /**
   * checks whether the OpenCV based matcher is usable in this environment
   *
   * @return true if a tiny probe match could be executed
   */
  public static boolean isAvailable() {
    try {
      BufferedImage base = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
      BufferedImage tpl = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
      new SikuliTemplateMatcher().findAll(base, tpl, 0.99, 1);
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @Override
  public List<Hit> findAll(BufferedImage base, BufferedImage template, double minScore, int maxResults) {
    List<Hit> hits = new ArrayList<>();
    Finder finder = new Finder(new ScreenImage(new Rectangle(0, 0, base.getWidth(), base.getHeight()), base));
    try {
      finder.findAll(new Pattern(template).similar(minScore));
      while (finder.hasNext() && hits.size() < maxResults) {
        Match m = finder.next();
        hits.add(new Hit(new Rectangle(m.x, m.y, m.w, m.h), m.getScore()));
      }
    } finally {
      finder.destroy();
    }
    return hits;
  }
}
