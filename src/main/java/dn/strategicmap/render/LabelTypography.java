package dn.strategicmap.render;

import com.badlogic.gdx.graphics.Color;
import java.util.Locale;

/** Stable late-nineteenth-century typography rules applied while labels are prepared. */
enum LabelTypography {
  WATER(Face.SERIF, 0.52f, new Color(0.25f, 0.34f, 0.36f, 0.90f), true, true, false),
  SEA(Face.SERIF, 0.28f, new Color(0.25f, 0.34f, 0.36f, 0.90f), true, false, false),
  PRIMARY_GROUP(Face.SERIF, 0.38f, new Color(0.10f, 0.08f, 0.05f, 1.0f), true, false, false),
  SECONDARY_GROUP(Face.SERIF, 0.33f, new Color(0.10f, 0.08f, 0.05f, 1.0f), true, false, false),
  CAPITAL(Face.SERIF, 0.30f, new Color(0.18f, 0.14f, 0.09f, 1.0f), true, false, true),
  MINOR_CAPITAL(Face.SERIF, 0.30f, new Color(0.18f, 0.14f, 0.09f, 1.0f), false, false, true),
  CITY(Face.ITALIC, 0.24f, new Color(0.25f, 0.22f, 0.16f, 1.0f), false, false, false),
  PORT(Face.ITALIC, 0.24f, new Color(0.25f, 0.22f, 0.16f, 1.0f), false, false, false),
  LAND(Face.ITALIC, 0.28f, new Color(0.15f, 0.13f, 0.09f, 0.96f), false, false, false);

  enum Face { SERIF, ITALIC }

  private final Face face;
  private final float scale;
  private final Color colour;
  private final boolean uppercase;
  private final boolean tracked;
  private final boolean bold;

  LabelTypography(
      Face face, float scale, Color colour, boolean uppercase, boolean tracked, boolean bold) {
    this.face = face;
    this.scale = scale;
    this.colour = colour;
    this.uppercase = uppercase;
    this.tracked = tracked;
    this.bold = bold;
  }

  static LabelTypography forCategory(LabelCategory category) {
    return valueOf(category.name());
  }

  Face face() { return face; }
  float scale() { return scale; }
  Color colour() { return colour; }
  boolean bold() { return bold; }

  String displayText(String text) {
    String cased = uppercase ? text.toUpperCase(Locale.ROOT) : text;
    if (!tracked) {
      return cased;
    }
    StringBuilder trackedText = new StringBuilder(cased.length() * 3);
    for (int index = 0; index < cased.length(); index++) {
      char character = cased.charAt(index);
      if (character == ' ') {
        trackedText.append("     ");
      } else {
        if (index > 0 && cased.charAt(index - 1) != ' ') {
          trackedText.append("  ");
        }
        trackedText.append(character);
      }
    }
    return trackedText.toString();
  }
}
