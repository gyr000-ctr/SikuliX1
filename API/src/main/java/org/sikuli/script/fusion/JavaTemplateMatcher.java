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
 * Pure Java template matcher (normalized cross correlation on grayscale).
 * <p>
 * Used as fallback when the OpenCV based matcher is not available and
 * in headless tests. The work per call is bounded: if the estimated
 * number of pixel operations exceeds {@link #MAX_OPS}, the scan runs
 * with a coarse stride first and only the best coarse positions are
 * refined pixel exact. Hence the matcher never degrades into an
 * unbounded full-screen multi-scale scan.
 */
public class JavaTemplateMatcher implements TemplateMatcher {

  /**
   * upper bound of pixel operations per findAll call
   */
  public static final long MAX_OPS = 500_000_000L;

  private static final int REFINE_RADIUS = 3;

  @Override
  public List<Hit> findAll(BufferedImage base, BufferedImage template, double minScore, int maxResults) {
    if (base == null || template == null || maxResults < 1) {
      return Collections.emptyList();
    }
    int bw = base.getWidth();
    int bh = base.getHeight();
    int tw = template.getWidth();
    int th = template.getHeight();
    if (tw > bw || th > bh || tw < 2 || th < 2) {
      return Collections.emptyList();
    }
    float[][] baseGray = toGray(base);
    float[][] tplGray = toGray(template);

    // template statistics (zero mean normalized)
    double tMean = 0;
    for (int y = 0; y < th; y++) {
      for (int x = 0; x < tw; x++) {
        tMean += tplGray[y][x];
      }
    }
    tMean /= (tw * th);
    double tVar = 0;
    double[][] tNorm = new double[th][tw];
    for (int y = 0; y < th; y++) {
      for (int x = 0; x < tw; x++) {
        tNorm[y][x] = tplGray[y][x] - tMean;
        tVar += tNorm[y][x] * tNorm[y][x];
      }
    }
    if (tVar < 1e-6) {
      return Collections.emptyList(); // flat template matches everything and nothing
    }
    double tNormLen = Math.sqrt(tVar);

    // integral images of the base for fast window statistics
    int positions = (bw - tw + 1) * (bh - th + 1);
    long estOps = (long) positions * tw * th;
    int stride = 1;
    while (estOps > MAX_OPS) {
      stride++;
      long cols = (bw - tw) / stride + 1;
      long rows = (bh - th) / stride + 1;
      estOps = cols * rows * tw * th;
    }
    double[][] sum = integral(baseGray, false);
    double[][] sumSq = integral(baseGray, true);

    List<Hit> hits = new ArrayList<>();
    if (stride == 1) {
      scan(baseGray, tNorm, tNormLen, sum, sumSq, 1, 0, 0, bw - tw, bh - th, minScore, hits);
    } else {
      List<Hit> coarse = new ArrayList<>();
      scan(baseGray, tNorm, tNormLen, sum, sumSq, stride, 0, 0, bw - tw, bh - th, minScore, coarse);
      Collections.sort(coarse, (a, b) -> Double.compare(b.score, a.score));
      int refineCount = Math.min(coarse.size(), Math.max(maxResults * 4, 8));
      for (int i = 0; i < refineCount; i++) {
        Hit c = coarse.get(i);
        int minX = Math.max(0, c.rect.x - stride - REFINE_RADIUS);
        int minY = Math.max(0, c.rect.y - stride - REFINE_RADIUS);
        int maxX = Math.min(bw - tw, c.rect.x + stride + REFINE_RADIUS);
        int maxY = Math.min(bh - th, c.rect.y + stride + REFINE_RADIUS);
        scan(baseGray, tNorm, tNormLen, sum, sumSq, 1, minX, minY, maxX, maxY, minScore, hits);
      }
    }
    return nms(hits, maxResults);
  }

  private void scan(float[][] base, double[][] tNorm, double tNormLen,
                    double[][] sum, double[][] sumSq, int stride,
                    int minX, int minY, int maxX, int maxY,
                    double minScore, List<Hit> out) {
    int tw = tNorm[0].length;
    int th = tNorm.length;
    int n = tw * th;
    for (int y = minY; y <= maxY; y += stride) {
      for (int x = minX; x <= maxX; x += stride) {
        double wSum = rectSum(sum, x, y, tw, th);
        double wMean = wSum / n;
        double wSq = rectSum(sumSq, x, y, tw, th);
        double wVar = wSq - wSum * wSum / n;
        if (wVar < 1e-6) {
          continue; // flat window cannot correlate
        }
        double cross = 0;
        for (int ty = 0; ty < th; ty++) {
          float[] bRow = base[y + ty];
          double[] tRow = tNorm[ty];
          for (int tx = 0; tx < tw; tx++) {
            cross += (bRow[x + tx] - wMean) * tRow[tx];
          }
        }
        double ncc = cross / (Math.sqrt(wVar) * tNormLen);
        if (ncc >= minScore) {
          out.add(new Hit(new Rectangle(x, y, tw, th), Math.min(1.0, ncc)));
        }
      }
    }
  }

  /**
   * non maximum suppression: keeps the best hits, suppresses overlapping weaker ones
   */
  static List<Hit> nms(List<Hit> hits, int maxResults) {
    Collections.sort(hits, (a, b) -> Double.compare(b.score, a.score));
    List<Hit> kept = new ArrayList<>();
    for (Hit h : hits) {
      boolean suppressed = false;
      for (Hit k : kept) {
        Rectangle overlap = k.rect.intersection(h.rect);
        if (!overlap.isEmpty()
            && overlap.width * overlap.height * 2 > h.rect.width * h.rect.height) {
          suppressed = true;
          break;
        }
      }
      if (!suppressed) {
        kept.add(h);
        if (kept.size() >= maxResults) {
          break;
        }
      }
    }
    return kept;
  }

  static float[][] toGray(BufferedImage img) {
    int w = img.getWidth();
    int h = img.getHeight();
    float[][] gray = new float[h][w];
    int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int p = rgb[y * w + x];
        int r = (p >> 16) & 0xFF;
        int g = (p >> 8) & 0xFF;
        int b = p & 0xFF;
        gray[y][x] = 0.299f * r + 0.587f * g + 0.114f * b;
      }
    }
    return gray;
  }

  private static double[][] integral(float[][] gray, boolean squared) {
    int h = gray.length;
    int w = gray[0].length;
    double[][] integ = new double[h + 1][w + 1];
    for (int y = 0; y < h; y++) {
      double rowSum = 0;
      for (int x = 0; x < w; x++) {
        double v = gray[y][x];
        if (squared) {
          v *= v;
        }
        rowSum += v;
        integ[y + 1][x + 1] = integ[y][x + 1] + rowSum;
      }
    }
    return integ;
  }

  private static double rectSum(double[][] integ, int x, int y, int w, int h) {
    return integ[y + h][x + w] - integ[y][x + w] - integ[y + h][x] + integ[y][x];
  }
}
