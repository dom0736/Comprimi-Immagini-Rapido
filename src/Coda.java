import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Pannello trasparente che compare con dissolvenza e un piccolo scatto verso l'alto. */
class Velo extends JPanel {
    final Anim.Val vis = new Anim.Val(1, this::repaint);
    double dy = 14;

    Velo() {
        setOpaque(false);
    }

    /** Fa ripartire l'animazione di entrata (dopo `ritardo` ms). */
    void entra(int ritardo) {
        vis.jump(0);
        Anim.dopo(ritardo, () -> vis.to(1, 460));
    }

    @Override
    public void paint(Graphics g) {
        double v = vis.get();
        if (v >= 0.999) {
            super.paint(g);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        java.awt.Composite c = g2.getComposite();
        float base = c instanceof AlphaComposite ? ((AlphaComposite) c).getAlpha() : 1f;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, v)) * base));
        g2.translate(0, (1 - v) * dy);
        super.paint(g2);
        g2.dispose();
    }
}

/** Barre di scorrimento sottili e discrete. */
final class ScrollSottile {
    private ScrollSottile() {
    }

    static JScrollPane crea(JComponent c) {
        JScrollPane sp = new JScrollPane(c, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(null);
        sp.setViewportBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 10));
        sp.getVerticalScrollBar().setOpaque(false);
        sp.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
            }

            @Override
            protected javax.swing.JButton createDecreaseButton(int o) {
                return vuoto();
            }

            @Override
            protected javax.swing.JButton createIncreaseButton(int o) {
                return vuoto();
            }

            private javax.swing.JButton vuoto() {
                javax.swing.JButton b = new javax.swing.JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c2, Rectangle r) {
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c2, Rectangle r) {
                if (r.isEmpty() || !scrollbar.isEnabled()) return;
                Graphics2D g2 = Gfx.g2(g);
                g2.setColor(Gfx.a(Tema.p.mut, isDragging ? 150 : 90));
                g2.fill(Gfx.rr(r.x + 2, r.y + 2, r.width - 4, r.height - 4, (r.width - 4) / 2.0));
                g2.dispose();
            }
        });
        return sp;
    }
}

/** Una riga dell'elenco: miniatura, nome, pesi, percentuale. Compare e sparisce con animazione. */
class VoceCard extends JComponent {
    static final int H = 68, GAP = 8;
    final Voce voce;
    final Anim.Val esp, sel, hov, mini, xhov;
    private boolean sopraX;
    Consumer<VoceCard> onClick, onRimuovi;

