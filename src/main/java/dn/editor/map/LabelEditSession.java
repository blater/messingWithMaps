package dn.editor.map;

import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.LabelLayoutOverrideTsv;
import dn.strategicmap.label.LabelZoomBandOverride;
import dn.strategicmap.label.PreparedLabelGlyph;
import dn.strategicmap.label.PreparedLabelGlyphTransforms;
import dn.strategicmap.label.PreparedLabelGlyphTsv;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicit development-mode label edits and their two persisted authoring artefacts. */
public final class LabelEditSession {
  private static final double ROTATION_STEP = 1.0;
  private static final double SCALE_STEP = 1.05;

  private final boolean enabled;
  private final Path overridePath;
  private final Path baseGlyphPath;
  private final Path runtimeGlyphPath;
  private final Map<String, LabelLayoutOverride> overrides;
  private final Map<String, LabelLayoutOverride> overridesView;
  private final Map<String, Double> positionSteps;
  private String selectedLabelKey;
  private LabelZoomBandOverride selectedDefaultBand = LabelZoomBandOverride.DEFAULT;
  private EditChange undoChange;
  private EditChange redoChange;
  private String status = "Select a prepared label";

  private LabelEditSession(
      boolean enabled,
      Path overridePath,
      Path baseGlyphPath,
      Path runtimeGlyphPath,
      Map<String, LabelLayoutOverride> overrides,
      Map<String, Double> positionSteps) {
    this.enabled = enabled;
    this.overridePath = overridePath;
    this.baseGlyphPath = baseGlyphPath;
    this.runtimeGlyphPath = runtimeGlyphPath;
    this.overrides = overrides;
    overridesView = Collections.unmodifiableMap(overrides);
    this.positionSteps = positionSteps;
  }

  public static LabelEditSession disabled() {
    return new LabelEditSession(false, null, null, null, Map.of(), Map.of());
  }

  public static LabelEditSession open(
      Path overridePath,
      Path baseGlyphPath,
      Path runtimeGlyphPath) throws IOException {
    List<PreparedLabelGlyph> baseGlyphs = PreparedLabelGlyphTsv.read(baseGlyphPath);
    return new LabelEditSession(
        true, overridePath, baseGlyphPath, runtimeGlyphPath,
        LabelLayoutOverrideTsv.read(overridePath), positionSteps(baseGlyphs));
  }

  public boolean enabled() { return enabled; }
  public String selectedLabelKey() { return selectedLabelKey; }
  public String status() { return status; }
  public Map<String, LabelLayoutOverride> overrides() { return overridesView; }

  public LabelLayoutOverride selectedOverride() {
    return selectedLabelKey == null
        ? LabelLayoutOverride.IDENTITY
        : overrides.getOrDefault(selectedLabelKey, LabelLayoutOverride.IDENTITY);
  }

  public void select(String labelKey, LabelZoomBandOverride defaultBand) {
    selectedLabelKey = labelKey;
    selectedDefaultBand = defaultBand;
    status = labelKey;
  }

  public boolean adjust(Property property, int direction) {
    if (selectedLabelKey == null) {
      status = "Select a prepared label first";
      return false;
    }
    LabelLayoutOverride current = selectedOverride();
    double positionStep = positionSteps.getOrDefault(selectedLabelKey, 0.50);
    double trackingStep = positionStep * 0.20;
    LabelLayoutOverride adjusted = switch (property) {
      case TRACKING -> new LabelLayoutOverride(
          current.trackingDeltaMapUnits() + direction * trackingStep,
          current.offsetMapX(), current.offsetMapY(), current.rotationDeltaDegrees(),
          current.fontScaleMultiplier(), current.hidden(), current.minimumBandOverride());
      case EAST_WEST -> new LabelLayoutOverride(
          current.trackingDeltaMapUnits(), current.offsetMapX() + direction * positionStep,
          current.offsetMapY(), current.rotationDeltaDegrees(), current.fontScaleMultiplier(),
          current.hidden(), current.minimumBandOverride());
      case NORTH_SOUTH -> new LabelLayoutOverride(
          current.trackingDeltaMapUnits(), current.offsetMapX(),
          current.offsetMapY() + direction * positionStep, current.rotationDeltaDegrees(),
          current.fontScaleMultiplier(), current.hidden(), current.minimumBandOverride());
      case ROTATION -> new LabelLayoutOverride(
          current.trackingDeltaMapUnits(), current.offsetMapX(), current.offsetMapY(),
          current.rotationDeltaDegrees() + direction * ROTATION_STEP,
          current.fontScaleMultiplier(), current.hidden(), current.minimumBandOverride());
      case FONT_SIZE -> new LabelLayoutOverride(
          current.trackingDeltaMapUnits(), current.offsetMapX(), current.offsetMapY(),
          current.rotationDeltaDegrees(),
          current.fontScaleMultiplier() * (direction > 0 ? SCALE_STEP : 1.0 / SCALE_STEP),
          current.hidden(), current.minimumBandOverride());
      case ZOOM_BAND -> withMinimumBand(current, adjacentBand(current, direction));
    };
    return apply(adjusted, "Unsaved changes to " + selectedLabelKey);
  }

