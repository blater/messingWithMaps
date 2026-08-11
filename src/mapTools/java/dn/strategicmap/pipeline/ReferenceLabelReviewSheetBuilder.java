package dn.strategicmap.pipeline;

import java.io.BufferedWriter;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes a local, annotated SVG review sheet for observations made against a reference scan.
 *
 * <p>The generated SVG deliberately links to the source image rather than embedding it. It is a
 * developer review artefact: neither the supplied scan nor a derivative is put in game assets.</p>
 */
public final class ReferenceLabelReviewSheetBuilder {
  private ReferenceLabelReviewSheetBuilder() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Expected <reference-image> <reference-observations.tsv> <output.svg>");
    }
    Path image = Path.of(args[0]).toAbsolutePath();
    BufferedImage reference = ImageIO.read(image.toFile());
    if (reference == null) {
      throw new IOException("Unable to read reference image: " + image);
    }
    List<Observation> observations = Observation.read(Path.of(args[1]));
    Path output = Path.of(args[2]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    if (output.toString().endsWith(".png")) {
      writePng(reference, observations, output);
      System.out.printf("Prepared %s: observations=%d%n", output, observations.size());
      return;
    }
    try (BufferedWriter writer = Files.newBufferedWriter(output)) {
      writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
          + " viewBox=\"0 0 " + reference.getWidth() + " " + reference.getHeight() + "\">\n");
      writer.write("<image x=\"0\" y=\"0\" width=\"");
      writer.write(Integer.toString(reference.getWidth()));
      writer.write("\" height=\"");
      writer.write(Integer.toString(reference.getHeight()));
      writer.write("\" xlink:href=\"");
      writer.write(xml(image.toUri().toString()));
      writer.write("\"/>\n<g fill=\"none\" stroke=\"#d90000\" stroke-width=\"5\">\n");
      for (Observation observation : observations) {
        writer.write("<polyline points=\"");
        writer.write(points(observation.imagePath()));
        writer.write("\"/>\n");
      }
      writer.write("</g>\n<g fill=\"#d90000\" font-family=\"sans-serif\" font-size=\"32\""
          + " font-weight=\"bold\" stroke=\"white\" stroke-width=\"4\" paint-order=\"stroke\">\n");
      for (Observation observation : observations) {
        ImagePoint start = observation.imagePath().getFirst();
        writer.write("<text x=\"");
        writer.write(Double.toString(start.x()));
        writer.write("\" y=\"");
        writer.write(Double.toString(start.y() - 10));
        writer.write("\">");
        writer.write(xml(observation.labelKey()));
        writer.write("</text>\n");
      }
      writer.write("</g>\n</svg>\n");
    }
    System.out.printf("Prepared %s: observations=%d%n", output, observations.size());
  }

  private static void writePng(BufferedImage image, List<Observation> observations, Path output)
      throws IOException {
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.RED);
    for (Observation observation : observations) {
      List<ImagePoint> path = observation.imagePath();
      for (int index = 1; index < path.size(); index++) {
        ImagePoint start = path.get(index - 1);
        ImagePoint end = path.get(index);
        graphics.drawLine((int) start.x(), (int) start.y(), (int) end.x(), (int) end.y());
      }
      ImagePoint start = path.getFirst();
      graphics.drawString(observation.labelKey(), (int) start.x(), (int) start.y() - 8);
    }
    graphics.dispose();
    ImageIO.write(image, "png", output.toFile());
  }

  private static String points(List<ImagePoint> points) {
    StringBuilder result = new StringBuilder();
    for (ImagePoint point : points) {
      if (!result.isEmpty()) {
        result.append(' ');
      }
      result.append(point.x()).append(',').append(point.y());
    }
    return result.toString();
  }

  private static String xml(String value) {
    return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
  }

  private record ImagePoint(double x, double y) {}

  private record Observation(String labelKey, List<ImagePoint> imagePath) {
    static List<Observation> read(Path source) throws IOException {
      List<Observation> observations = new ArrayList<>();
      for (String line : Files.readAllLines(source)) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("labelKey\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        observations.add(new Observation(fields[0], imagePath(fields[4])));
      }
      return observations;
    }

    private static List<ImagePoint> imagePath(String value) {
      List<ImagePoint> points = new ArrayList<>();
      for (String coordinate : value.split(";")) {
        String[] fields = coordinate.split(",", -1);
        points.add(new ImagePoint(Double.parseDouble(fields[0]), Double.parseDouble(fields[1])));
      }
      return points;
    }
  }
}
