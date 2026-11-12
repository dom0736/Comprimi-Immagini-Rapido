import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Anteprima prima/dopo: una linea che si trascina divide l'originale (sinistra) dalla versione compressa (destra).
 * In modalità ritaglio mostra l'immagine intera e si trascina un rettangolo.
 * Lavora su versioni ridotte (max ~1600 px) dell'immagine, così trascinare è sempre fluido.
 */
class Confronto extends JComponent {
    static final int MAX_VISTA = 2400;
    private static final int TOP = 50, MARG = 14;

    // stato
    private BufferedImage vFull, vOrig, vRis, vPrec;
    private int imgW, imgH;
    Rectangle crop;
    private boolean cropMode, caricando, calcolando, haRisultato, primo;
    private String titolo = "", meta = "";
    private long orig, out;
    private int rw, rh;
    private Rectangle drag;
    private Point inizio;
    Consumer<Rectangle> onCrop;
    Runnable onReset;

    // animazioni
    private final Anim.Val split = new Anim.Val(.5, this::repaint);
    private final Anim.Val fade = new Anim.Val(1, this::repaint);
    private final Anim.Val vis = new Anim.Val(1, this::repaint);
    private final Anim.Val man = new Anim.Val(0, this::repaint);
    private final Anim.Val pct = new Anim.Val(0, this::repaint);
    private final Anim.Val chip = new Anim.Val(0, this::repaint);
    private double rotazione;
    private boolean cicloAttivo;
    private long ultimo;

    final Bott bCrop, bReset;

