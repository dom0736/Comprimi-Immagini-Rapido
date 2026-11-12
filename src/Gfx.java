import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Piccoli attrezzi di disegno: vetro, sfumature, ombre, icone vettoriali, logo. */
final class Gfx {
    private Gfx() {
    }

    static Graphics2D g2(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g2;
    }

    static Color a(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /** Stesso colore con l'opacità moltiplicata per f (0..1). */
    static Color mulA(Color c, double f) {
        return a(c, (int) Math.round(c.getAlpha() * Math.max(0, Math.min(1, f))));
    }

    static Color mix(Color x, Color y, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                (int) Math.round(x.getRed() + (y.getRed() - x.getRed()) * t),
                (int) Math.round(x.getGreen() + (y.getGreen() - x.getGreen()) * t),
                (int) Math.round(x.getBlue() + (y.getBlue() - x.getBlue()) * t),
                (int) Math.round(x.getAlpha() + (y.getAlpha() - x.getAlpha()) * t));
    }

    static RoundRectangle2D.Float rr(double x, double y, double w, double h, double r) {
        return new RoundRectangle2D.Float((float) x, (float) y, (float) w, (float) h, (float) (r * 2), (float) (r * 2));
    }

    // ---------------------------------------------------------------- sfondo
    static BufferedImage fondo(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Tema.Pal p = Tema.p;
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setPaint(new LinearGradientPaint(0, 0, 0, h, new float[]{0f, .5f, 1f}, p.grad));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < p.glowCol.length; i++) {
            float cx = w * p.glowPos[i][0], cy = h * p.glowPos[i][1];
            float r = Math.max(w, h) * p.glowPos[i][2] / 2f;
            Color c = p.glowCol[i];
            int al = Math.round(255 * p.glowA[i]);
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r, new float[]{0f, .45f, 1f},
                    new Color[]{a(c, al), a(c, al / 4), a(c, 0)}));
            g.fillRect(0, 0, w, h);
        }
        g.dispose();
        return im;
    }

    // ---------------------------------------------------------------- vetro e ombre
    static void ombra(Graphics2D g, double x, double y, double w, double h, double r, int sfuma, double dy) {
        Color base = Tema.p.ombra;
        for (int i = sfuma; i >= 1; i--) {
            double t = 1 - i / (double) (sfuma + 1);
            g.setColor(mulA(base, t * t * 0.55));
            g.fill(rr(x - i, y - i + dy, w + 2 * i, h + 2 * i, r + i));
        }
    }

    static void vetro(Graphics2D g, double x, double y, double w, double h, double r) {
        g.setColor(Tema.p.card);
        g.fill(rr(x, y, w, h, r));
        g.setColor(Tema.p.cardBordo);
        g.setStroke(new BasicStroke(1f));
        g.draw(rr(x + .5, y + .5, w - 1, h - 1, r));
    }

    static void card(Graphics2D g, double x, double y, double w, double h, double r) {
        vetro(g, x, y, w, h, r);
    }

    /** Pillola con sfumatura blu → magenta e un velo di luce in alto. */
    static void pillolaAcc(Graphics2D g, double x, double y, double w, double h, double r, double chiaro) {
        Color c1 = mix(Tema.p.acc1, Color.WHITE, chiaro), c2 = mix(Tema.p.acc2, Color.WHITE, chiaro);
        Shape s = rr(x, y, w, h, r);
        g.setPaint(new GradientPaint((float) x, 0, c1, (float) (x + w), 0, c2));
        g.fill(s);
        Shape old = g.getClip();
        g.clip(s);
        g.setColor(new Color(255, 255, 255, 34));
        g.fill(new java.awt.geom.Rectangle2D.Double(x, y, w, h / 2));
        g.setClip(old);
    }

    static void testoCentro(Graphics2D g, String s, Font f, Color c, double cx, double cy) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
    }

    /** Taglia il testo con «…» se non ci sta. */
    static String taglia(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        String pt = "…";
        int n = s.length();
        while (n > 1 && fm.stringWidth(s.substring(0, n) + pt) > max) n--;
        return s.substring(0, n) + pt;
    }

    /** Come taglia(), ma tiene l'estensione del file (foto_lunga….jpg). */
    static String tagliaNomeFile(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        int dot = s.lastIndexOf('.');
        String ext = dot > 0 && s.length() - dot <= 6 ? s.substring(dot) : "";
        String base = ext.isEmpty() ? s : s.substring(0, dot);
        int n = base.length();
        while (n > 1 && fm.stringWidth(base.substring(0, n) + "…" + ext) > max) n--;
        return base.substring(0, n) + "…" + ext;
    }

    // ---------------------------------------------------------------- icone vettoriali
    /** Disegna l'icona `id` in un riquadro `s` x `s` centrato in (cx, cy). */
    static void glifo(Graphics2D g0, String id, double cx, double cy, double s, Color c) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(cx, cy);
        g.scale(s, s);
        g.setColor(c);
        float lw = 0.115f;
        g.setStroke(new BasicStroke(lw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (id) {
            case "plus":
                g.draw(new Line2D.Double(-.38, 0, .38, 0));
                g.draw(new Line2D.Double(0, -.38, 0, .38));
                break;
            case "x":
                g.draw(new Line2D.Double(-.3, -.3, .3, .3));
                g.draw(new Line2D.Double(.3, -.3, -.3, .3));
                break;
            case "check": {
                Path2D p = new Path2D.Double();
                p.moveTo(-.4, .04);
                p.lineTo(-.12, .32);
                p.lineTo(.42, -.3);
                g.draw(p);
                break;
            }
            case "crop": {
                Path2D p = new Path2D.Double();
                p.moveTo(-.2, -.5);
                p.lineTo(-.2, .2);
                p.lineTo(.5, .2);
                p.moveTo(.2, .5);
                p.lineTo(.2, -.2);
                p.lineTo(-.5, -.2);
                g.draw(p);
                break;
            }
            case "reset": {
                // freccia circolare antioraria: punta simmetrica sull'arco, tutto il disegno centrato sul suo ingombro
                double r = .34, a0 = 200, ext = 290, L = .25, w = .78;
                g.translate(-.010, .085);
                g.draw(new Arc2D.Double(-r, -r, 2 * r, 2 * r, a0, ext, Arc2D.OPEN));
                double ae = Math.toRadians(a0 + ext);
                double ex = r * Math.cos(ae), ey = -r * Math.sin(ae);
                double base = Math.atan2(Math.cos(ae), Math.sin(ae));   // direzione opposta a quella di marcia
                Path2D p = new Path2D.Double();
                p.moveTo(ex + L * Math.cos(base - w), ey + L * Math.sin(base - w));
                p.lineTo(ex, ey);
                p.lineTo(ex + L * Math.cos(base + w), ey + L * Math.sin(base + w));
                g.draw(p);
                break;
            }
            case "copy": {
                Area back = new Area(rr(-.12, -.5, .62, .62, .12));
                back.subtract(new Area(rr(-.58, -.2, .8, .8, .22)));
                g.draw(back);
                g.draw(rr(-.5, -.12, .62, .62, .12));
                break;
            }
            case "image": {
                g.draw(rr(-.5, -.42, 1, .84, .13));
                Path2D p = new Path2D.Double();
                p.moveTo(-.5, .22);
                p.lineTo(-.15, -.1);
                p.lineTo(.12, .14);
                p.lineTo(.26, 0);
                p.lineTo(.5, .24);
                g.draw(p);
                g.fill(new Ellipse2D.Double(.12, -.3, .17, .17));
                break;
            }
            case "save": {
                g.draw(new Line2D.Double(0, -.5, 0, .14));
                Path2D p = new Path2D.Double();
                p.moveTo(-.26, -.12);
                p.lineTo(0, .16);
                p.lineTo(.26, -.12);
                g.draw(p);
                Path2D t = new Path2D.Double();
                t.moveTo(-.5, .2);
                t.lineTo(-.5, .46);
                t.lineTo(.5, .46);
                t.lineTo(.5, .2);
                g.draw(t);
                break;
            }
            case "folder": {
                Path2D p = new Path2D.Double();
                p.moveTo(-.5, -.3);
                p.lineTo(-.14, -.3);
                p.lineTo(.02, -.14);
                p.lineTo(.5, -.14);
                p.lineTo(.5, .38);
                p.lineTo(-.5, .38);
                p.closePath();
                g.draw(p);
                break;
            }
            case "gear": {
                g.draw(new Ellipse2D.Double(-.2, -.2, .4, .4));
                for (int i = 0; i < 8; i++) {
                    double an = i * Math.PI / 4;
                    g.draw(new Line2D.Double(Math.cos(an) * .33, Math.sin(an) * .33, Math.cos(an) * .5, Math.sin(an) * .5));
                }
                break;
            }
            case "book": {
                Path2D p = new Path2D.Double();
                p.moveTo(0, -.34);
                p.quadTo(-.25, -.5, -.5, -.36);
                p.lineTo(-.5, .3);
                p.quadTo(-.25, .16, 0, .4);
                p.quadTo(.25, .16, .5, .3);
                p.lineTo(.5, -.36);
                p.quadTo(.25, -.5, 0, -.34);
                p.moveTo(0, -.34);
                p.lineTo(0, .4);
                g.draw(p);
                break;
            }
            case "flag": {
                g.draw(new Line2D.Double(-.36, -.5, -.36, .5));
                Path2D p = new Path2D.Double();
                p.moveTo(-.36, -.44);
                p.lineTo(.42, -.44);
                p.lineTo(.18, -.14);
                p.lineTo(.42, .14);
                p.lineTo(-.36, .14);
                g.draw(p);
                break;
            }
            case "expand": {
                Path2D p = new Path2D.Double();
                double a = .5, b = .2;
                p.moveTo(-a, -b); p.lineTo(-a, -a); p.lineTo(-b, -a);
                p.moveTo(b, -a); p.lineTo(a, -a); p.lineTo(a, -b);
                p.moveTo(a, b); p.lineTo(a, a); p.lineTo(b, a);
                p.moveTo(-b, a); p.lineTo(-a, a); p.lineTo(-a, b);
                g.draw(p);
                break;
            }
            case "panel": {
                g.draw(rr(-.5, -.4, 1, .8, .12));
                g.draw(new Line2D.Double(-.12, -.4, -.12, .4));
                break;
            }
            case "play": {
                Path2D p = new Path2D.Double();
                p.moveTo(-.24, -.4);
                p.lineTo(.42, 0);
                p.lineTo(-.24, .4);
                p.closePath();
                g.fill(p);
                break;
            }
            case "sun": {
                g.draw(new Ellipse2D.Double(-.2, -.2, .4, .4));
                for (int i = 0; i < 8; i++) {
                    double an = i * Math.PI / 4;
                    g.draw(new Line2D.Double(Math.cos(an) * .34, Math.sin(an) * .34, Math.cos(an) * .48, Math.sin(an) * .48));
                }
                break;
            }
            case "moon": {
                Area m = new Area(new Ellipse2D.Double(-.42, -.42, .84, .84));
                m.subtract(new Area(new Ellipse2D.Double(-.1, -.56, .8, .8)));
                g.fill(m);
                break;
            }
            default:
                break;
        }
        g.dispose();
    }

    static void cuore(Graphics2D g0, double cx, double cy, double size, Color c) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(cx, cy);
        g.scale(size, size);
        Path2D p = new Path2D.Double();
        p.moveTo(0, .42);
        p.curveTo(-.62, -.02, -.5, -.5, -.18, -.46);
        p.curveTo(-.06, -.45, 0, -.34, 0, -.3);
        p.curveTo(0, -.34, .06, -.45, .18, -.46);
        p.curveTo(.5, -.5, .62, -.02, 0, .42);
        p.closePath();
        g.setColor(c);
        g.fill(p);
        g.dispose();
    }

    // ---------------------------------------------------------------- logo
    /** Icona dell'app (stesso stile del Registratore Azioni: fondo grigio-blu, foglio blu → magenta, bollino). */
    static BufferedImage icona(int n) {
        int s = n * 2;
        BufferedImage im = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g2(im.createGraphics());
        Shape sfondo = rr(0, 0, s, s, s * .22);
        g.setClip(sfondo);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(s / 2f, s * .42f), s * .75f, new float[]{0f, 1f},
                new Color[]{Tema.hex("#2c3b52"), Tema.hex("#1d2838")}));
        g.fillRect(0, 0, s, s);
        g.setClip(null);

        double x0 = s * .20, y0 = s * .20, w = s * .60, h = s * .60;
        g.setPaint(new GradientPaint((float) x0, (float) (y0 + h), Tema.hex("#0e46fe"), (float) (x0 + w), (float) y0, Tema.hex("#f03def")));
        g.fill(rr(x0, y0, w, h, s * .08));
        // sole e montagne (la foto)
        g.setColor(new Color(255, 255, 255, 235));
        g.fill(new Ellipse2D.Double(x0 + w * .62, y0 + h * .20, s * .075, s * .075));
        Path2D m = new Path2D.Double();
        m.moveTo(x0 + w * .10, y0 + h * .74);
        m.lineTo(x0 + w * .38, y0 + h * .38);
        m.lineTo(x0 + w * .56, y0 + h * .60);
        m.lineTo(x0 + w * .68, y0 + h * .48);
        m.lineTo(x0 + w * .90, y0 + h * .74);
        m.closePath();
        g.setColor(new Color(255, 255, 255, 215));
        g.fill(m);
        // bollino "si riduce": anello di vetro + disco verde acqua con due frecce che si avvicinano
        double cx = s * .70, cy = s * .70, r = s * .135;
        g.setColor(new Color(255, 255, 255, 40));
        g.fill(new Ellipse2D.Double(cx - r * 1.38, cy - r * 1.38, r * 2.76, r * 2.76));
        g.setColor(new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke((float) (s * .004)));
        g.draw(new Ellipse2D.Double(cx - r * 1.38, cy - r * 1.38, r * 2.76, r * 2.76));
        g.setPaint(new GradientPaint((float) cx, (float) (cy - r), Tema.hex("#34e0c0"), (float) cx, (float) (cy + r), Tema.hex("#0f9f8a")));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (s * .016), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double k = r * .36, q = r * .20; // due frecce che si avvicinano: il file si "stringe"
        Path2D a1 = new Path2D.Double();
        a1.moveTo(cx - k, cy - q - k);
        a1.lineTo(cx, cy - q);
        a1.lineTo(cx + k, cy - q - k);
        Path2D a2 = new Path2D.Double();
        a2.moveTo(cx - k, cy + q + k);
        a2.lineTo(cx, cy + q);
        a2.lineTo(cx + k, cy + q + k);
        g.draw(a1);
        g.draw(a2);
        g.dispose();
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D o = g2(out.createGraphics());
        o.drawImage(im, 0, 0, n, n, null);
        o.dispose();
        return out;
    }

    /** Immagine scalata (qualità alta, a passi di dimezzamento). Mai ingrandisce. */
    static BufferedImage scala(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = passo(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        if (cur.getWidth() != w || cur.getHeight() != h) cur = passo(cur, w, h);
        return cur;
    }

    private static BufferedImage passo(BufferedImage s, int w, int h) {
        BufferedImage o = new BufferedImage(Math.max(1, w), Math.max(1, h),
                s.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(s, 0, 0, o.getWidth(), o.getHeight(), null);
        g.dispose();
        return o;
    }
}