  public boolean clearSpacing() {
    if (!hasSelection()) {
      return false;
    }
    LabelLayoutOverride current = selectedOverride();
    return apply(new LabelLayoutOverride(
        0.0, current.offsetMapX(), current.offsetMapY(), current.rotationDeltaDegrees(),
        current.fontScaleMultiplier(), current.hidden(), current.minimumBandOverride()),
        "Cleared letter spacing for " + selectedLabelKey + " (save to persist)");
  }

  public boolean toggleHidden() {
    if (!hasSelection()) {
      return false;
    }
    LabelLayoutOverride current = selectedOverride();
    return apply(new LabelLayoutOverride(
        current.trackingDeltaMapUnits(), current.offsetMapX(), current.offsetMapY(),
        current.rotationDeltaDegrees(), current.fontScaleMultiplier(), !current.hidden(),
        current.minimumBandOverride()),
        (current.hidden() ? "Shown " : "Hidden ") + selectedLabelKey + " (save to persist)");
  }

  public boolean clearSelected() {
    if (!hasSelection()) {
      return false;
    }
    return apply(LabelLayoutOverride.IDENTITY,
        "Cleared " + selectedLabelKey + " (save to persist)");
  }

  public boolean undo() {
    if (undoChange == null) {
      status = "Nothing to undo";
      return false;
    }
    applyValue(undoChange.labelKey(), undoChange.before());
    selectedLabelKey = undoChange.labelKey();
    selectedDefaultBand = undoChange.defaultBand();
    redoChange = undoChange;
    undoChange = null;
    status = "Undid change to " + selectedLabelKey;
    return true;
  }

  public boolean redo() {
    if (redoChange == null) {
      status = "Nothing to redo";
      return false;
    }
    applyValue(redoChange.labelKey(), redoChange.after());
    selectedLabelKey = redoChange.labelKey();
    selectedDefaultBand = redoChange.defaultBand();
    undoChange = redoChange;
    redoChange = null;
    status = "Redid change to " + selectedLabelKey;
    return true;
  }

  public boolean canUndo() { return undoChange != null; }
  public boolean canRedo() { return redoChange != null; }
  public LabelZoomBandOverride selectedMinimumBand() {
    LabelLayoutOverride current = selectedOverride();
    return current.minimumBandOverride() == LabelZoomBandOverride.DEFAULT
        ? selectedDefaultBand : current.minimumBandOverride();
  }
  public boolean selectedMinimumBandIsOverridden() {
    return selectedOverride().minimumBandOverride() != LabelZoomBandOverride.DEFAULT;
  }

  public void save() throws IOException {
    LabelLayoutOverrideTsv.write(overridePath, overrides);
    PreparedLabelGlyphTsv.write(
        runtimeGlyphPath,
        PreparedLabelGlyphTransforms.apply(PreparedLabelGlyphTsv.read(baseGlyphPath), overrides));
    status = "Saved overrides and runtime glyph coordinates";
  }

  private static Map<String, Double> positionSteps(List<PreparedLabelGlyph> glyphs) {
    Map<String, Double> steps = new HashMap<>();
    for (PreparedLabelGlyph glyph : glyphs) {
      double step = Math.max(0.05, Math.min(0.50, glyph.fontScaleMapUnits() * 500.0));
      steps.merge(glyph.labelKey(), step, Math::max);
    }
    return steps;
  }

  private boolean hasSelection() {
    if (selectedLabelKey != null) {
      return true;
    }
    status = "Select a prepared label first";
    return false;
  }

  private boolean apply(LabelLayoutOverride adjusted, String successStatus) {
    LabelLayoutOverride current = selectedOverride();
    if (current.equals(adjusted)) {
      status = "No change to " + selectedLabelKey;
      return false;
    }
    applyValue(selectedLabelKey, adjusted);
    undoChange = new EditChange(selectedLabelKey, selectedDefaultBand, current, adjusted);
    redoChange = null;
    status = successStatus;
    return true;
  }

  private void applyValue(String labelKey, LabelLayoutOverride value) {
    if (value.isIdentity()) {
      overrides.remove(labelKey);
    } else {
      overrides.put(labelKey, value);
    }
  }

  private LabelZoomBandOverride adjacentBand(LabelLayoutOverride current, int direction) {
    LabelZoomBandOverride band = current.minimumBandOverride() == LabelZoomBandOverride.DEFAULT
        ? selectedDefaultBand : current.minimumBandOverride();
    int ordinal = Math.max(
        LabelZoomBandOverride.WORLD.ordinal(),
        Math.min(LabelZoomBandOverride.DETAIL.ordinal(), band.ordinal() + direction));
    return LabelZoomBandOverride.values()[ordinal];
  }

  private static LabelLayoutOverride withMinimumBand(
      LabelLayoutOverride current, LabelZoomBandOverride minimumBand) {
    return new LabelLayoutOverride(
        current.trackingDeltaMapUnits(), current.offsetMapX(), current.offsetMapY(),
        current.rotationDeltaDegrees(), current.fontScaleMultiplier(), current.hidden(),
        minimumBand);
  }

  public enum Property {
    TRACKING,
    EAST_WEST,
    NORTH_SOUTH,
    ROTATION,
    FONT_SIZE,
    ZOOM_BAND
  }

  private record EditChange(
      String labelKey,
      LabelZoomBandOverride defaultBand,
      LabelLayoutOverride before,
      LabelLayoutOverride after) {}
}
