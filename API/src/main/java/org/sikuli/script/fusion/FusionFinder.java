/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import org.sikuli.basics.Debug;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fused image + OCR locator.
 * <p>
 * Combines template matching, OCR text, anchors and structural
 * constraints of a {@link FusionTarget} into one confidence value per
 * candidate and returns a ranked candidate list. When the confidence is
 * too low or the result ambiguous, it falls back to OCR-only localization
 * and then to wait/retry within the target's time budget. If nothing
 * trustworthy is found, a {@link FusionFindFailed} with the reasons and
 * the candidate ranking is thrown — a wrong position is never returned.
 * <p>
 * Bounded work: one capture per attempt is reused for all signals, DPI
 * scale factors are capped ({@link FusionTarget#MAX_SCALES}), scaled and
 * inverted template variants are cached in a bounded LRU cache, anchor
 * checks run in small windows around their expected position and the
 * overall locate is bounded by the target's timeout. No full-screen
 * multi-scale scan is ever repeated per candidate evaluation.
 * <p>
 * Logging is text only (scores, coordinates, reasons) — screenshots are
 * never written to the log.
 */
public class FusionFinder {

  /**
   * raw template hit threshold; the fusion decides about acceptance
   */
  public static final double RAW_TEMPLATE_MIN = 0.4;

  /**
   * minimal text contribution for the OCR fallback path
   */
  public static final double OCR_FALLBACK_MIN = 0.85;

  private static final long RETRY_SLEEP_MILLIS = 100;
  private static final int TEMPLATE_CACHE_SIZE = 32;

  private final ScreenSource source;
  private final TemplateMatcher matcher;
  private final OcrProvider ocr;
  private final FusionStats stats = new FusionStats();

  private final Map<String, BufferedImage> templateCache =
      new LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
          return size() > TEMPLATE_CACHE_SIZE;
        }
      };

  /**
   * creates a finder with the default backends: the OpenCV based matcher
   * when available (pure Java fallback otherwise) and the SikuliX OCR
   */
  public static FusionFinder createDefault(ScreenSource source) {
    TemplateMatcher matcher = SikuliTemplateMatcher.isAvailable()
        ? new SikuliTemplateMatcher() : new JavaTemplateMatcher();
    return new FusionFinder(source, matcher, new SikuliOcrProvider());
  }

  public FusionFinder(ScreenSource source, TemplateMatcher matcher, OcrProvider ocr) {
    if (source == null || matcher == null) {
      throw new IllegalArgumentException("source and matcher must not be null");
    }
    this.source = source;
    this.matcher = matcher;
    this.ocr = ocr;
  }

  public FusionStats getStats() {
    return stats;
  }

  /**
   * locates the target, retrying within its time budget if needed
   *
   * @return the result with the ranked candidates
   * @throws FusionFindFailed if no trustworthy target was found in time
   */
  public FusionResult locate(FusionTarget target) throws FusionFindFailed {
    long start = System.currentTimeMillis();
    long deadline = start + (long) (target.timeout * 1000);
    int attempts = 0;
    List<String> notes = new ArrayList<>();
    List<FusionCandidate> lastRanking = new ArrayList<>();
    String failureReason = "no-candidates";
    while (true) {
      attempts++;
      BufferedImage capture = source.capture();
      if (capture == null) {
        throw new FusionFindFailed("FusionFindFailed: screen capture returned null for " + target);
      }
      // one evaluation pass is bounded by itself (MAX_SCALES passes,
      // maxCandidates anchor checks, bounded matcher ops) — the timeout
      // bounds the overall locate including wait/retry
      Outcome outcome = evaluate(capture, target, notes);
      if (outcome.accepted != null) {
        FusionResult result = new FusionResult(outcome.ranking, outcome.accepted,
            outcome.reason, System.currentTimeMillis() - start, attempts);
        stats.record(result.getElapsedMillis(), true, outcome.reason, outcome.ranking);
        Debug.log(3, "Fusion: locate %s -> conf=%.3f reason=%s attempts=%d elapsed=%dms",
            target, outcome.accepted.getConfidence(), outcome.reason, attempts, result.getElapsedMillis());
        return result;
      }
      lastRanking = outcome.ranking;
      failureReason = outcome.failureReason;
      if (outcome.fatal || System.currentTimeMillis() >= deadline) {
        break;
      }
      if (!notes.contains("wait-retry")) {
        notes.add("wait-retry");
      }
      try {
        Thread.sleep(Math.min(RETRY_SLEEP_MILLIS, Math.max(1, deadline - System.currentTimeMillis())));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    stats.record(elapsed, false, failureReason, lastRanking);
    StringBuilder msg = new StringBuilder();
    msg.append("FusionFindFailed: ").append(target)
        .append(String.format(" reason=%s attempts=%d elapsed=%dms", failureReason, attempts, elapsed));
    if (!notes.isEmpty()) {
      msg.append(" notes=").append(notes);
    }
    for (int i = 0; i < Math.min(3, lastRanking.size()); i++) {
      msg.append(String.format("%n  #%d %s", i + 1, lastRanking.get(i)));
    }
    Debug.log(3, "Fusion: %s", msg);
    throw new FusionFindFailed(msg.toString());
  }

  private static class Outcome {
    List<FusionCandidate> ranking = new ArrayList<>();
    FusionCandidate accepted;
    String reason;
    String failureReason = "no-candidates";
    boolean fatal = false;
  }

  private Outcome evaluate(BufferedImage cap, FusionTarget target, List<String> notes) {
    Outcome out = new Outcome();
    List<FusionCandidate> candidates = new ArrayList<>();

    // --- signal 1: template (bounded scales, optional inversion, cached variants)
    if (target.hasTemplate()) {
      List<FusionCandidate> hits = new ArrayList<>();
      double bestScore = 0;
      for (double scale : target.scales) {
        BufferedImage tpl = scaledTemplate(target.template, scale);
        for (TemplateMatcher.Hit h : matcher.findAll(cap, tpl, RAW_TEMPLATE_MIN, target.maxCandidates)) {
          FusionCandidate c = new FusionCandidate(h.rect);
          c.addContribution("template", h.score);
          if (scale != 1.0) {
            c.addNote(String.format("scale=%.2f", scale));
          }
          hits.add(c);
          bestScore = Math.max(bestScore, h.score);
        }
      }
      if (target.autoInvert && bestScore < target.minConfidence) {
        // dark theme: match the ORIGINAL template against the inverted
        // capture (inverting both sides would cancel out in NCC)
        BufferedImage invCap = invert(cap);
        for (double scale : target.scales) {
          BufferedImage tpl = scaledTemplate(target.template, scale);
          for (TemplateMatcher.Hit h : matcher.findAll(invCap, tpl, RAW_TEMPLATE_MIN, target.maxCandidates)) {
            FusionCandidate c = new FusionCandidate(h.rect);
            c.addContribution("template", h.score);
            c.addNote("inverted");
            if (scale != 1.0) {
              c.addNote(String.format("scale=%.2f", scale));
            }
            hits.add(c);
          }
        }
      }
      candidates.addAll(hits);
    }

    // --- signal 2: OCR text (once per capture, degrades gracefully)
    List<TextWord> words = null;
    if (target.hasText() || hasTextAnchor(target)) {
      if (ocr == null) {
        addNote(notes, "ocr-unavailable");
      } else {
        try {
          words = ocr.readWords(cap);
        } catch (RuntimeException e) {
          addNote(notes, "ocr-unavailable");
          Debug.log(3, "Fusion: OCR not usable: %s", e.getMessage());
        }
      }
      if (target.hasText() && words == null && !target.hasTemplate() && !target.hasAnchors()) {
        out.fatal = true; // text is the only signal and OCR is dead — retrying is pointless
      }
    }

    // --- fuse text into template candidates and create text-only candidates
    if (target.hasText() && words != null) {
      for (FusionCandidate c : candidates) {
        double best = 0;
        TextWord bestWord = null;
        for (TextWord w : words) {
          double ts = textScore(w.text, target) * w.confidence;
          if (ts > best && layoutOk(c.getRect(), w.rect, target)) {
            best = ts;
            bestWord = w;
          }
        }
        c.addContribution("text", best);
        if (bestWord != null && best > 0) {
          c.addNote("text=\"" + bestWord.text + "\"");
        }
      }
      for (TextWord w : words) {
        double ts = textScore(w.text, target) * w.confidence;
        if (ts < 0.5) {
          continue;
        }
        // text-only candidates are always kept: they are the only ones
        // eligible for the OCR fallback ("where the text actually is")
        FusionCandidate c = new FusionCandidate(w.rect);
        if (target.hasTemplate()) {
          c.addContribution("template", 0.0);
        }
        c.addContribution("text", ts);
        c.addNote("text=\"" + w.text + "\"");
        c.setFromText(true);
        candidates.add(c);
      }
    }

    // --- bound candidate count before the (more expensive) anchor checks
    // when OCR is unavailable the text signal is excluded from the weights
    // (graceful degradation) instead of counting as 0
    boolean textActive = target.hasText() && words != null;
    for (FusionCandidate c : candidates) {
      c.setConfidence(partialConfidence(c, target, textActive));
    }
    Collections.sort(candidates, (a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));
    if (candidates.size() > target.maxCandidates) {
      candidates = new ArrayList<>(candidates.subList(0, target.maxCandidates));
    }

    // --- signal 3: anchors (small windows around expected positions only)
    if (target.hasAnchors()) {
      for (FusionCandidate c : candidates) {
        double sum = 0;
        for (FusionTarget.Anchor anchor : target.anchors) {
          sum += anchorScore(cap, c.getRect(), anchor, words);
        }
        c.addContribution("anchor", sum / target.anchors.size());
      }
    }

    // --- structural constraint: within
    List<FusionCandidate> filtered = new ArrayList<>();
    int dropped = 0;
    for (FusionCandidate c : candidates) {
      if (target.within != null && !target.within.contains(centerOf(c.getRect()))) {
        dropped++;
        continue;
      }
      filtered.add(c);
    }
    if (dropped > 0) {
      addNote(notes, "within-filtered=" + dropped);
    }

    // --- final confidence and ranking
    for (FusionCandidate c : filtered) {
      c.setConfidence(partialConfidence(c, target, textActive));
    }
    Collections.sort(filtered, (a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));
    out.ranking = filtered;
    decide(out, target, words != null);
    return out;
  }

  private void decide(Outcome out, FusionTarget target, boolean ocrOk) {
    if (out.ranking.isEmpty()) {
      out.failureReason = "no-candidates";
      return;
    }
    FusionCandidate top = out.ranking.get(0);
    double margin = out.ranking.size() > 1
        ? top.getConfidence() - out.ranking.get(1).getConfidence() : 1.0;
    if (top.getConfidence() >= target.minConfidence && margin >= target.minMargin) {
      out.accepted = top;
      out.reason = primaryReason(top);
      return;
    }
    // fallback: low confidence -> OCR-only localization if the text signal is strong;
    // only genuine text candidates are eligible ("where the text actually is")
    if (target.hasText() && ocrOk) {
      FusionCandidate bestOcr = null;
      double second = 0;
      for (FusionCandidate c : out.ranking) {
        if (!c.isFromText()) {
          continue;
        }
        Double ts = c.getContributions().get("text");
        if (ts == null || ts < OCR_FALLBACK_MIN) {
          continue;
        }
        Double as = c.getContributions().get("anchor");
        if (target.hasAnchors() && (as == null || as < 1.0)) {
          continue;
        }
        if (bestOcr == null || ts > bestOcr.getContributions().get("text")) {
          if (bestOcr != null) {
            second = Math.max(second, bestOcr.getContributions().get("text"));
          }
          bestOcr = c;
        } else {
          second = Math.max(second, ts);
        }
      }
      if (bestOcr != null && bestOcr.getContributions().get("text") - second >= target.minMargin) {
        bestOcr.addNote("ocr-fallback");
        out.accepted = bestOcr;
        out.reason = "ocr-fallback";
        return;
      }
    }
    out.failureReason = top.getConfidence() < target.minConfidence ? "below-threshold" : "ambiguous-margin";
  }

  private static String primaryReason(FusionCandidate c) {
    if (c.getNotes().contains("inverted")) {
      return "theme-inverted";
    }
    for (String note : c.getNotes()) {
      if (note.startsWith("scale=")) {
        return "dpi-scaled";
      }
    }
    return null;
  }

  private double partialConfidence(FusionCandidate c, FusionTarget target, boolean textActive) {
    double acc = 0;
    double wSum = 0;
    if (target.hasTemplate()) {
      wSum += target.weightTemplate;
      Double v = c.getContributions().get("template");
      acc += target.weightTemplate * (v == null ? 0 : v);
    }
    if (textActive) {
      wSum += target.weightText;
      Double v = c.getContributions().get("text");
      acc += target.weightText * (v == null ? 0 : v);
    }
    if (target.hasAnchors()) {
      wSum += target.weightAnchor;
      Double v = c.getContributions().get("anchor");
      acc += target.weightAnchor * (v == null ? 0 : v);
    }
    return wSum <= 0 ? 0 : acc / wSum;
  }

  private double anchorScore(BufferedImage cap, Rectangle candRect, FusionTarget.Anchor anchor,
                             List<TextWord> words) {
    int expectedX = candRect.x + anchor.dx;
    int expectedY = candRect.y + anchor.dy;
    if (anchor.image != null) {
      int halfW = anchor.image.getWidth() / 2 + anchor.tolerance;
      int halfH = anchor.image.getHeight() / 2 + anchor.tolerance;
      Rectangle window = new Rectangle(expectedX - halfW, expectedY - halfH, 2 * halfW, 2 * halfH)
          .intersection(new Rectangle(0, 0, cap.getWidth(), cap.getHeight()));
      if (window.isEmpty() || window.width < anchor.image.getWidth()
          || window.height < anchor.image.getHeight()) {
        return 0;
      }
      List<TemplateMatcher.Hit> hits = matcher.findAll(
          cap.getSubimage(window.x, window.y, window.width, window.height),
          anchor.image, 0.7, 1);
      return hits.isEmpty() ? 0 : hits.get(0).score;
    }
    if (words != null) {
      double best = 0;
      for (TextWord w : words) {
        if (Math.abs(w.centerX() - expectedX) <= anchor.tolerance
            && Math.abs(w.centerY() - expectedY) <= anchor.tolerance) {
          best = Math.max(best, fuzzyEquals(w.text, anchor.text) ? 1.0 : 0);
        }
      }
      return best;
    }
    return 0;
  }

  static double textScore(String word, FusionTarget target) {
    if (word == null) {
      return 0;
    }
    if (target.textRegex) {
      try {
        return java.util.regex.Pattern.compile(target.text, java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(word).find() ? 1.0 : 0.0;
      } catch (RuntimeException e) {
        return 0;
      }
    }
    String a = word.trim().toLowerCase();
    String b = target.text.trim().toLowerCase();
    if (a.isEmpty() || b.isEmpty()) {
      return 0;
    }
    if (a.equals(b)) {
      return 1.0;
    }
    if (a.contains(b) || b.contains(a)) {
      return 0.9;
    }
    double sim = 1.0 - (double) levenshtein(a, b) / Math.max(a.length(), b.length());
    return sim >= 0.5 ? sim : 0.0;
  }

  private static boolean fuzzyEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    a = a.trim().toLowerCase();
    b = b.trim().toLowerCase();
    return a.equals(b) || a.contains(b) || b.contains(a);
  }

  static int levenshtein(String a, String b) {
    int[] prev = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      int[] cur = new int[b.length() + 1];
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      prev = cur;
    }
    return prev[b.length()];
  }

  private static boolean layoutOk(Rectangle tpl, Rectangle txt, FusionTarget target) {
    int gap = target.textMaxGap;
    switch (target.textLayout) {
      case INSIDE:
        return tpl.contains(txt.x + txt.width / 2, txt.y + txt.height / 2);
      case LEFT_OF:
        return txt.x + txt.width <= tpl.x + gap && verticalNear(tpl, txt, gap);
      case RIGHT_OF:
        return txt.x >= tpl.x + tpl.width - gap && verticalNear(tpl, txt, gap);
      case ABOVE:
        return txt.y + txt.height <= tpl.y + gap && horizontalNear(tpl, txt, gap);
      case BELOW:
        return txt.y >= tpl.y + tpl.height - gap && horizontalNear(tpl, txt, gap);
      default:
        return true;
    }
  }

  private static boolean verticalNear(Rectangle tpl, Rectangle txt, int gap) {
    return txt.y < tpl.y + tpl.height + gap && txt.y + txt.height > tpl.y - gap;
  }

  private static boolean horizontalNear(Rectangle tpl, Rectangle txt, int gap) {
    return txt.x < tpl.x + tpl.width + gap && txt.x + txt.width > tpl.x - gap;
  }

  private BufferedImage scaledTemplate(BufferedImage template, double scale) {
    String key = System.identityHashCode(template) + "|" + scale;
    BufferedImage cached;
    synchronized (templateCache) {
      cached = templateCache.get(key);
    }
    if (cached != null) {
      return cached;
    }
    BufferedImage result = template;
    if (scale != 1.0) {
      int w = Math.max(2, (int) Math.round(template.getWidth() * scale));
      int h = Math.max(2, (int) Math.round(template.getHeight() * scale));
      BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      java.awt.Graphics2D g = scaled.createGraphics();
      g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
          java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(template, 0, 0, w, h, null);
      g.dispose();
      result = scaled;
    }
    synchronized (templateCache) {
      templateCache.put(key, result);
    }
    return result;
  }

  private static BufferedImage invert(BufferedImage img) {
    int w = img.getWidth();
    int h = img.getHeight();
    BufferedImage inv = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
    for (int i = 0; i < rgb.length; i++) {
      rgb[i] = 0xFFFFFF - (rgb[i] & 0xFFFFFF);
    }
    inv.setRGB(0, 0, w, h, rgb, 0, w);
    return inv;
  }

  private static java.awt.Point centerOf(Rectangle r) {
    return new java.awt.Point(r.x + r.width / 2, r.y + r.height / 2);
  }

  private static void addNote(List<String> notes, String note) {
    if (!notes.contains(note)) {
      notes.add(note);
    }
  }

  private static boolean hasTextAnchor(FusionTarget target) {
    for (FusionTarget.Anchor a : target.anchors) {
      if (a.text != null) {
        return true;
      }
    }
    return false;
  }
}
