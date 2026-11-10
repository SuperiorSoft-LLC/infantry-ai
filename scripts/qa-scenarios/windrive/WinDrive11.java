import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseWheelEvent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * windrive11: windrive10 plus drag, scrollto and typein (ticket bf14807e). In-window only: it posts AWT events to the
 * app's own components, like windrive10 (whose classes are bundled in the same jar and reused for everything else).
 *
 *   drag "FROM=>TO" [index]       FROM/TO is a node label (as `click`) or "x,y" screen coordinates
 *   scrollto "LABEL" [index]      wheel the window until the node is on screen; answers "OK x,y" (its centre)
 *   typein "LABEL[@dx,dy]=>TEXT" [index]
 *                                 click the node (offset from its centre) to focus a field, then type TEXT. A text
 *                                 field is not in the accessibility tree: use a label shown in or next to it, with
 *                                 @dx,dy to reach the field.
 * Any other command goes to windrive10 unchanged.
 */
public class WinDrive11 {
    static final Set<String> MINE = Set.of("drag", "scrollto", "typein");
    static int lastRot = 1;

    public static void agentmain(String a) { run(a); }

    public static void premain(String a) { run(a); }

    static void run(String a) {
        String[] p = a.split("\\|", -1);
        String cmd = p.length > 1 ? p[1] : "dump";
        if (!MINE.contains(cmd)) { WinDrive10.run(a); return; }
        CompletableFuture<String> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try { f.complete(exec(p)); } catch (Throwable e) { f.completeExceptionally(e); }
        }, "windrive11");
        t.setDaemon(true);
        t.start();
        String res;
        try { res = f.get(40, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) { res = "TIMEOUT: driver did not finish within 40s"; }
        catch (Throwable e) { res = "ERR" + e; }
        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(p[0]),
                java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(res);
        } catch (java.io.IOException ignored) { }
    }

    static String exec(String[] p) throws Exception {
        String cmd = p[1];
        String arg = p.length > 2 ? p[2] : "";
        int idx = p.length > 3 && !p[3].isEmpty() ? Integer.parseInt(p[3]) : 0;
        int sep = arg.indexOf("=>");
        switch (cmd) {
            case "drag": {
                if (sep < 0) return "usage: drag \"FROM=>TO\"";
                Point from = target(arg.substring(0, sep), idx), to = target(arg.substring(sep + 2), 0);
                if (from == null) return "NOTFOUND from: " + arg.substring(0, sep);
                if (to == null) return "NOTFOUND to: " + arg.substring(sep + 2);
                drag(from, to);
                return "OK drag " + from.x + "," + from.y + " -> " + to.x + "," + to.y;
            }
            case "scrollto":
                return scrollTo(arg, idx);
            case "typein": {
                if (sep < 0) return "usage: typein \"LABEL[@dx,dy]=>TEXT\"";
                Point at = target(arg.substring(0, sep), idx);
                if (at == null) return "NOTFOUND: " + arg.substring(0, sep);
                WinDrive10.clickAt(at);
                Thread.sleep(200);
                WinDrive10.typeText(arg.substring(sep + 2));
                return "OK typed at " + at.x + "," + at.y;
            }
            default:
                return "unknown";
        }
    }

    /** "x,y" | "LABEL" | "LABEL@dx,dy" -> screen point, or null when the label is not in the tree. */
    static Point target(String s, int idx) throws Exception {
        s = s.trim();
        if (s.matches("-?\\d+,-?\\d+")) {
            String[] c = s.split(",");
            return new Point(Integer.parseInt(c[0]), Integer.parseInt(c[1]));
        }
        int dx = 0, dy = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*)@(-?\\d+),(-?\\d+)$").matcher(s);
        if (m.matches()) { s = m.group(1); dx = Integer.parseInt(m.group(2)); dy = Integer.parseInt(m.group(3)); }
        WinDrive10.Node n = WinDrive10.find(s, idx);
        if (n == null) return null;
        return new Point(n.screen.x + n.screen.width / 2 + dx, n.screen.y + n.screen.height / 2 + dy);
    }

    static void drag(Point from, Point to) throws Exception {
        WinDrive10.mouse(from, 503, 0, 0, 0);
        Thread.sleep(60);
        WinDrive10.mouse(from, 501, 1024, 1, 1);
        Thread.sleep(120);
        int steps = 14;
        for (int i = 1; i <= steps; i++) {
            Point q = new Point(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps);
            WinDrive10.mouse(q, 506, 1024, 1, 0);
            Thread.sleep(35);
        }
        Thread.sleep(120);
        WinDrive10.mouse(to, 502, 0, 1, 1);
    }

    static void wheel(Point p, int rotation) throws Exception {
        WinDrive10.onEdt(() -> {
            Window w = WinDrive10.window();
            Point wp = new Point(p);
            SwingUtilities.convertPointFromScreen(wp, w);
            Component c = SwingUtilities.getDeepestComponentAt(w, wp.x, wp.y);
            if (c == null) c = w;
            Point cp = new Point(p);
            SwingUtilities.convertPointFromScreen(cp, c);
            c.dispatchEvent(new MouseWheelEvent(c, 507, System.currentTimeMillis(), 0, cp.x, cp.y, p.x, p.y, 0, false, 0, 3, rotation));
            return null;
        });
    }

    static String scrollTo(String label, int idx) throws Exception {
        Rectangle w = WinDrive10.onEdt(() -> WinDrive10.window().getBounds());
        Point mid = new Point(w.x + w.width / 2, w.y + w.height / 2);
        int top = w.y + 90, bottom = w.y + w.height - 70;
        double perNotch = 0;   // pixels the node moves per wheel notch, learned after the first wheel
        int sign = 1, stuck = 0;
        Double last = null;
        for (int i = 0; i < 60; i++) {
            WinDrive10.Node n = WinDrive10.find(label, idx);
            if (n == null) return "NOTFOUND: " + label;
            int cy = n.screen.y + n.screen.height / 2, cx = n.screen.x + n.screen.width / 2;
            if (cy >= top && cy <= bottom) return "OK " + cx + "," + cy;
            double delta = cy - mid.y;   // > 0: node below the middle, so scroll down (positive rotation)
            if (last != null) {
                double moved = last - delta;
                if (Math.abs(moved) < 2) {
                    if (++stuck >= 2) { sign = -sign; stuck = 0; if (i > 20) return "STUCK: " + label + " at y=" + cy; }
                } else {
                    stuck = 0;
                    perNotch = Math.abs(moved) / Math.max(1, lastRot);
                }
            }
            int rot = perNotch > 0 ? (int) Math.max(1, Math.min(60, Math.abs(delta) / perNotch))
                                   : (int) Math.max(1, Math.min(10, Math.abs(delta) / 120));
            wheel(mid, delta > 0 ? rot * sign : -rot * sign);
            last = delta;
            lastRot = rot;
            Thread.sleep(250);
        }
        return "STUCK: " + label + " not reachable";
    }
}
