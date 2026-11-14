/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import org.junit.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Headless tests for the fused image + OCR locator.
 * <p>
 * Screens are synthetic BufferedImages, the matcher is the pure Java
 * NCC matcher and OCR is a stub — no display, no OpenCV, no Tesseract
 * needed. Covers: DPI scaling, dark theme, occlusion, dynamic text,
 * low confidence/ambiguity, failure reporting, bounded evaluation,
 * statistics and the default (single template) regression.
 */
public class FusionFinderTest {

  //<editor-fold desc="test doubles and synthetic UI">
  private static class FixedSource implements ScreenSource {
    private final BufferedImage img;

    FixedSource(BufferedImage img) {
      this.img = img;
    }

    @Override
    public BufferedImage capture() {
      return img;
    }
  }

  private static class SequenceSource implements ScreenSource {
    private final List<BufferedImage> frames;
    private int i = 0;

    SequenceSource(BufferedImage... frames) {
      this.frames = Arrays.asList(frames);
    }

    @Override
    public BufferedImage capture() {
      return frames.get(Math.min(i++, frames.size() - 1));
    }
  }

  private static class StubOcr implements OcrProvider {
    private List<TextWord> words = new ArrayList<>();
    private boolean down = false;

    void setWords(TextWord... words) {
      this.words = Arrays.asList(words);
      this.down = false;
    }

    void setDown() {
      this.down = true;
    }

    @Override
    public List<TextWord> readWords(BufferedImage img) {
      if (down) {
        throw new RuntimeException("tesseract not available (stubbed)");
      }
      return words;
    }
  }

  private static class CountingMatcher implements TemplateMatcher {
    private final TemplateMatcher delegate = new JavaTemplateMatcher();
    int calls = 0;

    @Override
    public List<Hit> findAll(BufferedImage base, BufferedImage template, double minScore, int maxResults) {
      calls++;
      return delegate.findAll(base, template, minScore, maxResults);
    }
  }