    VoceCard(Voce voce) {
        this.voce = voce;
        esp = new Anim.Val(0, this::cambioAltezza);
        sel = new Anim.Val(0, this::repaint);
        hov = new Anim.Val(0, this::repaint);
        mini = new Anim.Val(0, this::repaint);
        xhov = new Anim.Val(0, this::repaint);
        esp.ease(Anim.OUT_CUBIC);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hov.to(1, 130);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hov.to(0, 220);
                aggiornaX(false);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                aggiornaX(inX(e.getPoint()));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (inX(e.getPoint())) {
                    if (onRimuovi != null) onRimuovi.accept(VoceCard.this);
                } else if (onClick != null) onClick.accept(VoceCard.this);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    private void cambioAltezza() {
        revalidate();
        repaint();
    }

    private void aggiornaX(boolean v) {
        if (v == sopraX) return;
        sopraX = v;
        xhov.to(v ? 1 : 0, 120);
    }

    private boolean inX(java.awt.Point p) {
        return hov.get() > .05 && Math.hypot(p.x - (getWidth() - 26), p.y - 20) <= 13;
    }

    void arrivataMiniatura() {
        mini.to(1, 380);
    }

    void seleziona(boolean s) {
        sel.to(s ? 1 : 0, 220);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, (int) Math.round((H + GAP) * esp.get()));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        double e = esp.get();
        if (e < 0.02) return;
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        double w = getWidth() - 2;
        g.translate(1, 0);
        g.translate((1 - e) * -26, 0);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, e * 1.3)));
        double s = sel.get(), hv = hov.get();
        // fondo
        g.setColor(Gfx.mix(Gfx.mulA(p.btn, .55), p.btnHover, Math.max(s * .55, hv * .6)));
        g.fill(Gfx.rr(0, 0, w, H, 14));
        if (s > 0.01) {
            g.setPaint(new GradientPaint(0, 0, Gfx.mulA(p.acc1, s), (float) w, 0, Gfx.mulA(p.acc2, s)));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(Gfx.rr(.8, .8, w - 1.6, H - 1.6, 13.2));
        } else {
            g.setColor(Gfx.a(p.btnBordo, 70));
            g.setStroke(new BasicStroke(1f));
            g.draw(Gfx.rr(.5, .5, w - 1, H - 1, 13.5));
        }
        // miniatura
        double tx = 10, ty = 8, ts = 52;
        Shape clip = Gfx.rr(tx, ty, ts, ts, 10);
        Shape old = g.getClip();
        g.clip(clip);
        if (voce.mini != null) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, e * 1.3)));
            g.setColor(p.campo);
            g.fill(clip);
            java.awt.Composite c0 = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (Math.min(1, e * 1.3) * mini.get())));
            g.drawImage(voce.mini, (int) tx, (int) ty, (int) ts, (int) ts, null);
            g.setComposite(c0);
        } else {
            g.setPaint(new GradientPaint((float) tx, (float) ty, Gfx.a(p.acc1, 70), (float) (tx + ts), (float) (ty + ts), Gfx.a(p.acc2, 70)));
            g.fill(clip);
            Gfx.glifo(g, voce.errore != null ? "x" : "image", tx + ts / 2, ty + ts / 2, 22, Gfx.a(p.fg, 150));
        }
        g.setClip(old);
        if (voce.uscita != null) { // salvata: bollino verde con la spunta
            double bx = tx + ts - 8, by = ty + ts - 8;
            g.setColor(p.campo);
            g.fill(new Ellipse2D.Double(bx - 9.5, by - 9.5, 19, 19));
            g.setColor(p.scuro ? Tema.hex("#22b07d") : Tema.hex("#17724a"));
            g.fill(new Ellipse2D.Double(bx - 7.5, by - 7.5, 15, 15));
            Gfx.glifo(g, "check", bx, by, 9, Color.WHITE);
        }
        // testi
        double x = 72;
        g.setFont(Tema.fb(13f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(p.fg);
        double maxW = w - x - 38;
        g.drawString(Gfx.tagliaNomeFile(voce.nome, fm, (int) maxW), (float) x, 28);
        g.setFont(Tema.f(11.5f));
        fm = g.getFontMetrics();
        String sub;
        Color csub = p.mut;
        if (voce.errore != null) {
            sub = voce.errore;
            csub = p.err;
        } else if (voce.out >= 0) {
            sub = Motore.peso(voce.orig) + "  →  " + Motore.peso(voce.out);
        } else {
            sub = Motore.peso(voce.orig) + (voce.larg > 0 ? "  ·  " + voce.larg + "×" + voce.alt : "");
        }
        g.setColor(csub);
        g.drawString(Gfx.taglia(sub, fm, (int) (maxW - (voce.out >= 0 && voce.errore == null ? 52 : 0))), (float) x, 48);
        if (voce.out >= 0 && voce.errore == null && voce.orig > 0) {
            int risp = (int) Math.round(100.0 - 100.0 * voce.out / voce.orig);
            String t = (risp >= 0 ? "−" : "+") + Math.abs(risp) + "%";
            g.setFont(Tema.fb(11f));
            fm = g.getFontMetrics();
            double cw = fm.stringWidth(t) + 14, cx = w - 12 - cw, cy = 36;
            Color c = risp >= 0 ? p.ok : p.err;
            g.setColor(Gfx.a(c, p.scuro ? 36 : 40));
            g.fill(Gfx.rr(cx, cy, cw, 20, 10));
            g.setColor(c);
            g.drawString(t, (float) (cx + 7), (float) (cy + 14));
        }
        // pulsante rimuovi (compare al passaggio del mouse)
        double xh = hv;
        if (xh > 0.02) {
            double cx = w - 25, cy = 20;
            g.setColor(Gfx.mix(Gfx.a(p.fg, (int) (40 * xh)), Gfx.a(p.rec, (int) (200 * xh)), xhov.get()));
            g.fill(new Ellipse2D.Double(cx - 11, cy - 11, 22, 22));
            Gfx.glifo(g, "x", cx, cy, 13, Gfx.mix(Gfx.mulA(p.fg, xh), Gfx.mulA(Color.WHITE, xh), xhov.get()));
        }
        g.dispose();
    }
}

/** Elenco delle immagini: scorrevole, con le righe che entrano e escono con animazione. */
class ListaVoci extends JPanel {
    final List<VoceCard> carte = new ArrayList<>();
    private final Contenitore cont = new Contenitore();
    final JScrollPane scroll = ScrollSottile.crea(cont);
    Consumer<Voce> onSeleziona, onRimuovi;
    private VoceCard selezionata;

    ListaVoci() {
        super(new java.awt.BorderLayout());
        setOpaque(false);
        add(scroll);
    }

