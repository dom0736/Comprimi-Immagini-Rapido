import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.function.IntConsumer;

/** Selettore a tendina: un pulsante con la voce scelta; al clic si apre l'elenco con tutte le opzioni. */
class Scelta extends JComponent {
    final String[] nomi, note;
    private int sel;
    IntConsumer onSel;
    int larghezza = 150;
    private final Anim.Val hov = new Anim.Val(0, this::repaint);
    private final Anim.Val aperto = new Anim.Val(0, this::repaint);
    
    Scelta(String[] nomi, String[] note, int sel, IntConsumer onSel) {
        this.nomi = nomi;
        this.note = note;
        this.sel = Math.max(0, Math.min(nomi.length - 1, sel));
        this.onSel = onSel;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hov.to(1, 120);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hov.to(0, 200);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && isEnabled()) apri();
            }
        });
    }

    int selezione() {
        return sel;
    }

    void seleziona(int i, boolean anim, boolean fire) {
        if (i < 0 || i >= nomi.length) return;
        boolean cambia = i != sel;
        sel = i;
        repaint();
        if (fire && cambia && onSel != null) onSel.accept(i);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(larghezza, 36);
    }

    private Lista lista;
    private java.awt.event.AWTEventListener fuori;

    private void apri() {
        if (lista != null) {
            chiudi();
            return;
        }
        javax.swing.JLayeredPane lp = SwingUtilities.getRootPane(this).getLayeredPane();
        lista = new Lista();
        Dimension d = lista.getPreferredSize();
        java.awt.Point q = SwingUtilities.convertPoint(this, getWidth() - d.width, -d.height - 6, lp);
        if (q.y < 0) q = SwingUtilities.convertPoint(this, getWidth() - d.width, getHeight() + 6, lp);
        lista.setBounds(q.x, q.y, d.width, d.height);
        lp.add(lista, javax.swing.JLayeredPane.POPUP_LAYER);
        lp.repaint();
        aperto.to(1, 160);
        lista.entra();
        fuori = ev -> {
            if (ev instanceof MouseEvent && ev.getID() == MouseEvent.MOUSE_PRESSED && lista != null) {
                MouseEvent me = (MouseEvent) ev;
                java.awt.Point pl = SwingUtilities.convertPoint(me.getComponent(), me.getPoint(), lista);
                java.awt.Point pb = SwingUtilities.convertPoint(me.getComponent(), me.getPoint(), this);
                if (!lista.contains(pl) && !contains(pb)) chiudi();
            }
        };
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(fuori, java.awt.AWTEvent.MOUSE_EVENT_MASK);
    }

    boolean aperta() {
        return lista != null;
    }

    void chiudi() {
        if (lista == null) return;
        java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(fuori);
        java.awt.Container pa = lista.getParent();
        if (pa != null) {
            pa.remove(lista);
            pa.repaint();
        }
        lista = null;
        aperto.to(0, 160);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        double w = getWidth(), h = getHeight(), hv = hov.get();
        g.setColor(Gfx.mix(p.btn, p.btnHover, hv));
        g.fill(Gfx.rr(0, 0, w, h, h / 2));
        g.setColor(Gfx.mix(p.btnBordo, p.acc1, aperto.get() * .8));
        g.setStroke(new BasicStroke(1f + (float) aperto.get() * .4f));
        g.draw(Gfx.rr(.5, .5, w - 1, h - 1, h / 2));
        g.setFont(Tema.fb(13f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(p.fg);
        String t = nomi[sel];
        g.drawString(t, 16, (float) (h / 2 + (fm.getAscent() - fm.getDescent()) / 2.0));
        // freccetta che si gira quando è aperto
        double cx = w - 18, cy = h / 2, k = aperto.get();
        g.translate(cx, cy);
        g.rotate(Math.PI * k);
        Path2D.Double fr = new Path2D.Double();
        fr.moveTo(-4.5, -2);
        fr.lineTo(0, 2.5);
        fr.lineTo(4.5, -2);
        g.setColor(p.mut);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(fr);
        g.dispose();
    }

    /** L'elenco delle opzioni (come quello di Squoosh, ma nello stile del programma). */
    private final class Lista extends JComponent {
        private static final int RIGA = 50, PAD = 8;
        private int sopra = -1;
        private final Anim.Val ent = new Anim.Val(0, this::repaint);
        private final Anim.Val[] h = new Anim.Val[nomi.length];

        Lista() {
            for (int i = 0; i < h.length; i++) h[i] = new Anim.Val(0, this::repaint);
            setOpaque(false);
            ent.ease(Anim.OUT_CUBIC);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = indice(e.getY());
                    if (i == sopra) return;
                    if (sopra >= 0) h[sopra].to(0, 180);
                    sopra = i;
                    if (i >= 0) h[i].to(1, 100);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (sopra >= 0) h[sopra].to(0, 180);
                    sopra = -1;
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    int i = indice(e.getY());
                    if (i >= 0) {
                        chiudi();
                        seleziona(i, true, true);
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        void entra() {
            ent.jump(0);
            ent.to(1, 180);
        }

        private int indice(int y) {
            int i = (y - PAD) / RIGA;
            return y >= PAD && i >= 0 && i < nomi.length ? i : -1;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(Math.max(300, Scelta.this.getWidth()), PAD * 2 + RIGA * nomi.length);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            Graphics2D g = Gfx.g2(gr);
            Tema.Pal p = Tema.p;
            int w = getWidth(), hh = getHeight();
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, (float) Math.min(1, ent.get())));
            g.translate(0, (1 - ent.get()) * 8);
            for (int i = 6; i >= 1; i--) { // ombra
                g.setColor(new java.awt.Color(0, 0, 0, (int) ((1 - i / 7.0) * 18)));
                g.fill(Gfx.rr(3 - i * .4, 5 - i * .4, w - 6 + i * .8, hh - 8 + i * .8, 18 + i));
            }
            java.awt.Color base = p.scuro ? Gfx.mix(p.grad[1], java.awt.Color.WHITE, .07) : Gfx.mix(p.grad[0], java.awt.Color.WHITE, .7);
            base = new java.awt.Color(base.getRed(), base.getGreen(), base.getBlue());
            g.setColor(base);
            g.fill(Gfx.rr(3, 3, w - 6, hh - 8, 18));
            g.setColor(p.btnBordo);
            g.setStroke(new BasicStroke(1f));
            g.draw(Gfx.rr(3.5, 3.5, w - 7, hh - 9, 18));
            for (int i = 0; i < nomi.length; i++) {
                double y = PAD + i * RIGA;
                double hv = h[i].get();
                boolean on = i == sel;
                if (hv > .01 || on) {
                    g.setColor(Gfx.a(on ? p.acc1 : p.fg, (int) (on ? 30 + 20 * hv : 22 * hv)));
                    g.fill(Gfx.rr(10, y + 2, w - 20, RIGA - 4, 12));
                }
                g.setFont(Tema.fb(14f));
                g.setColor(p.fg);
                g.drawString(nomi[i], 24, (float) (y + (note != null && note[i] != null ? 22 : 30)));
                if (note != null && note[i] != null) {
                    g.setFont(Tema.f(11.5f));
                    g.setColor(p.mut);
                    g.drawString(note[i], 24, (float) (y + 39));
                }
                double cx = w - 30, cy = y + RIGA / 2.0;
                g.setStroke(new BasicStroke(1.8f));
                g.setColor(on ? p.acc1 : Gfx.a(p.mut, 150));
                g.draw(new Ellipse2D.Double(cx - 8, cy - 8, 16, 16));
                if (on) {
                    g.setPaint(new java.awt.GradientPaint((float) (cx - 5), 0, p.acc1, (float) (cx + 5), 0, p.acc2));
                    g.fill(new Ellipse2D.Double(cx - 4.5, cy - 4.5, 9, 9));
                }
            }
            g.dispose();
        }
    }
}
