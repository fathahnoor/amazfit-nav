import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rasterises the maneuver arrows to PNG.
 *
 * Zepp resolves a relayed notification's icon from its resource; a VectorDrawable fails
 * that lookup and the watch then shows its generic blue "APP" badge instead of ours, so
 * these must be bitmaps.
 */
public final class GenIcons {

    private static final int SIZE = 144;           // xxhdpi, 48dp
    private static final float SCALE = SIZE / 24f; // artwork is authored in a 24x24 box
    private static final Pattern CMD = Pattern.compile("([MLQ])([^MLQ]*)");
    private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static final String[][] ICONS = {
            {"ic_nav_straight", "0", "M12,21 L12,4.5 M7.2,9.3 L12,4.5 L16.8,9.3"},
            {"ic_nav_slight_right", "45", "M12,22 L12,7 M7.4,11.6 L12,7 L16.6,11.6"},
            {"ic_nav_slight_left", "-45", "M12,22 L12,7 M7.4,11.6 L12,7 L16.6,11.6"},
            {"ic_nav_right", "0", "M7,22 L7,11 Q7,7 11,7 L19.5,7 M15,3 L19.5,7 L15,11"},
            {"ic_nav_left", "0", "M17,22 L17,11 Q17,7 13,7 L4.5,7 M9,3 L4.5,7 L9,11"},
            {"ic_nav_uturn", "0", "M17,22 L17,10 Q17,5 12,5 Q7,5 7,10 L7,15 M3,11 L7,15.5 L11,11"},
            {"ic_nav_roundabout", "0",
                    "M16,8 Q20,12 16,16 Q12,20 8,16 Q4,12 8,8 Q10,5 16,8"
                            + " M11.2,14.3 L11.2,21 M7.4,17.2 L11.2,21 L15,17.2"},
            {"ic_nav_arrive", "0", "M6,22 L6,3 M6,4.5 L17,4.5 L17,12.5 L6,12.5"},
    };

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "app/res/drawable-xxhdpi");
        out.mkdirs();
        for (String[] icon : ICONS) {
            render(out, icon[0], Integer.parseInt(icon[1]), icon[2]);
        }
        System.out.println("wrote " + ICONS.length + " icons to " + out);
    }

    private static void render(File dir, String name, int rotation, String data) throws Exception {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.6f * SCALE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        AffineTransform t = new AffineTransform();
        t.scale(SCALE, SCALE);
        if (rotation != 0) t.rotate(Math.toRadians(rotation), 12, 12);
        g.setTransform(t);
        g.draw(toPath(data));
        g.dispose();

        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    private static Path2D toPath(String data) {
        Path2D.Float p = new Path2D.Float();
        Matcher m = CMD.matcher(data);
        while (m.find()) {
            List<Double> v = numbers(m.group(2));
            switch (m.group(1)) {
                case "M":
                    p.moveTo(v.get(0), v.get(1));
                    break;
                case "L":
                    p.lineTo(v.get(0), v.get(1));
                    break;
                case "Q":
                    p.quadTo(v.get(0), v.get(1), v.get(2), v.get(3));
                    break;
                default:
                    break;
            }
        }
        return p;
    }

    private static List<Double> numbers(String s) {
        List<Double> out = new ArrayList<>();
        Matcher m = NUM.matcher(s);
        while (m.find()) out.add(Double.parseDouble(m.group()));
        return out;
    }
}
