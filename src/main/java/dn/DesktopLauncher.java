package dn;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import dn.politics.PoliticalSnapshotLoader;
import dn.strategicmap.StrategicMapScreen;
import dn.strategicmap.data.PlaceFeatureLoader;
import dn.strategicmap.data.WorldMapAssetLoader;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.interaction.VisiblePlaceQuery;
import dn.editor.map.LabelEditSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.lwjgl.system.Configuration;

public final class DesktopLauncher {
  private DesktopLauncher() {}

  public static void main(String[] args) {
    long launchStartedNanos = System.nanoTime();
    System.setProperty("org.lwjgl.glfw.GLFW_CHECK_THREAD0", "false");
    Configuration.GLFW_CHECK_THREAD0.set(false);
    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
    config.setTitle("The World (1880)");
    config.setWindowedMode(1280, 720);
    config.setResizable(true);
    config.useVsync(true);
    config.setForegroundFPS(60);

    new Lwjgl3Application(
        new SimpleGame(smokeSeconds(args), screenshotPath(args), mapView(args),
            labelEditSession(args), outlinesOnly(args), launchStartedNanos), config);
  }

  private static double smokeSeconds(String[] args) {
    String prefix = "--smoke-seconds=";
    for (String argument : args) {
      if (argument.startsWith(prefix)) {
        double value = Double.parseDouble(argument.substring(prefix.length()));
        if (!Double.isFinite(value) || value <= 0.0) {
          throw new IllegalArgumentException("Smoke duration must be a positive number of seconds");
        }
        return value;
      }
    }
    return 0.0;
  }

  private static String screenshotPath(String[] args) {
    String prefix = "--screenshot=";
    for (String argument : args) {
      if (argument.startsWith(prefix)) {
        return argument.substring(prefix.length());
      }
    }
    return "";
  }

  private static MapView mapView(String[] args) {
    String prefix = "--map-view=";
    for (String argument : args) {
      if (argument.startsWith(prefix)) {
        String[] values = argument.substring(prefix.length()).split(",");
        return new MapView(
            Double.parseDouble(values[0]),
            Double.parseDouble(values[1]),
            Double.parseDouble(values[2]));
      }
    }
    return null;
  }

  private static LabelEditSession labelEditSession(String[] args) {
    boolean enabled = false;
    for (String argument : args) {
      enabled |= argument.equals("--label-edit");
    }
    if (!enabled) {
      return LabelEditSession.disabled();
    }
    try {
      return LabelEditSession.open(
          argumentPath(args, "--label-overrides=",
              "src/main/resources/presentation/label-layout-overrides.tsv"),
          argumentPath(args, "--label-base-glyphs=",
              "src/main/resources/presentation/prepared-strategic-label-base-glyphs.tsv"),
          argumentPath(args, "--label-runtime-glyphs=",
              "src/main/resources/presentation/prepared-strategic-label-glyphs.tsv"));
    } catch (IOException failure) {
      throw new IllegalStateException("Unable to start label edit mode", failure);
    }
  }

  static boolean outlinesOnly(String[] args) {
    for (String argument : args) {
      if (argument.equals("--map-outlines-only")) {
        return true;
      }
    }
    return false;
  }

  private static Path argumentPath(String[] args, String prefix, String defaultPath) {
    for (String argument : args) {
      if (argument.startsWith(prefix)) {
        return Path.of(argument.substring(prefix.length())).toAbsolutePath();
      }
    }
    return Path.of(defaultPath).toAbsolutePath();
  }

  private static final class SimpleGame implements ApplicationListener {
    private final double smokeSeconds;
    private final String screenshotPath;
    private final MapView mapView;
    private final LabelEditSession labelEditSession;
    private final boolean outlinesOnly;
    private final long launchStartedNanos;
    private StrategicMapScreen screen;
    private double elapsedSeconds;
    private boolean screenshotCaptured;

    private SimpleGame(
        double smokeSeconds,
        String screenshotPath,
        MapView mapView,
        LabelEditSession labelEditSession,
        boolean outlinesOnly,
        long launchStartedNanos) {
      this.smokeSeconds = smokeSeconds;
      this.screenshotPath = screenshotPath;
      this.mapView = mapView;
      this.labelEditSession = labelEditSession;
      this.outlinesOnly = outlinesOnly;
      this.launchStartedNanos = launchStartedNanos;
    }

    @Override
    public void create() {
      var worldMap = WorldMapAssetLoader.loadDefault();
      var places = new VisiblePlaceQuery(
          PlaceFeatureLoader.loadDefault(), new FlatMapProjection());
      var presentation = new PoliticalMapPresentationAdapter(
          PoliticalLabelPlacementLoader.loadDefault()).compose(
              PoliticalSnapshotLoader.loadDefault(), worldMap, places);
      screen = new StrategicMapScreen(
          launchStartedNanos,
          smokeSeconds > 0.0,
          worldMap,
          places,
          presentation.regionStyles(),
          presentation.groupLabels(),
          presentation.capitalPlaceIds(),
          presentation.tooltipAdditions(),
          labelEditSession,
          outlinesOnly);
      screen.show();
      screen.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
      if (mapView != null) {
        screen.focusGeographic(mapView.latitude(), mapView.longitude(), mapView.zoom());
      }
    }

    @Override
    public void render() {
      float delta = Gdx.graphics.getDeltaTime();
      screen.render(delta);
      if (!screenshotCaptured && !screenshotPath.isEmpty() && elapsedSeconds >= 1.0) {
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, width, height, true);
        Pixmap screenshot = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        ByteBuffer screenshotPixels = screenshot.getPixels();
        screenshotPixels.clear();
        screenshotPixels.put(pixels);
        screenshotPixels.flip();
        PixmapIO.writePNG(Gdx.files.absolute(screenshotPath), screenshot);
        screenshot.dispose();
        screenshotCaptured = true;
      }
      if (smokeSeconds > 0.0) {
        elapsedSeconds += delta;
        if (elapsedSeconds >= smokeSeconds) {
          Gdx.app.log("Baseline", screen.baselineReport());
          Gdx.app.exit();
        }
      }
    }

    @Override
    public void resize(int width, int height) {
      screen.resize(width, height);
    }

    @Override
    public void pause() {
      screen.pause();
    }

    @Override
    public void resume() {
      screen.resume();
    }

    @Override
    public void dispose() {
      screen.hide();
      screen.dispose();
    }
  }

  private record MapView(double latitude, double longitude, double zoom) {}
}