    Confronto() {
        setLayout(null);
        bCrop = new Bott("Ritaglia", "crop", null);
        bCrop.azione = () -> impostaCrop(bCrop.isOn());
        bCrop.toggle = true;
        bCrop.alt = 30;
        bCrop.fs = 12;
        bReset = new Bott("Azzera", "reset", () -> {
            if (onReset != null) onReset.run();
        });
        bReset.alt = 30;
        bReset.fs = 12;
        add(bCrop);
        add(bReset);
        bCrop.setVisible(false);
        bReset.setVisible(false);
        split.ease(Anim.OUT_QUINT);

        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (cropMode) {
                    Rectangle f = fit();
                    if (f == null || !f.contains(e.getPoint())) return;
                    inizio = clampImg(e.getPoint());
                    drag = null;
                } else if (haRisultato) {
                    man.to(1, 120);
                    Rectangle f = fit();
                    if (f != null) split.to(vSplit(e.getX(), f), 200);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Rectangle f = fit();
                if (f == null) return;
                if (cropMode) {
                    if (inizio == null) return;
                    Point q = clampImg(e.getPoint());
                    drag = new Rectangle(Math.min(inizio.x, q.x), Math.min(inizio.y, q.y), Math.abs(q.x - inizio.x), Math.abs(q.y - inizio.y));
                    repaint();
                } else if (haRisultato) {
                    split.jump(vSplit(e.getX(), f));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (cropMode && drag != null && drag.width > 8 && drag.height > 8 && onCrop != null) onCrop.accept(new Rectangle(drag));
                drag = null;
                inizio = null;
                man.to(0, 220);
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                Rectangle f = fit();
                boolean sopra = f != null && f.contains(e.getPoint());
                setCursor(Cursor.getPredefinedCursor(cropMode ? Cursor.CROSSHAIR_CURSOR : sopra && haRisultato ? Cursor.E_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
                if (!cropMode && haRisultato && f != null) {
                    double sx = f.x + f.width * split.get();
                    man.to(Math.abs(e.getX() - sx) < 26 ? .6 : 0, 140);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (drag == null) man.to(0, 220);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    private double vSplit(int x, Rectangle f) {
        return Math.max(0, Math.min(1, (x - f.x) / (double) f.width));
    }

    // ---------------------------------------------------------------- stato dall'esterno
    void vuoto() {
        vFull = vOrig = vRis = vPrec = null;
        haRisultato = false;
        caricando = false;
        calcolando = false;
        crop = null;
        drag = null;
        impostaCropSilenzioso(false);
        bCrop.setVisible(false);
        bReset.setVisible(false);
        titolo = meta = "";
        vis.jump(0);
        vis.to(1, 400);
        repaint();
    }

    void carico(String nome) {
        vuoto();
        titolo = nome;
        caricando = true;
        avviaCiclo();
    }

    /** L'immagine è stata letta: `vista` è la versione ridotta dell'intera immagine. */
    void immagine(String nome, BufferedImage vista, int w, int h, Rectangle crop, String metaTesto) {
        caricando = false;
        calcolando = true;
        titolo = nome;
        meta = metaTesto;
        vFull = vista;
        vOrig = vista;
        vRis = vPrec = null;
        haRisultato = false;
        imgW = w;
        imgH = h;
        this.crop = crop;
        primo = true;
        impostaCropSilenzioso(false);
        bCrop.setVisible(true);
        bReset.setVisible(true);
        bReset.setEnabled(crop != null);
        vis.jump(0);
        vis.to(1, 360);
        avviaCiclo();
        doLayout();
        repaint();
    }

    void errore(String nome, String testo) {
        vuoto();
        titolo = nome;
        meta = testo;
        repaint();
    }

    /** Arriva il risultato della compressione. vOrigRegione è null se non c'è ritaglio. */
    void risultato(BufferedImage vOrigRegione, BufferedImage vRisultato, long origByte, long outByte, int w, int h) {
        calcolando = false;
        vOrig = vOrigRegione != null ? vOrigRegione : vFull;
        vPrec = haRisultato ? vRis : null;
        vRis = vRisultato;
        orig = origByte;
        out = outByte;
        rw = w;
        rh = h;
        double p = origByte > 0 ? 100.0 - 100.0 * outByte / origByte : 0;
        pct.to(p, 700);
        if (chip.get() < 1) chip.to(1, 400);
        if (primo) { // la prima volta la linea "scopre" il risultato scorrendo da destra
            primo = false;
            haRisultato = true;
            split.jump(1);
            split.to(.5, 1100);
            fade.jump(1);
        } else {
            haRisultato = true;
            if (vPrec != null) {
                fade.jump(0);
                fade.to(1, 260);
            }
        }
        repaint();
    }

    void inCalcolo(boolean v) {
        if (calcolando == v) return;
        calcolando = v;
        if (v) avviaCiclo();
        repaint();
    }

    void impostaCrop(boolean v) {
        cropMode = v;
        drag = null;
        vis.jump(.35);
        vis.to(1, 300);
        setCursor(Cursor.getPredefinedCursor(v ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    private void impostaCropSilenzioso(boolean v) {
        cropMode = v;
        bCrop.setOn(v, false);
    }

    void cambiatoCrop(Rectangle nuovo) {
        crop = nuovo;
        bReset.setEnabled(nuovo != null);
        if (nuovo != null) calcolando = true;
        repaint();
    }

    boolean inCropMode() {
        return cropMode;
    }

    void esciDaCrop() {
        if (cropMode) {
            bCrop.setOn(false, true);
            impostaCrop(false);
        }
    }

    // ---------------------------------------------------------------- animazione del cerchio di attesa
    private void avviaCiclo() {
        if (cicloAttivo) return;
        cicloAttivo = true;
        Anim.ciclo(() -> {
            if (!isShowing() || !(caricando || calcolando)) {
                cicloAttivo = false;
                repaint();
                return false;
            }
            long t = Anim.ora();
            if (t - ultimo >= 16) {
                ultimo = t;
                rotazione = (t % 900) / 900.0 * 360;
                repaint();
            }
            return true;
        });
    }

    // ---------------------------------------------------------------- geometria
    private Rectangle area() {
        return new Rectangle(MARG, TOP, getWidth() - 2 * MARG, getHeight() - TOP - MARG);
    }

    /** Rettangolo (in pixel dello schermo) dove sta l'immagine mostrata. */
    private Rectangle fit() {
        BufferedImage base = cropMode ? vFull : vOrig;
        if (base == null) return null;
        Rectangle a = area();
        double ar = a.width / (double) Math.max(1, a.height);
        double ir = base.getWidth() / (double) base.getHeight();
        int w, h;
        if (ir > ar) {
            w = a.width;
            h = (int) Math.round(a.width / ir);
        } else {
            h = a.height;
            w = (int) Math.round(a.height * ir);
        }
        return new Rectangle(a.x + (a.width - w) / 2, a.y + (a.height - h) / 2, Math.max(1, w), Math.max(1, h));
    }

    private Point clampImg(Point p) {
        Rectangle f = fit();
        int x = (int) Math.round((p.x - f.x) / (double) f.width * imgW);
        int y = (int) Math.round((p.y - f.y) / (double) f.height * imgH);
        return new Point(Math.max(0, Math.min(imgW, x)), Math.max(0, Math.min(imgH, y)));
    }

    @Override
    public void doLayout() {
        int w = getWidth();
        int y = (TOP - 30) / 2 + 2;
        int w1 = bReset.getPreferredSize().width, w2 = bCrop.getPreferredSize().width;
        bReset.setBounds(w - MARG - w1, y, w1, 30);
        bCrop.setBounds(w - MARG - w1 - 8 - w2, y, w2, 30);
    }

    // ---------------------------------------------------------------- disegno
    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        int W = getWidth(), H = getHeight();
        Gfx.card(g, 0, 0, W, H, 20);
        double v = vis.get();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, v))));

        // testata: nome e dati
        if (!titolo.isEmpty()) {
            g.setFont(Tema.fb(14f));
            FontMetrics fm = g.getFontMetrics();
            int maxT = W - 2 * MARG - (bCrop.isVisible() ? bCrop.getWidth() + bReset.getWidth() + 24 : 0) - 8;
            g.setColor(p.fg);
            g.drawString(Gfx.tagliaNomeFile(titolo, fm, Math.max(60, maxT)), MARG + 4, 27);
            g.setFont(Tema.f(11.5f));
            g.setColor(p.mut);
            g.drawString(Gfx.taglia(meta, g.getFontMetrics(), Math.max(60, maxT)), MARG + 4, 43);
        }

        Rectangle f = fit();
        if (caricando) {
            spinner(g, W / 2.0, H / 2.0 + 8, "Carico l'immagine…");
        } else if (f == null) {
            statoVuoto(g, W, H);
        } else {
            immagini(g, f);
        }
        if (calcolando && f != null) { // pillola "Calcolo…" in alto a sinistra sull'immagine
            String t = "Calcolo…";
            g.setFont(Tema.fb(11.5f));
            int tw = g.getFontMetrics().stringWidth(t);
            g.setColor(new Color(10, 16, 28, 160));
            g.fill(Gfx.rr(f.x + 10, f.y + 10, tw + 38, 26, 13));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(f.x + 18, f.y + 17, 12, 12, -rotazione, 270, Arc2D.OPEN));
            g.drawString(t, (float) (f.x + 36), (float) (f.y + 28));
        }
        g.dispose();
    }

    private void statoVuoto(Graphics2D g, int W, int H) {
        Tema.Pal p = Tema.p;
        double cx = W / 2.0, cy = H / 2.0;
        Gfx.glifo(g, "image", cx, cy - 22, 60, Gfx.mulA(p.mut, .55));
        Gfx.testoCentro(g, meta.isEmpty() ? "Qui vedrai il prima e il dopo" : meta, Tema.fb(15f), p.fg, cx, cy + 28);
        if (meta.isEmpty()) Gfx.testoCentro(g, "Scegli un'immagine dall'elenco", Tema.f(12.5f), p.mut, cx, cy + 50);
    }

    private void spinner(Graphics2D g, double cx, double cy, String t) {
        Tema.Pal p = Tema.p;
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Gfx.a(p.mut, 60));
        g.draw(new Ellipse2D.Double(cx - 16, cy - 36, 32, 32));
        g.setPaint(new java.awt.GradientPaint((float) (cx - 16), 0, p.acc1, (float) (cx + 16), 0, p.acc2));
        g.draw(new Arc2D.Double(cx - 16, cy - 36, 32, 32, -rotazione, 100, Arc2D.OPEN));
        Gfx.testoCentro(g, t, Tema.f(12.5f), p.mut, cx, cy + 12);
    }

    private void immagini(Graphics2D g, Rectangle f) {
        Tema.Pal p = Tema.p;
        Shape clip = Gfx.rr(f.x, f.y, f.width, f.height, 12);
        Shape old = g.getClip();
        g.clip(clip);
        BufferedImage base = cropMode ? vFull : vOrig;
        g.setColor(p.campo);
        g.fill(clip.getBounds2D());
        g.drawImage(base, f.x, f.y, f.width, f.height, null);
        double sp = split.get();
        int sx = f.x + (int) Math.round(f.width * sp);
        if (!cropMode && haRisultato && vRis != null) {
            Shape c2 = g.getClip();
            g.clipRect(sx, f.y, f.x + f.width - sx, f.height);
            if (vPrec != null && fade.get() < 1) g.drawImage(vPrec, f.x, f.y, f.width, f.height, null);
            java.awt.Composite co = g.getComposite();
            float al = vPrec != null ? (float) Math.max(0, Math.min(1, fade.get())) : 1f;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, al * (co instanceof AlphaComposite ? ((AlphaComposite) co).getAlpha() : 1f)));
            g.drawImage(vRis, f.x, f.y, f.width, f.height, null);
            g.setComposite(co);
            g.setClip(c2);
        }
        if (cropMode) selezione(g, f);
        g.setClip(old);

        // contorno sottile attorno all'immagine
        g.setColor(Gfx.a(Color.WHITE, 40));
        g.setStroke(new BasicStroke(1f));
        g.draw(Gfx.rr(f.x + .5, f.y + .5, f.width - 1, f.height - 1, 12));

        if (!cropMode && haRisultato) {
            etichetta(g, "Originale", f.x + 10, f.y + 10, false, sx, f);
            etichetta(g, "Compressa", f.x + f.width - 10, f.y + 10, true, sx, f);
            linea(g, f, sx);
            statistiche(g, f);
        } else if (cropMode) {
            etichetta(g, "Trascina per ritagliare", f.x + 10, f.y + 10, false, -1, f);
        }
    }

