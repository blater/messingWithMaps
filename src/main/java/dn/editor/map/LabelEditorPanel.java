package dn.editor.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.PreparedLabelSelection;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Screen-space development editor for prepared labels. */
public final class LabelEditorPanel {
  private static final Color BACKGROUND = new Color(0.025f, 0.035f, 0.05f, 0.94f);
  private static final Color BUTTON = new Color(0.18f, 0.23f, 0.27f, 1.0f);
  private static final Color ACTION = new Color(0.24f, 0.29f, 0.32f, 1.0f);
  private static final Color DISABLED = new Color(0.11f, 0.13f, 0.15f, 1.0f);
  private static final Color SAVE = new Color(0.25f, 0.42f, 0.31f, 1.0f);
  private static final float WIDTH = 340.0f;
  private static final float HEIGHT = 458.0f;
  private static final float BOTTOM = 16.0f;
  private static final float PROPERTY_BUTTON_WIDTH = 44.0f;
  private static final float PROPERTY_BUTTON_HEIGHT = 28.0f;
  private static final float ACTION_WIDTH = 96.0f;
  private static final float LEFT_ACTION = 12.0f;
  private static final float RIGHT_ACTION = 232.0f;
  private static final LabelEditSession.Property[] PROPERTIES =
      LabelEditSession.Property.values();

  private final LabelEditSession session;
  private final Runnable previewChanged;

  public LabelEditorPanel(LabelEditSession session, Runnable previewChanged) {
    this.session = session;
    this.previewChanged = previewChanged;
  }

  public boolean enabled() { return session.enabled(); }

  public void select(PreparedLabelSelection target) {
    session.select(target.labelKey(), target.defaultBand());
  }

  public void drawShapes(ShapeRenderer shapes, int screenWidth) {
    float left = left(screenWidth);
    shapes.setColor(BACKGROUND);
    shapes.rect(left, BOTTOM, WIDTH, HEIGHT);
    shapes.setColor(BUTTON);
    for (int row = 0; row < PROPERTIES.length; row++) {
      float bottom = propertyBottom(row);
      shapes.rect(minusLeft(left), bottom, PROPERTY_BUTTON_WIDTH, PROPERTY_BUTTON_HEIGHT);
      shapes.rect(plusLeft(left), bottom, PROPERTY_BUTTON_WIDTH, PROPERTY_BUTTON_HEIGHT);
    }
    shapes.setColor(ACTION);
    shapes.rect(left + LEFT_ACTION, BOTTOM + 94.0f, 140.0f, 30.0f);
    shapes.rect(left + RIGHT_ACTION, BOTTOM + 94.0f, ACTION_WIDTH, 30.0f);
    shapes.setColor(session.canUndo() ? ACTION : DISABLED);
    shapes.rect(left + LEFT_ACTION, BOTTOM + 56.0f, ACTION_WIDTH, 30.0f);
    shapes.setColor(session.canRedo() ? ACTION : DISABLED);
    shapes.rect(left + RIGHT_ACTION, BOTTOM + 56.0f, ACTION_WIDTH, 30.0f);
    shapes.setColor(ACTION);
    shapes.rect(left + LEFT_ACTION, BOTTOM + 18.0f, ACTION_WIDTH, 30.0f);
    shapes.setColor(SAVE);
    shapes.rect(left + RIGHT_ACTION, BOTTOM + 18.0f, ACTION_WIDTH, 30.0f);
  }

  public void drawText(SpriteBatch sprites, BitmapFont font, int screenWidth) {
    float left = left(screenWidth);
    font.draw(sprites, "LABEL EDIT MODE", left + 12.0f, BOTTOM + 438.0f);
    String selected = session.selectedLabelKey();
    font.draw(sprites, selected == null ? "Selected: none" : "Selected: " + selected,
        left + 12.0f, BOTTOM + 414.0f);
    font.draw(sprites, session.status(), left + 12.0f, BOTTOM + 390.0f);
    LabelLayoutOverride value = session.selectedOverride();
    for (int row = 0; row < PROPERTIES.length; row++) {
      float baseline = propertyBottom(row) + 20.0f;
      font.draw(sprites, propertyLabel(row), left + 12.0f, baseline);
      font.draw(sprites, propertyValue(row, value), left + 142.0f, baseline);
      font.draw(sprites, "-", minusLeft(left) + 18.0f, baseline);
      font.draw(sprites, "+", plusLeft(left) + 17.0f, baseline);
    }
    font.draw(sprites, "CLEAR SPACING", left + 27.0f, BOTTOM + 115.0f);
    font.draw(sprites, value.hidden() ? "SHOW" : "HIDE", left + 260.0f, BOTTOM + 115.0f);
    font.draw(sprites, "UNDO", left + 42.0f, BOTTOM + 77.0f);
    font.draw(sprites, "REDO", left + 262.0f, BOTTOM + 77.0f);
    font.draw(sprites, "CLEAR", left + 37.0f, BOTTOM + 39.0f);
    font.draw(sprites, "SAVE", left + 262.0f, BOTTOM + 39.0f);
  }

