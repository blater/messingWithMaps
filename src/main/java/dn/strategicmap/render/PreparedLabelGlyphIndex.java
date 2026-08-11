package dn.strategicmap.render;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dn.strategicmap.label.PreparedLabelGlyph;
import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.PreparedLabelGlyphTransforms;
import dn.strategicmap.label.PreparedLabelGlyphTsv;

/** Immutable, build-prepared glyph origins and tangents for reviewed map labels. */
final class PreparedLabelGlyphIndex {
  private static final String RESOURCE =
      "/presentation/prepared-strategic-label-glyphs.tsv";
  private static final String BASE_RESOURCE =
      "/presentation/prepared-strategic-label-base-glyphs.tsv";
  private static final String DEFAULT_VARIANT = "DEFAULT";
  private static final String CAPITAL_VARIANT = "CAPITAL";
  private final Map<String, Map<String, List<PreparedLabelGlyph>>> glyphsByLabelKey;

  private PreparedLabelGlyphIndex(
      Map<String, Map<String, List<PreparedLabelGlyph>>> glyphsByLabelKey) {
    this.glyphsByLabelKey = glyphsByLabelKey;
  }

  static PreparedLabelGlyphIndex loadDefault() {
    return load(RESOURCE);
  }

  static PreparedLabelGlyphIndex loadBase() {
    return load(BASE_RESOURCE);
  }

  private static PreparedLabelGlyphIndex load(String resource) {
    InputStream input = PreparedLabelGlyphIndex.class.getResourceAsStream(resource);
    if (input == null) {
      throw new IllegalStateException("Prepared label glyphs not found: " + resource);
    }
    try {
      Map<String, Map<String, List<PreparedLabelGlyph>>> glyphs = new LinkedHashMap<>();
      for (PreparedLabelGlyph glyph : PreparedLabelGlyphTsv.read(input)) {
        glyphs.computeIfAbsent(glyph.labelKey(), ignored -> new LinkedHashMap<>())
            .computeIfAbsent(glyph.variant(), ignored -> new ArrayList<>()).add(glyph);
      }
      return new PreparedLabelGlyphIndex(glyphs);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Unable to load prepared label glyphs", exception);
    }
  }

  List<PreparedLabelGlyph> glyphs(String labelKey, LabelCategory category) {
    Map<String, List<PreparedLabelGlyph>> variants = glyphsByLabelKey.get(labelKey);
    if (variants == null) {
      return null;
    }
    String requested = category == LabelCategory.CAPITAL
        || category == LabelCategory.MINOR_CAPITAL ? CAPITAL_VARIANT : DEFAULT_VARIANT;
    List<PreparedLabelGlyph> glyphs = variants.get(requested);
    return glyphs == null ? variants.get(DEFAULT_VARIANT) : glyphs;
  }

  Set<String> labelKeys() {
    return glyphsByLabelKey.keySet();
  }

  PreparedLabelGlyphIndex withOverrides(Map<String, LabelLayoutOverride> overrides) {
    List<PreparedLabelGlyph> baseGlyphs = new ArrayList<>();
    for (Map<String, List<PreparedLabelGlyph>> variants : glyphsByLabelKey.values()) {
      for (List<PreparedLabelGlyph> glyphs : variants.values()) {
        baseGlyphs.addAll(glyphs);
      }
    }
    Map<String, Map<String, List<PreparedLabelGlyph>>> transformed = new LinkedHashMap<>();
    for (PreparedLabelGlyph glyph : PreparedLabelGlyphTransforms.apply(baseGlyphs, overrides)) {
      transformed.computeIfAbsent(glyph.labelKey(), ignored -> new LinkedHashMap<>())
          .computeIfAbsent(glyph.variant(), ignored -> new ArrayList<>()).add(glyph);
    }
    return new PreparedLabelGlyphIndex(transformed);
  }
}
