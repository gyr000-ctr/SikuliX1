/*
 * Copyright (c) 2010-2026, SikuliX, RaiMan
 * Released under the MIT License.
 */

/**
 * Fused image + OCR localization.
 * <p>
 * Classic SikuliX find ops return a similarity and coordinates for image
 * targets, or plain text for OCR — with no cross-checking between the
 * two. This package combines both signals plus anchors and structural
 * constraints into one ranked, confidence-scored candidate list:
 * <ul>
 * <li>{@link org.sikuli.script.fusion.FusionTarget} describes the target
 * (template, text/regex, anchors, layout, DPI scales, dark-theme
 * inversion, confidence threshold, time budget)</li>
 * <li>{@link org.sikuli.script.fusion.FusionFinder} evaluates one capture
 * per attempt, fuses the signals and decides: accept, OCR fallback,
 * wait/retry — or an explicit
 * {@link org.sikuli.script.fusion.FusionFindFailed} instead of clicking
 * a wrong position</li>
 * <li>{@link org.sikuli.script.fusion.FusionStats} exposes candidate
 * rankings, fallback reasons, average locate time and failure counts</li>
 * </ul>
 * The work per locate is bounded (capped scale factors, cached template
 * variants, small anchor windows, timeout) and logging is text only —
 * screenshots are never written to logs. The classic single image find
 * is not changed by any of this; fusion is strictly additive.
 */
package org.sikuli.script.fusion;