  private static BufferedImage newScreen(int w, int h) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(245, 245, 245));
    g.fillRect(0, 0, w, h);
    g.dispose();
    return img;
  }

  /**
   * draws a distinctive button (gradient, border, red mark) at x,y — size 60x24 at scale 1
   */
  private static void drawButton(BufferedImage img, int x, int y) {
    Graphics2D g = img.createGraphics();
    g.translate(x, y);
    g.setPaint(new GradientPaint(0, 0, new Color(225, 230, 240), 0, 24, new Color(170, 180, 200)));
    g.fillRect(0, 0, 60, 24);
    g.setColor(new Color(60, 70, 90));
    g.drawRect(0, 0, 59, 23);
    g.setColor(new Color(200, 60, 60));
    g.fillRect(5, 8, 8, 8);
    g.setColor(new Color(90, 100, 120));
    g.drawLine(20, 6, 54, 18);
    g.dispose();
  }

  private static void drawWord(BufferedImage img, String word, int x, int y) {
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(30, 30, 30));
    g.drawString(word, x, y + 12);
    g.dispose();
  }

  private static void drawNoise(BufferedImage img, Rectangle r, long seed) {
    java.util.Random rnd = new java.util.Random(seed);
    for (int y = r.y; y < r.y + r.height; y++) {
      for (int x = r.x; x < r.x + r.width; x++) {
        int v = 40 + rnd.nextInt(180);
        img.setRGB(x, y, new Color(v, v, v).getRGB());
      }
    }
  }

  private static BufferedImage invert(BufferedImage img) {
    BufferedImage inv = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
    int[] rgb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    for (int i = 0; i < rgb.length; i++) {
      rgb[i] = 0xFFFFFF - (rgb[i] & 0xFFFFFF);
    }
    inv.setRGB(0, 0, img.getWidth(), img.getHeight(), rgb, 0, img.getWidth());
    return inv;
  }

  private static BufferedImage sub(BufferedImage img, int x, int y, int w, int h) {
    // deep copy — getSubimage would share the raster with the source, so
    // later drawing on the screen (e.g. occluders) would corrupt the template
    BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = copy.createGraphics();
    g.drawImage(img, -x, -y, null);
    g.dispose();
    return copy;
  }

  private static FusionFinder javaFinder(ScreenSource source, StubOcr ocr) {
    return new FusionFinder(source, new JavaTemplateMatcher(), ocr);
  }

  private static void assertRect(String msg, Rectangle r, int x, int y, int w, int h) {
    assertEquals(msg + " x", x, r.x);
    assertEquals(msg + " y", y, r.y);
    assertEquals(msg + " w", w, r.width);
    assertEquals(msg + " h", h, r.height);
  }
  //</editor-fold>

  @Test
  public void defaultSingleTemplateBehavesLikeClassicFind() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    BufferedImage template = sub(screen, 50, 40, 60, 24);

    FusionFinder finder = javaFinder(new FixedSource(screen), new StubOcr());
    FusionResult result = finder.locate(FusionTarget.of(template));

    assertTrue(result.isSuccess());
    assertNull(result.getFallbackReason());
    assertEquals(1, result.getAttempts());
    assertTrue("confidence " + result.getBest().getConfidence(),
        result.getBest().getConfidence() > 0.99);
    assertRect("best", result.getBest().getRect(), 50, 40, 60, 24);
    assertFalse(result.getRanking().isEmpty());
    // stats: ranking, timing, no failures
    assertEquals(1, finder.getStats().getLocateCount());
    assertEquals(0, finder.getStats().getFailureCount());
    assertTrue(finder.getStats().getAverageLocateMillis() >= 0);
    assertFalse(finder.getStats().getLastRanking().isEmpty());
  }

  @Test
  public void dpiScalingFindsRescaledTargetWithBoundedScales() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    // draw the button at 1.25x (DPI 120): 75x30 at (60,50)
    Graphics2D g = screen.createGraphics();
    g.translate(60, 50);
    g.scale(1.25, 1.25);
    drawButtonAtOrigin(g);
    g.dispose();
    BufferedImage plain = newScreen(100, 60);
    drawButton(plain, 0, 0);
    BufferedImage template = sub(plain, 0, 0, 60, 24);

    CountingMatcher counting = new CountingMatcher();
    FusionFinder finder = new FusionFinder(new FixedSource(screen), counting, new StubOcr());
    FusionTarget target = FusionTarget.builder()
        .template(template)
        .scales(1.0, 1.25)
        .timeout(0)
        .build();
    FusionResult result = finder.locate(target);

    assertTrue(result.isSuccess());
    assertEquals("dpi-scaled", result.getFallbackReason());
    assertTrue(result.getBest().getNotes().contains("scale=1.25"));
    assertRect("best", result.getBest().getRect(), 60, 50, 75, 30);
    assertEquals("exactly one match pass per scale, no full multi-scale scan", 2, counting.calls);

    // default target: single scale only — bounded to one pass
    counting.calls = 0;
    FusionFinder finder2 = new FusionFinder(new FixedSource(screen), counting, new StubOcr());
    try {
      finder2.locate(FusionTarget.builder().template(template).timeout(0).build());
    } catch (FusionFindFailed expected) {
      // may or may not find at scale 1.0 — what matters is the bound
    }
    assertEquals(1, counting.calls);
  }

  private static void drawButtonAtOrigin(Graphics2D g) {
    g.setPaint(new GradientPaint(0, 0, new Color(225, 230, 240), 0, 24, new Color(170, 180, 200)));
    g.fillRect(0, 0, 60, 24);
    g.setColor(new Color(60, 70, 90));
    g.drawRect(0, 0, 59, 23);
    g.setColor(new Color(200, 60, 60));
    g.fillRect(5, 8, 8, 8);
    g.setColor(new Color(90, 100, 120));
    g.drawLine(20, 6, 54, 18);
  }

  @Test
  public void darkThemeIsFoundViaInversion() throws Exception {
    BufferedImage light = newScreen(300, 200);
    drawButton(light, 50, 40);
    BufferedImage template = sub(light, 50, 40, 60, 24);
    BufferedImage dark = invert(light);

    FusionTarget target = FusionTarget.builder()
        .template(template).autoInvert(true).timeout(0).build();
    FusionResult result = javaFinder(new FixedSource(dark), new StubOcr()).locate(target);

    assertTrue(result.isSuccess());
    assertEquals("theme-inverted", result.getFallbackReason());
    assertTrue(result.getBest().getNotes().contains("inverted"));
    assertRect("best", result.getBest().getRect(), 50, 40, 60, 24);
  }

  @Test
  public void darkThemeWithoutAutoInvertFailsExplicitly() {
    BufferedImage light = newScreen(300, 200);
    drawButton(light, 50, 40);
    BufferedImage template = sub(light, 50, 40, 60, 24);
    BufferedImage dark = invert(light);

    FusionFinder finder = javaFinder(new FixedSource(dark), new StubOcr());
    try {
      finder.locate(FusionTarget.builder().template(template).timeout(0).build());
      fail("expected FusionFindFailed");
    } catch (FusionFindFailed e) {
      assertTrue(e.getMessage(), e.getMessage().contains("FusionFindFailed"));
    }
    assertEquals(1, finder.getStats().getFailureCount());
  }

  @Test
  public void occludedTemplateFallsBackToOcr() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    drawWord(screen, "Save", 50, 80);
    BufferedImage template = sub(screen, 50, 40, 60, 24);
    // occlude the whole button with noise -> template signal collapses
    drawNoise(screen, new Rectangle(50, 40, 60, 24), 42);

    StubOcr ocr = new StubOcr();
    ocr.setWords(new TextWord("Save", 0.95, new Rectangle(50, 80, 30, 14)));
    FusionFinder finder = javaFinder(new FixedSource(screen), ocr);
    FusionTarget target = FusionTarget.builder()
        .template(template)
        .text("Save")
        .textLayout(FusionTarget.TextLayout.BELOW, 30)
        .timeout(0)
        .build();
    FusionResult result = finder.locate(target);

    assertTrue(result.isSuccess());
    assertEquals("ocr-fallback", result.getFallbackReason());
    assertEquals(50, result.getBest().getRect().x);
    assertEquals(1, finder.getStats().getReasons().get("ocr-fallback").intValue());
  }

  @Test
  public void dynamicTextMatchesRegexButRejectsChangedText() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    BufferedImage template = sub(screen, 50, 40, 60, 24);

    StubOcr ocr = new StubOcr();
    ocr.setWords(new TextWord("Order#12345", 0.9, new Rectangle(50, 80, 80, 14)));
    FusionFinder finder = javaFinder(new FixedSource(screen), ocr);
    FusionTarget target = FusionTarget.builder()
        .template(template)
        .textRegex("Order#\\d+")
        .timeout(0)
        .build();
    FusionResult result = finder.locate(target);
    assertTrue(result.isSuccess());
    assertRect("best", result.getBest().getRect(), 50, 40, 60, 24);

    // text changed (dynamic content no longer matches the pattern): must not click
    ocr.setWords(new TextWord("Closed", 0.9, new Rectangle(50, 80, 40, 14)));
    try {
      finder.locate(target);
      fail("expected FusionFindFailed for changed dynamic text");
    } catch (FusionFindFailed e) {
      assertTrue(e.getMessage(), e.getMessage().contains("below-threshold"));
    }
  }

  @Test
  public void ambiguousCandidatesAreNotClicked() {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    drawButton(screen, 180, 40);
    BufferedImage template = sub(screen, 50, 40, 60, 24);

    FusionFinder finder = javaFinder(new FixedSource(screen), new StubOcr());
    try {
      finder.locate(FusionTarget.builder().template(template).timeout(0).build());
      fail("expected FusionFindFailed");
    } catch (FusionFindFailed e) {
      assertTrue(e.getMessage(), e.getMessage().contains("ambiguous-margin"));
      // the message carries the candidate ranking for diagnosis
      assertTrue(e.getMessage(), e.getMessage().contains("#1"));
      assertTrue(e.getMessage(), e.getMessage().contains("#2"));
    }
    assertEquals(1, finder.getStats().getFailureCount());
    assertEquals(1, finder.getStats().getReasons().get("ambiguous-margin").intValue());
  }

  @Test
  public void absentTargetFailsWithinTimeout() {
    BufferedImage screen = newScreen(300, 200); // empty
    BufferedImage plain = newScreen(100, 60);
    drawButton(plain, 0, 0);
    BufferedImage template = sub(plain, 0, 0, 60, 24);

    FusionFinder finder = javaFinder(new FixedSource(screen), new StubOcr());
    long start = System.currentTimeMillis();
    try {
      finder.locate(FusionTarget.builder().template(template).timeout(0.3).build());
      fail("expected FusionFindFailed");
    } catch (FusionFindFailed e) {
      assertTrue(e.getMessage(), e.getMessage().contains("no-candidates"));
    }
    long elapsed = System.currentTimeMillis() - start;
    assertTrue("locate must be bounded by the timeout, was " + elapsed, elapsed < 5000);
    assertEquals(1, finder.getStats().getFailureCount());
    assertTrue(finder.getStats().getAverageLocateMillis() > 0);
  }

  @Test
  public void waitRetryFindsLateAppearingTarget() throws Exception {
    BufferedImage empty = newScreen(300, 200);
    BufferedImage withButton = newScreen(300, 200);
    drawButton(withButton, 50, 40);
    BufferedImage template = sub(withButton, 50, 40, 60, 24);

    FusionFinder finder = javaFinder(
        new SequenceSource(empty, empty, withButton), new StubOcr());
    FusionResult result = finder.locate(
        FusionTarget.builder().template(template).timeout(5).build());

    assertTrue(result.isSuccess());
    assertTrue("attempts " + result.getAttempts(), result.getAttempts() >= 2);
    assertRect("best", result.getBest().getRect(), 50, 40, 60, 24);
  }

  @Test
  public void evaluationIsBoundedOnLargeScreens() {
    // 1600x900 noise screen: worst case for a naive full-screen scan
    BufferedImage big = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
    drawNoise(big, new Rectangle(0, 0, 1600, 900), 7);
    BufferedImage tplNoise = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
    drawNoise(tplNoise, new Rectangle(0, 0, 80, 40), 13);

    CountingMatcher counting = new CountingMatcher();
    FusionFinder finder = new FusionFinder(new FixedSource(big), counting, new StubOcr());
    long start = System.currentTimeMillis();
    try {
      finder.locate(FusionTarget.builder().template(tplNoise).timeout(0).build());
      fail("expected FusionFindFailed on noise");
    } catch (FusionFindFailed expected) {
      // fine
    }
    long elapsed = System.currentTimeMillis() - start;
    assertEquals("one bounded match pass, no unbounded scan", 1, counting.calls);
    assertTrue("evaluation must be bounded (MAX_OPS), took " + elapsed + "ms", elapsed < 30000);
  }

  @Test
  public void anchorsCarryLocalizationWhenTemplateIsWeak() throws Exception {
    // screen: intact button + a sharp anchor icon at a known offset
    BufferedImage plain = newScreen(100, 60);
    drawButton(plain, 0, 0);
    BufferedImage template = sub(plain, 0, 0, 60, 24);

    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    Graphics2D g = screen.createGraphics();
    g.setColor(new Color(30, 120, 30));
    g.fillRect(130, 46, 12, 12);
    g.setColor(Color.WHITE);
    g.drawLine(130, 46, 141, 57);
    g.dispose();
    BufferedImage anchorIcon = sub(screen, 130, 46, 12, 12);

    // anchor weighted strongly: even a weak template score is carried over
    // the threshold when the anchor matches at its expected position
    FusionTarget target = FusionTarget.builder()
        .template(template)
        .anchor(FusionTarget.Anchor.image(anchorIcon, 80, 6, 6))
        .weights(0.4, 0.0, 0.6)
        .timeout(0)
        .build();

    FusionResult result = javaFinder(new FixedSource(screen), new StubOcr()).locate(target);
    assertTrue(result.isSuccess());
    assertRect("best", result.getBest().getRect(), 50, 40, 60, 24);
    assertEquals(1.0, result.getBest().getContributions().get("anchor"), 0.001);

    // anchor missing -> confidence must drop below threshold -> explicit failure
    BufferedImage noAnchor = newScreen(300, 200);
    drawButton(noAnchor, 50, 40);
    try {
      javaFinder(new FixedSource(noAnchor), new StubOcr()).locate(target);
      fail("expected FusionFindFailed without anchor");
    } catch (FusionFindFailed e) {
      assertTrue(e.getMessage(), e.getMessage().contains("below-threshold"));
    }
  }

  @Test
  public void withinConstraintFiltersCandidates() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    drawButton(screen, 180, 40);
    BufferedImage template = sub(screen, 50, 40, 60, 24);

    FusionTarget target = FusionTarget.builder()
        .template(template)
        .within(new Rectangle(150, 0, 150, 200))
        .timeout(0)
        .build();
    FusionResult result = javaFinder(new FixedSource(screen), new StubOcr()).locate(target);

    assertTrue(result.isSuccess());
    assertRect("best", result.getBest().getRect(), 180, 40, 60, 24);
  }

  @Test
  public void textLayoutConstraintRanksCandidates() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    drawWord(screen, "Save", 52, 70);   // below the button
    drawWord(screen, "Save", 200, 20);  // decoy far away
    BufferedImage template = sub(screen, 50, 40, 60, 24);

    StubOcr ocr = new StubOcr();
    ocr.setWords(
        new TextWord("Save", 1.0, new Rectangle(52, 70, 30, 14)),
        new TextWord("Save", 1.0, new Rectangle(200, 20, 30, 14)));
    FusionTarget target = FusionTarget.builder()
        .template(template)
        .text("Save")
        .textLayout(FusionTarget.TextLayout.BELOW, 30)
        .timeout(0)
        .build();
    FusionResult result = javaFinder(new FixedSource(screen), ocr).locate(target);

    assertTrue(result.isSuccess());
    assertRect("best", result.getBest().getRect(), 50, 40, 60, 24);
    // ranking: fused candidate first with a clear lead over the rest
    assertTrue(result.getRanking().size() >= 2);
    assertTrue(result.getRanking().get(0).getConfidence()
        > result.getRanking().get(1).getConfidence());
  }

  @Test
  public void ocrUnavailableDegradesGracefully() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    BufferedImage template = sub(screen, 50, 40, 60, 24);

    StubOcr ocr = new StubOcr();
    ocr.setDown();

    // template + text: text signal is excluded, template still localizes
    FusionResult result = javaFinder(new FixedSource(screen), ocr).locate(
        FusionTarget.builder().template(template).text("Save").timeout(0).build());
    assertTrue(result.isSuccess());
    assertRect("best", result.getBest().getRect(), 50, 40, 60, 24);

    // text only: explicit failure naming the reason, no guessing
    try {
      javaFinder(new FixedSource(screen), ocr).locate(
          FusionTarget.builder().text("Save").timeout(0).build());
      fail("expected FusionFindFailed");
    } catch (FusionFindFailed e) {
      assertTrue(e.getMessage(), e.getMessage().contains("ocr-unavailable"));
    }
  }

  @Test
  public void statsAndLogsContainNoImageData() throws Exception {
    BufferedImage screen = newScreen(300, 200);
    drawButton(screen, 50, 40);
    BufferedImage template = sub(screen, 50, 40, 60, 24);

    FusionFinder finder = javaFinder(new FixedSource(screen), new StubOcr());
    FusionResult result = finder.locate(FusionTarget.of(template));

    String dump = result.toString() + finder.getStats().toString()
        + finder.getStats().getLastRanking().toString();
    assertFalse(dump.contains("BufferedImage"));
    assertFalse(dump.contains("RGB"));
    assertTrue(dump.contains("conf="));
    assertTrue(finder.getStats().toString().contains("locates=1"));
    assertTrue(finder.getStats().getLastRanking().get(0).startsWith("#1"));
  }

  @Test
  public void targetValidationRejectsInvalidSpecs() {
    try {
      FusionTarget.builder().build();
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // no template and no text
    }
    try {
      FusionTarget.builder().text("x").scales(1.0, 1.1, 1.2, 1.3, 1.4);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // more than MAX_SCALES
    }
    try {
      FusionTarget.builder().text("x").minConfidence(1.5);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // out of range
    }
  }
}
