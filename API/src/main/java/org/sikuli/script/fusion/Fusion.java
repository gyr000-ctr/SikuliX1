/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import org.sikuli.script.Match;
import org.sikuli.script.Region;
import org.sikuli.script.Screen;
import org.sikuli.script.ScreenImage;
import org.sikuli.support.devices.IScreen;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Entry point for fused image + OCR localization on SikuliX regions.
 * <p>
 * Usage:
 * <pre>
 *   FusionTarget target = FusionTarget.builder()
 *       .template(buttonImage)
 *       .text("Save")
 *       .scales(1.0, 1.25)
 *       .autoInvert(true)
 *       .build();
 *   Match m = Fusion.locate(new Screen(), target);
 *   m.click();
 * </pre>
 * The classic single image find ({@code region.find(img)}) is not
 * touched by this — fusion is strictly additive.
 */
public class Fusion {

  private Fusion() {
  }

  /**
   * creates a fusion finder working on the given region
   */
  public static FusionFinder on(Region region) {
    return FusionFinder.createDefault(sourceFor(region));
  }

  /**
   * creates a fusion finder working on a custom pixel source
   */
  public static FusionFinder on(ScreenSource source) {
    return FusionFinder.createDefault(source);
  }

  /**
   * locates the target in the given region
   *
   * @return the best match (screen coordinates)
   * @throws FusionFindFailed if no trustworthy target was found in time
   */
  public static Match locate(Region region, FusionTarget target) throws FusionFindFailed {
    return locate(on(region), region, target);
  }

  /**
   * locates the target on the default screen
   *
   * @return the best match (screen coordinates)
   * @throws FusionFindFailed if no trustworthy target was found in time
   */
  public static Match locate(FusionTarget target) throws FusionFindFailed {
    Screen screen = new Screen();
    return locate(on(screen), screen, target);
  }

  /**
   * locates with an existing finder (keeps its statistics and caches)
   */
  public static Match locate(FusionFinder finder, Region region, FusionTarget target)
      throws FusionFindFailed {
    FusionResult result = finder.locate(target);
    Rectangle r = result.getBest().getRect();
    IScreen screen = region.getScreen();
    Match match = new Match(region.x + r.x, region.y + r.y, r.width, r.height,
        result.getBest().getConfidence(), screen);
    return match;
  }

  private static ScreenSource sourceFor(Region region) {
    if (region.getScreen() == null) {
      throw new IllegalArgumentException("Fusion: region has no screen to capture from: " + region);
    }
    return new ScreenSource() {
      @Override
      public BufferedImage capture() {
        ScreenImage simg = region.getScreen().capture(region);
        return simg == null ? null : simg.getImage();
      }
    };
  }
}
