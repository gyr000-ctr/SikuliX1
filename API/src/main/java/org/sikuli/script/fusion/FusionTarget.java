/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes what the fusion locator should find: a combination of
 * template image, expected text, anchors and structural constraints.
 * <p>
 * At least a template or a text must be given. All signals are fused
 * into one confidence value per candidate; see {@link FusionFinder}.
 * <p>
 * Defaults are chosen so that a target with only a template behaves
 * like a classic single image find (one scale, no inversion, similarity
 * threshold 0.7) — existing behavior is unchanged unless extra signals
 * or options are added explicitly.
 */
public class FusionTarget {

  /**
   * maximal number of DPI scale factors evaluated per locate (bounded work)
   */
  public static final int MAX_SCALES = 4;

  /**
   * where the text is expected relative to the template match
   */
  public enum TextLayout {
    ANY, INSIDE, LEFT_OF, RIGHT_OF, ABOVE, BELOW
  }

  /**
   * An anchor: a landmark (image or text) at a known offset relative to
   * the target's top-left corner. Anchors stabilize localization when
   * the template itself is occluded, scaled or theme-shifted.
   */
  public static class Anchor {
    public final BufferedImage image;
    public final String text;
    public final int dx;
    public final int dy;
    public final int tolerance;

    private Anchor(BufferedImage image, String text, int dx, int dy, int tolerance) {
      this.image = image;
      this.text = text;
      this.dx = dx;
      this.dy = dy;
      this.tolerance = tolerance;
    }

    public static Anchor image(BufferedImage img, int dx, int dy, int tolerance) {
      if (img == null) {
        throw new IllegalArgumentException("anchor image must not be null");
      }
      return new Anchor(img, null, dx, dy, Math.max(1, tolerance));
    }

    public static Anchor text(String text, int dx, int dy, int tolerance) {
      if (text == null || text.isEmpty()) {
        throw new IllegalArgumentException("anchor text must not be empty");
      }
      return new Anchor(null, text, dx, dy, Math.max(1, tolerance));
    }
  }

  public final BufferedImage template;
  public final String text;
  public final boolean textRegex;
  public final TextLayout textLayout;
  public final int textMaxGap;
  public final List<Anchor> anchors;
  public final Rectangle within;
  public final double minConfidence;
  public final double minMargin;
  public final double timeout;
  public final double[] scales;
  public final boolean autoInvert;
  public final int maxCandidates;
  public final double weightTemplate;
  public final double weightText;
  public final double weightAnchor;

  private FusionTarget(Builder b) {
    this.template = b.template;
    this.text = b.text;
    this.textRegex = b.textRegex;
    this.textLayout = b.textLayout;
    this.textMaxGap = b.textMaxGap;
    this.anchors = Collections.unmodifiableList(new ArrayList<>(b.anchors));
    this.within = b.within;
    this.minConfidence = b.minConfidence;
    this.minMargin = b.minMargin;
    this.timeout = b.timeout;
    this.scales = b.scales;
    this.autoInvert = b.autoInvert;
    this.maxCandidates = b.maxCandidates;
    this.weightTemplate = b.weightTemplate;
    this.weightText = b.weightText;
    this.weightAnchor = b.weightAnchor;
    if (template == null && text == null) {
      throw new IllegalArgumentException("FusionTarget: at least a template or a text must be given");
    }
  }

  public boolean hasTemplate() {
    return template != null;
  }

  public boolean hasText() {
    return text != null;
  }