    private void etichetta(Graphics2D g, String t, int x, int y, boolean destra, int sx, Rectangle f) {
        g.setFont(Tema.fb(11.5f));
        int tw = g.getFontMetrics().stringWidth(t) + 18;
        int xx = destra ? x - tw : x;
        if (calcolando && !destra && !cropMode) y += 34; // la pillola "Calcolo…" occupa l'angolo
        if (sx >= 0) { // se la linea passa sopra, l'etichetta sfuma
            double sopra = destra ? (sx > xx ? 1 : 0) : (sx < xx + tw ? 1 : 0);
            if (sopra >= 1) return;
        }
        g.setColor(new Color(10, 16, 28, 150));
        g.fill(Gfx.rr(xx, y, tw, 24, 12));
        g.setColor(Color.WHITE);
        g.drawString(t, xx + 9, y + 16);
    }

    private void linea(Graphics2D g, Rectangle f, int sx) {
        double m = man.get();
        g.setColor(new Color(0, 0, 0, 60));
        g.setStroke(new BasicStroke(4f));
        g.draw(new Line2D.Double(sx, f.y, sx, f.y + f.height));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Line2D.Double(sx, f.y, sx, f.y + f.height));
        double cy = f.y + f.height / 2.0, r = 15 + 3 * m;
        for (int i = 6; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, (int) ((1 - i / 7.0) * 36)));
            g.fill(new Ellipse2D.Double(sx - r - i, cy - r - i + 2, 2 * r + 2 * i, 2 * r + 2 * i));
        }
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(sx - r, cy - r, 2 * r, 2 * r));
        g.setPaint(new java.awt.GradientPaint((float) (sx - r), 0, Tema.p.acc1, (float) (sx + r), 0, Tema.p.acc2));
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D a = new Path2D.Double(), b = new Path2D.Double();
        double k = 4.2;
        a.moveTo(sx - 4, cy - k);
        a.lineTo(sx - 4 - k, cy);
        a.lineTo(sx - 4, cy + k);
        b.moveTo(sx + 4, cy - k);
        b.lineTo(sx + 4 + k, cy);
        b.lineTo(sx + 4, cy + k);
        g.draw(a);
        g.draw(b);
    }

    private void statistiche(Graphics2D g, Rectangle f) {
        double c = chip.get();
        if (c < 0.02 || orig <= 0) return;
        Tema.Pal p = Tema.p;
        String a = Motore.peso(orig) + "  " + (imgW > 0 && crop == null ? "(" + imgW + "×" + imgH + ")" : "");
        String b = Motore.peso(out) + "  (" + rw + "×" + rh + ")";
        double pc = pct.get();
        String ps = (pc >= 0 ? "−" : "+") + Math.abs(Math.round(pc)) + "%";
        g.setFont(Tema.f(12.5f));
        FontMetrics fm = g.getFontMetrics();
        int wa = fm.stringWidth(a.trim()), wb = fm.stringWidth(b);
        g.setFont(Tema.fb(14f));
        int wp = g.getFontMetrics().stringWidth(ps);
        double total = wa + 26 + wb + 22 + wp + 30;
        double maxW = f.width - 20;
        if (total > maxW) { // spazio stretto: solo pesi e percentuale
            a = Motore.peso(orig);
            b = Motore.peso(out);
            g.setFont(Tema.f(12.5f));
            fm = g.getFontMetrics();
            wa = fm.stringWidth(a);
            wb = fm.stringWidth(b);
            total = wa + 26 + wb + 22 + wp + 30;
        }
        double x = f.x + (f.width - total) / 2, y = f.y + f.height - 14 - 34 + (1 - c) * 12;
        java.awt.Composite co = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, c) * (co instanceof AlphaComposite ? ((AlphaComposite) co).getAlpha() : 1f)));
        g.setColor(new Color(10, 16, 28, 190));
        g.fill(Gfx.rr(x, y, total, 34, 17));
        g.setColor(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(1f));
        g.draw(Gfx.rr(x + .5, y + .5, total - 1, 33, 16.5));
        g.setFont(Tema.f(12.5f));
        g.setColor(new Color(210, 220, 236));
        double ty = y + 21;
        g.drawString(a.trim(), (float) (x + 16), (float) ty);
        double ax = x + 16 + wa + 6;
        g.setColor(new Color(160, 176, 200));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(ax, y + 17, ax + 14, y + 17));
        g.draw(new Line2D.Double(ax + 9, y + 12.5, ax + 14, y + 17));
        g.draw(new Line2D.Double(ax + 9, y + 21.5, ax + 14, y + 17));
        g.setColor(Color.WHITE);
        g.drawString(b, (float) (ax + 26), (float) ty);
        g.setFont(Tema.fb(14f));
        g.setColor(pc >= 0 ? Tema.hex("#8be9b0") : Tema.hex("#ffa08f"));
        g.drawString(ps, (float) (x + total - 16 - wp), (float) (y + 22));
        g.setComposite(co);
    }

    /** Rettangolo di ritaglio con il resto dell'immagine scurito. */
    private void selezione(Graphics2D g, Rectangle f) {
        Rectangle r = drag != null ? drag : crop;
        if (r == null || imgW <= 0) return;
        double sx = f.width / (double) imgW, sy = f.height / (double) imgH;
        double rx = f.x + r.x * sx, ry = f.y + r.y * sy, rw2 = r.width * sx, rh2 = r.height * sy;
        Area a = new Area(new java.awt.geom.Rectangle2D.Double(f.x, f.y, f.width, f.height));
        a.subtract(new Area(new java.awt.geom.Rectangle2D.Double(rx, ry, rw2, rh2)));
        g.setColor(new Color(0, 0, 0, 140));
        g.fill(a);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
        g.draw(new java.awt.geom.Rectangle2D.Double(rx, ry, rw2, rh2));
        g.setStroke(new BasicStroke(1f));
        for (double[] c : new double[][]{{rx, ry}, {rx + rw2, ry}, {rx, ry + rh2}, {rx + rw2, ry + rh2}}) {
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(c[0] - 4, c[1] - 4, 8, 8));
        }
        String t = r.width + " × " + r.height;
        g.setFont(Tema.fb(11f));
        int tw = g.getFontMetrics().stringWidth(t) + 14;
        double ty = ry + rh2 + 8 + 20 > f.y + f.height ? ry - 28 : ry + rh2 + 8;
        g.setColor(new Color(10, 16, 28, 170));
        g.fill(Gfx.rr(rx, Math.max(f.y + 4, ty), tw, 20, 10));
        g.setColor(Color.WHITE);
        g.drawString(t, (float) (rx + 7), (float) (Math.max(f.y + 4, ty) + 14));
    }
}
