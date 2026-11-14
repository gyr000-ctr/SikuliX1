/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Finds a template image inside a base image and scores the hits.
 * <p>
 * Implementations must be side-effect free and must not retain the
 * given images. The number of returned hits is bounded by maxResults.
 */
public interface TemplateMatcher {

  /**
   * one scored hit of a template match
   */
  class Hit {
    public final Rectangle rect;
    public final double score;

    public Hit(Rectangle rect, double score) {
      this.rect = rect;
      this.score = score;
    }

    @Override
    public String toString() {
      return String.format("Hit[(%d,%d %dx%d) %.3f]", rect.x, rect.y, rect.width, rect.height, score);
    }
  }

  /**
   * finds the template in the base image
   *
   * @param base       the image to search in
   * @param template   the image to find
   * @param minScore   minimal similarity (0..1) for a hit to be reported
   * @param maxResults maximal number of hits returned (best first)
   * @return scored hits, best first, maybe empty, never null
   */
  List<Hit> findAll(BufferedImage base, BufferedImage template, double minScore, int maxResults);
}