  public boolean hasAnchors() {
    return !anchors.isEmpty();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * a target with only a template image (behaves like classic image find)
   */
  public static FusionTarget of(BufferedImage template) {
    return builder().template(template).build();
  }

  /**
   * a target with only a text (behaves like classic text find)
   */
  public static FusionTarget of(String text) {
    return builder().text(text).build();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("FusionTarget[");
    if (hasTemplate()) {
      sb.append("template ").append(template.getWidth()).append("x").append(template.getHeight());
    }
    if (hasText()) {
      if (hasTemplate()) {
        sb.append(", ");
      }
      sb.append("text \"").append(text).append("\"");
    }
    if (hasAnchors()) {
      sb.append(", ").append(anchors.size()).append(" anchors");
    }
    return sb.append("]").toString();
  }

  public static class Builder {
    private BufferedImage template;
    private String text;
    private boolean textRegex = false;
    private TextLayout textLayout = TextLayout.ANY;
    private int textMaxGap = 20;
    private final List<Anchor> anchors = new ArrayList<>();
    private Rectangle within = null;
    private double minConfidence = 0.7;
    private double minMargin = 0.05;
    private double timeout = 3.0;
    private double[] scales = new double[]{1.0};
    private boolean autoInvert = false;
    private int maxCandidates = 10;
    private double weightTemplate = 0.5;
    private double weightText = 0.3;
    private double weightAnchor = 0.2;

    public Builder template(BufferedImage template) {
      this.template = template;
      return this;
    }

    /**
     * expected text of the target (plain, case-insensitive contains/equals
     * with fuzzy tolerance for OCR noise)
     */
    public Builder text(String text) {
      this.text = text;
      this.textRegex = false;
      return this;
    }

    /**
     * expected text as regular expression — use for dynamic text
     * (e.g. counters, timestamps) where only the shape is stable
     */
    public Builder textRegex(String regex) {
      this.text = regex;
      this.textRegex = true;
      return this;
    }

    /**
     * structural constraint: where the text must be relative to the template match
     */
    public Builder textLayout(TextLayout layout, int maxGap) {
      this.textLayout = layout == null ? TextLayout.ANY : layout;
      this.textMaxGap = Math.max(0, maxGap);
      return this;
    }

    public Builder anchor(Anchor anchor) {
      this.anchors.add(anchor);
      return this;
    }

    /**
     * restricts candidates to this rectangle (relative to the search region)
     */
    public Builder within(Rectangle within) {
      this.within = within;
      return this;
    }

    public Builder minConfidence(double minConfidence) {
      if (minConfidence < 0 || minConfidence > 1) {
        throw new IllegalArgumentException("minConfidence must be in 0..1");
      }
      this.minConfidence = minConfidence;
      return this;
    }

    /**
     * minimal confidence gap between the best and the second best candidate;
     * below that the result is ambiguous and not clicked
     */
    public Builder minMargin(double minMargin) {
      if (minMargin < 0 || minMargin > 1) {
        throw new IllegalArgumentException("minMargin must be in 0..1");
      }
      this.minMargin = minMargin;
      return this;
    }

    /**
     * overall time budget in seconds (wait/retry included), must be &gt;= 0
     */
    public Builder timeout(double timeout) {
      if (timeout < 0) {
        throw new IllegalArgumentException("timeout must be >= 0");
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * DPI scale factors to try for the template (e.g. 1.0, 1.25, 1.5).
     * Bounded to {@link #MAX_SCALES}; default is only 1.0 (no rescaling).
     */
    public Builder scales(double... scales) {
      if (scales == null || scales.length == 0) {
        throw new IllegalArgumentException("at least one scale factor is needed");
      }
      if (scales.length > MAX_SCALES) {
        throw new IllegalArgumentException("at most " + MAX_SCALES + " scale factors are allowed");
      }
      for (double s : scales) {
        if (s < 0.25 || s > 4.0) {
          throw new IllegalArgumentException("scale factors must be in 0.25..4.0");
        }
      }
      this.scales = scales.clone();
      return this;
    }

    /**
     * if true, one extra matching pass with inverted brightness is tried
     * when the normal pass stays below minConfidence (dark theme support)
     */
    public Builder autoInvert(boolean autoInvert) {
      this.autoInvert = autoInvert;
      return this;
    }

    public Builder maxCandidates(int maxCandidates) {
      this.maxCandidates = Math.max(1, Math.min(50, maxCandidates));
      return this;
    }

    /**
     * relative weights of the signals; normalized internally over the
     * signals actually configured
     */
    public Builder weights(double template, double text, double anchor) {
      if (template < 0 || text < 0 || anchor < 0 || template + text + anchor <= 0) {
        throw new IllegalArgumentException("weights must be >= 0 and not all 0");
      }
      this.weightTemplate = template;
      this.weightText = text;
      this.weightAnchor = anchor;
      return this;
    }

    public FusionTarget build() {
      return new FusionTarget(this);
    }
  }
}