  public boolean handleClick(int viewportX, int viewportY, int screenWidth, int screenHeight) {
    float screenY = screenHeight - viewportY;
    float left = left(screenWidth);
    if (viewportX < left || viewportX > left + WIDTH
        || screenY < BOTTOM || screenY > BOTTOM + HEIGHT) {
      return false;
    }
    for (int row = 0; row < PROPERTIES.length; row++) {
      float bottom = propertyBottom(row);
      if (screenY < bottom || screenY > bottom + PROPERTY_BUTTON_HEIGHT) {
        continue;
      }
      int direction = direction(viewportX, left);
      if (direction != 0) {
        LabelEditSession.Property property = PROPERTIES[row];
        mutate(() -> session.adjust(property, direction));
      }
      return true;
    }
    if (inRow(screenY, BOTTOM + 94.0f)) {
      if (between(viewportX, left + LEFT_ACTION, left + 152.0f)) {
        mutate(session::clearSpacing);
      } else if (between(viewportX, left + RIGHT_ACTION, left + RIGHT_ACTION + ACTION_WIDTH)) {
        mutate(session::toggleHidden);
      }
    } else if (inRow(screenY, BOTTOM + 56.0f)) {
      if (between(viewportX, left + LEFT_ACTION, left + LEFT_ACTION + ACTION_WIDTH)) {
        mutate(session::undo);
      } else if (between(viewportX, left + RIGHT_ACTION, left + RIGHT_ACTION + ACTION_WIDTH)) {
        mutate(session::redo);
      }
    } else if (inRow(screenY, BOTTOM + 18.0f)) {
      if (between(viewportX, left + LEFT_ACTION, left + LEFT_ACTION + ACTION_WIDTH)) {
        mutate(session::clearSelected);
      } else if (between(viewportX, left + RIGHT_ACTION, left + RIGHT_ACTION + ACTION_WIDTH)) {
        save();
      }
    }
    return true;
  }

  private void mutate(BooleanSupplier operation) {
    if (operation.getAsBoolean()) {
      previewChanged.run();
    }
  }

  private void save() {
    try {
      session.save();
    } catch (IOException failure) {
      throw new IllegalStateException("Unable to save label edit artefacts", failure);
    }
  }

  private int direction(int viewportX, float left) {
    if (between(viewportX, plusLeft(left), plusLeft(left) + PROPERTY_BUTTON_WIDTH)) {
      return 1;
    }
    return between(viewportX, minusLeft(left), minusLeft(left) + PROPERTY_BUTTON_WIDTH) ? -1 : 0;
  }

  private static boolean inRow(float screenY, float bottom) {
    return screenY >= bottom && screenY <= bottom + 30.0f;
  }

  private static boolean between(float value, float minimum, float maximum) {
    return value >= minimum && value <= maximum;
  }

  private static float left(int screenWidth) {
    return Math.max(8.0f, screenWidth - WIDTH - 8.0f);
  }

  private static float minusLeft(float left) { return left + 232.0f; }
  private static float plusLeft(float left) { return left + 284.0f; }
  private static float propertyBottom(int row) { return BOTTOM + 310.0f - row * 36.0f; }

  private static String propertyLabel(int row) {
    return switch (PROPERTIES[row]) {
      case TRACKING -> "Letter spacing";
      case EAST_WEST -> "Position E/W";
      case NORTH_SOUTH -> "Position N/S";
      case ROTATION -> "Rotation";
      case FONT_SIZE -> "Font size";
      case ZOOM_BAND -> "Zoom band";
    };
  }

  private String propertyValue(int row, LabelLayoutOverride value) {
    return switch (PROPERTIES[row]) {
      case TRACKING -> String.format("%+.2f", value.trackingDeltaMapUnits());
      case EAST_WEST -> String.format("%+.1f", value.offsetMapX());
      case NORTH_SOUTH -> String.format("%+.1f", value.offsetMapY());
      case ROTATION -> String.format("%+.0f deg", value.rotationDeltaDegrees());
      case FONT_SIZE -> String.format("%.0f%%", value.fontScaleMultiplier() * 100.0);
      case ZOOM_BAND -> session.selectedMinimumBand().name()
          + (session.selectedMinimumBandIsOverridden() ? "*" : "");
    };
  }
}
