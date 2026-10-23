// APP-ICON-1 asset generator. Rerun from superiorSoftLibrary/: `java agentHqUi/icons/GenAgentHqIcon.java` (JDK 11+). Not wired into Gradle.
//
// Draws the title bar's Logo() (agenthqUiCore TitleBar.kt) at icon sizes, same proportions: an amber Palette.Accent
// (0xFFF59E0B) tile with corner radius 4/18 of the side, and a dark 0xFF1A1200 Black-weight "A" at 12/18 of the side.
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class GenAgentHqIcon {
    static final Color AMBER = new Color(0xF5, 0x9E, 0x0B);
    static final Color INK = new Color(0x1A, 0x12, 0x00);

    static Font blackFont(float size) {
        Set<String> have = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : new String[]{"Segoe UI Black", "Arial Black", "Roboto Black"}) if (have.contains(f)) return new Font(f, Font.PLAIN, 1).deriveFont(size);
        return new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size);
    }

    /** The "A" centred on (cx, cy), font size [fontPx]. */
    static void drawA(Graphics2D g, float fontPx, double cx, double cy) {
        GlyphVector gv = blackFont(fontPx).createGlyphVector(g.getFontRenderContext(), "A");
        Rectangle2D b = gv.getVisualBounds();
        g.setColor(INK);
        g.fill(gv.getOutline((float) (cx - b.getCenterX()), (float) (cy - b.getCenterY())));
    }

    static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** The full mark: tile + "A". */
    static BufferedImage mark(int side) {
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        double r = side * 4.0 / 18.0;
        g.setColor(AMBER);
        g.fill(new RoundRectangle2D.Double(0, 0, side, side, 2 * r, 2 * r));
        drawA(g, side * 12f / 18f, side / 2.0, side / 2.0);
        g.dispose();
        return img;
    }

    /** Adaptive-icon foreground (108dp canvas): only the "A", sized as on a 72dp visible tile, inside the 66dp safe zone. */
    static BufferedImage foreground(int side) {
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        double tile = side * 72.0 / 108.0;
        drawA(g, (float) (tile * 12.0 / 18.0), side / 2.0, side / 2.0);
        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** An ICO with PNG-compressed entries (supported since Windows Vista, accepted by jpackage). */
    static void writeIco(Path to, int... sizes) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) pngs.add(png(mark(s)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeShort(0); d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) sizes.length));
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            d.writeByte(s >= 256 ? 0 : s); d.writeByte(s >= 256 ? 0 : s); d.writeByte(0); d.writeByte(0);
            d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) 32));
            d.writeInt(Integer.reverseBytes(pngs.get(i).length)); d.writeInt(Integer.reverseBytes(offset));
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) d.write(p);
        Files.createDirectories(to.getParent());
        Files.write(to, out.toByteArray());
    }

    static void writePng(Path to, BufferedImage img) throws IOException {
        Files.createDirectories(to.getParent());
        Files.write(to, png(img));
    }

    public static void main(String[] args) throws IOException {
        Path ui = Paths.get("agentHqUi"), res = Paths.get("agentHqAndroid/src/main/res");
        writePng(ui.resolve("src/main/resources/agenthq-icon.png"), mark(256));
        writeIco(ui.resolve("icons/agenthq.ico"), 16, 24, 32, 48, 64, 128, 256);
        String[] dens = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        double[] scale = {1, 1.5, 2, 3, 4};
        for (int i = 0; i < dens.length; i++) {
            writePng(res.resolve("mipmap-" + dens[i] + "/ic_launcher.png"), mark((int) Math.round(48 * scale[i])));
            writePng(res.resolve("mipmap-" + dens[i] + "/ic_launcher_foreground.png"), foreground((int) Math.round(108 * scale[i])));
        }
        System.out.println("APP-ICON-1 icons written (font: " + blackFont(12).getFamily() + ")");
    }
}