    private static final class Contenitore extends JPanel implements Scrollable {
        Contenitore() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return Math.max(40, r.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    VoceCard aggiungi(Voce v, int ritardo) {
        VoceCard c = new VoceCard(v);
        c.onClick = x -> {
            if (onSeleziona != null) onSeleziona.accept(x.voce);
        };
        c.onRimuovi = x -> {
            if (onRimuovi != null) onRimuovi.accept(x.voce);
        };
        carte.add(c);
        cont.add(c);
        cont.revalidate();
        Anim.dopo(ritardo, () -> c.esp.to(1, 380));
        return c;
    }

    VoceCard carta(Voce v) {
        for (VoceCard c : carte) if (c.voce == v) return c;
        return null;
    }

    void rimuovi(Voce v) {
        VoceCard c = carta(v);
        if (c == null) return;
        carte.remove(c);
        if (selezionata == c) selezionata = null;
        c.esp.to(0, 240, () -> {
            cont.remove(c);
            cont.revalidate();
            cont.repaint();
        });
    }

    void svuota() {
        for (VoceCard c : new ArrayList<>(carte)) rimuovi(c.voce);
    }

    void seleziona(Voce v) {
        VoceCard n = carta(v);
        if (selezionata != null && selezionata != n) selezionata.seleziona(false);
        selezionata = n;
        if (n != null) {
            n.seleziona(true);
            Rectangle r = n.getBounds();
            r.height = Math.max(r.height, VoceCard.H + VoceCard.GAP);
            cont.scrollRectToVisible(r);
        }
    }

    int indiceDi(Voce v) {
        for (int i = 0; i < carte.size(); i++) if (carte.get(i).voce == v) return i;
        return -1;
    }
}

/** Area vuota "trascina qui le immagini": icona che galleggia e bagliore che pulsa. */
class ZonaDrop extends JComponent {
    private final Anim.Val hov = new Anim.Val(0, this::repaint);
    final Anim.Val drag = new Anim.Val(0, this::repaint);
    private double fase;
    private long ultimo;
    final Bott scegli, cartella;
    Runnable alClic;

    ZonaDrop(Runnable scegliFile, Runnable scegliCartella) {
        setLayout(null);
        scegli = new Bott("Scegli immagini…", "plus", true, scegliFile);
        cartella = new Bott("o una cartella", "folder", scegliCartella);
        cartella.fs = 12;
        cartella.alt = 32;
        add(scegli);
        add(cartella);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hov.to(1, 200);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hov.to(0, 300);
            }
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) avviaCiclo();
        });
    }

    private boolean attivo;

    /** Movimento lento, solo mentre la zona si vede: ~30 immagini al secondo bastano, e a riposo il timer si ferma. */
    private void avviaCiclo() {
        if (attivo) return;
        attivo = true;
        Anim.ciclo(() -> {
            if (!isShowing()) {
                attivo = false;
                return false;
            }
            long t = Anim.ora();
            if (t - ultimo >= 30) {
                ultimo = t;
                fase = (t % 4200) / 4200.0 * Math.PI * 2;
                repaint();
            }
            return true;
        });
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        Dimension a = scegli.getPreferredSize(), b = cartella.getPreferredSize();
        scegli.setBounds((w - a.width) / 2, h / 2 + 52, a.width, a.height);
        cartella.setBounds((w - b.width) / 2, h / 2 + 52 + a.height + 10, b.width, b.height);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        double w = getWidth(), h = getHeight();
        double d = drag.get(), hv = hov.get();
        double m = 4;
        // bordo tratteggiato
        float[] tratti = {7f, 6f};
        double sfum = 0.5 + 0.5 * Math.sin(fase);
        g.setColor(Gfx.a(p.acc1, (int) (24 + 30 * d + 14 * hv)));
        g.fill(Gfx.rr(m, m, w - 2 * m, h - 2 * m, 18));
        g.setStroke(new BasicStroke(1.6f + (float) d, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, tratti, (float) (fase / (Math.PI * 2) * 26)));
        g.setPaint(new GradientPaint(0, 0, Gfx.a(p.acc1, (int) (110 + 120 * d + 40 * hv)), (float) w, (float) h, Gfx.a(p.acc2, (int) (110 + 120 * d + 40 * hv))));
        g.draw(Gfx.rr(m + 1, m + 1, w - 2 * m - 2, h - 2 * m - 2, 17));
        // icona che galleggia
        double cx = w / 2, cy = h / 2 - 54 + Math.sin(fase) * 3.5 - d * 6;
        double r = 40 + 4 * d;
        for (int i = 12; i >= 1; i--) {
            g.setColor(Gfx.a(p.acc2, (int) ((1 - i / 13.0) * (1 - i / 13.0) * (30 + 40 * sfum + 40 * d))));
            g.fill(Gfx.rr(cx - r - i * 1.6, cy - r - i * 1.6 + 6, 2 * r + i * 3.2, 2 * r + i * 3.2, r * .42 + i * 1.6));
        }
        Gfx.pillolaAcc(g, cx - r, cy - r, 2 * r, 2 * r, r * .42, 0);
        Gfx.glifo(g, d > .5 ? "save" : "image", cx, cy, 38, Color.WHITE);
        // testi
        Gfx.testoCentro(g, d > .5 ? "Rilascia per aggiungere" : "Trascina qui le immagini", Tema.fb(16f), p.fg, cx, h / 2 + 8);
        Gfx.testoCentro(g, "oppure una cartella intera", Tema.f(12.5f), p.mut, cx, h / 2 + 30);
        Gfx.testoCentro(g, "Ctrl+V incolla uno screenshot", Tema.f(11.5f), Gfx.mulA(p.mut, .85), cx, h - 26);
        g.dispose();
    }

    static BufferedImage quadrata(BufferedImage s, int lato) {
        int w = s.getWidth(), h = s.getHeight(), l = Math.min(w, h);
        BufferedImage ritaglio = s.getSubimage((w - l) / 2, (h - l) / 2, l, l);
        return Gfx.scala(Motore.rgb(ritaglio), lato, lato);
    }
}
