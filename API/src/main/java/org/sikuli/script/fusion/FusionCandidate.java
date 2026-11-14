/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */
package org.sikuli.script.fusion;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One ranked localization candidate: position, fused confidence and the
 * per-signal contributions that led to it. Text data only — never holds
 * image data, so it is safe to log and keep in statistics.
 */
public class FusionCandidate {

  private final Rectangle rect;
  private double confidence;
  private boolean fromText;
  private final Map<String, Double> contributions = new LinkedHashMap<>();
  private final List<String> notes = new ArrayList<>();

  public FusionCandidate(Rectangle rect) {
    this.rect = rect;
  }

  public Rectangle getRect() {
    return rect;
  }

  public double getConfidence() {
    return confidence;
  }

  void setConfidence(double confidence) {
    this.confidence = confidence;
  }

  /**
   * true if this candidate's position comes from an OCR word
   * (eligible for the OCR fallback path)
   */
  public boolean isFromText() {
    return fromText;
  }

  void setFromText(boolean fromText) {
    this.fromText = fromText;
  }

  /**
   * per-signal contributions (e.g. template, text, anchor), values 0..1
   */
  public Map<String, Double> getContributions() {
    return Collections.unmodifiableMap(contributions);
  }

  void addContribution(String signal, double value) {
    contributions.put(signal, value);
  }

  /**
   * human readable notes, e.g. "inverted", "scale=1.25", "ocr-fallback"
   */
  public List<String> getNotes() {
    return Collections.unmodifiableList(notes);
  }

  void addNote(String note) {
    notes.add(note);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("(%d,%d %dx%d) conf=%.3f", rect.x, rect.y, rect.width, rect.height, confidence));
    if (!contributions.isEmpty()) {
      sb.append(" [");
      String sep = "";
      for (Map.Entry<String, Double> e : contributions.entrySet()) {
        sb.append(sep).append(e.getKey()).append(String.format("=%.2f", e.getValue()));
        sep = ", ";
      }
      sb.append("]");
    }
    if (!notes.isEmpty()) {
      sb.append(" ").append(notes);
    }
    return sb.toString();
  }
}
