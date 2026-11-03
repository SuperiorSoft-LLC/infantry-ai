// Infantry AI icon generator. Rerun from superiorSoftLibrary/: `java infantryUi/icons/GenInfantryIcon.java` (JDK 11+). Not wired into Gradle.
//
// Source: ../assets/branding/infantry-ai-emblem.png (transparent PNG, red/white triangle emblem with a glow). The glow's faint
// halo is trimmed (alpha > 40) so the emblem fills the icon; everything else is just scaled and padded.
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class GenInfantryIcon {
    static final Color TILE = new Color(0x0B, 0x0B, 0x10);

    static BufferedImage emblem() throws IOException {
        BufferedImage src = ImageIO.read(new File("../assets/branding/infantry-ai-emblem.png"));
        int minx = src.getWidth(), miny = src.getHeight(), maxx = 0, maxy = 0;
        for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++)
            if ((src.getRGB(x, y) >>> 24) > 40) { minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y); }
        return src.getSubimage(minx, miny, maxx - minx + 1, maxy - miny + 1);
    }

    static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** The emblem scaled (by halving, for quality) to fit [fraction] of the square [side], centred, transparent around it. */
    static BufferedImage emblemIn(BufferedImage e, int side, double fraction) {
        double box = side * fraction, s = Math.min(box / e.getWidth(), box / e.getHeight());
        int w = Math.max(1, (int) Math.round(e.getWidth() * s)), h = Math.max(1, (int) Math.round(e.getHeight() * s));
        BufferedImage cur = e;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) cur = scale(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        BufferedImage fit = scale(cur, w, h);
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(out);
        g.drawImage(fit, (side - w) / 2, (side - h) / 2, null);
        g.dispose();
        return out;
    }

    static BufferedImage scale(BufferedImage in, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(out);
        g.drawImage(in, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Transparent square icon: the emblem alone (desktop window, ico, in-app logo). */
    static BufferedImage mark(BufferedImage e, int side) { return emblemIn(e, side, 0.96); }

    /** Legacy launcher icon: the emblem on a dark rounded tile. */
    static BufferedImage tile(BufferedImage e, int side) {
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        g.setColor(TILE);
        g.fillRoundRect(0, 0, side, side, side * 4 / 18 * 2, side * 4 / 18 * 2);
        g.drawImage(emblemIn(e, side, 0.78), 0, 0, null);
        g.dispose();
        return img;
    }

    /** Adaptive foreground (108dp canvas): emblem inside the 66dp safe zone. */
    static BufferedImage foreground(BufferedImage e, int side) { return emblemIn(e, side, 60.0 / 108.0); }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** An ICO with PNG-compressed entries (supported since Windows Vista, accepted by jpackage). */
    static void writeIco(Path to, BufferedImage e, int... sizes) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) pngs.add(png(mark(e, s)));
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
        BufferedImage e = emblem();
        Path ui = Paths.get("infantryUi"), res = Paths.get("infantryAndroid/src/main/res");
        writePng(ui.resolve("src/main/resources/infantry-icon.png"), mark(e, 256));
        writePng(Paths.get("infantryUiCore/src/commonMain/composeResources/drawable/infantry_emblem.png"), mark(e, 256));
        writePng(Paths.get("../assets/branding/infantry-ai-icon-512.png"), mark(e, 512));
        writeIco(ui.resolve("icons/infantry.ico"), e, 16, 24, 32, 48, 64, 128, 256);
        String[] dens = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        double[] scale = {1, 1.5, 2, 3, 4};
        for (int i = 0; i < dens.length; i++) {
            writePng(res.resolve("mipmap-" + dens[i] + "/ic_launcher.png"), tile(e, (int) Math.round(48 * scale[i])));
            writePng(res.resolve("mipmap-" + dens[i] + "/ic_launcher_foreground.png"), foreground(e, (int) Math.round(108 * scale[i])));
        }
        System.out.println("Infantry AI icons written");
    }
}
