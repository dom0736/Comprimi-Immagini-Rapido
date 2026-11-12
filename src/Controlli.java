import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Pulsante a pillola: vetro oppure sfumato blu → magenta, con hover e "clic" animati. */
class Bott extends JComponent {
    String testo, icona;
    boolean primario, toggle;
    Runnable azione;
    double alt = 36, fs = 13;
    private boolean on, tastiera;
    final Anim.Val hov = new Anim.Val(0, this::repaint);
    final Anim.Val prem = new Anim.Val(0, this::repaint);
    final Anim.Val onv = new Anim.Val(0, this::repaint);

    Bott(String testo, String icona, boolean primario, Runnable azione) {
        this.testo = testo;
        this.icona = icona;
        this.primario = primario;
        this.azione = azione;
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (isEnabled()) hov.to(1, 140);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hov.to(0, 240);
                prem.to(0, 160);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                tastiera = false;
                prem.to(1, 80);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                prem.to(0, 200);
                if (isEnabled() && SwingUtilities.isLeftMouseButton(e) && contains(e.getPoint())) scatta();
            }
        };
        addMouseListener(m);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) {
                    tastiera = true;
                    prem.to(1, 70);
                    repaint();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if ((e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) && isEnabled()) {
                    prem.to(0, 160);
                    scatta();
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }

    Bott(String testo, String icona, Runnable azione) {
        this(testo, icona, false, azione);
    }

    private void scatta() {
        if (toggle) setOn(!on, true);
        if (azione != null) azione.run();
    }

    boolean isOn() {
        return on;
    }

    void setOn(boolean v, boolean anim) {
        on = v;
        onv.to(v ? 1 : 0, anim ? 220 : 0);
        repaint();
    }

    void setTesto(String t) {
        testo = t;
        revalidate();
        repaint();
    }

    @Override
    public void setEnabled(boolean e) {
        super.setEnabled(e);
        setCursor(Cursor.getPredefinedCursor(e ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Tema.fb((float) fs));
        int w = 0;
        if (testo != null && !testo.isEmpty()) w += fm.stringWidth(testo);
        if (icona != null) w += (int) (fs + 4) + (w > 0 ? 8 : 0);
        w += testo == null || testo.isEmpty() ? (int) (alt - fs - 4) : (int) (alt * 0.8);
        return new Dimension(Math.max(w, (int) alt), (int) alt);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        double w = getWidth(), h = getHeight();
        double sc = 1 - .035 * prem.get();
        g.translate(w / 2, h / 2);
        g.scale(sc, sc);
        g.translate(-w / 2, -h / 2);
        if (!isEnabled()) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .4f));
        Tema.Pal p = Tema.p;
        double r = h / 2;
        double hv = hov.get();
        boolean accento = primario || toggle;
        double qAcc = primario ? 1 : onv.get();
        if (primario) { // alone colorato sotto il pulsante
            for (int i = 7; i >= 1; i--) {
                g.setColor(Gfx.a(p.acc2, (int) ((1 - i / 8.0) * (1 - i / 8.0) * 46 * (0.6 + .4 * hv))));
                g.fill(Gfx.rr(-i + 1, -i + 5, w + 2 * i - 2, h + 2 * i - 4, r + i));
            }
        }
        if (!primario) {
            g.setColor(Gfx.mix(p.btn, p.btnHover, hv));
            g.fill(Gfx.rr(0, 0, w, h, r));
            g.setColor(p.btnBordo);
            g.setStroke(new BasicStroke(1f));
            g.draw(Gfx.rr(.5, .5, w - 1, h - 1, r));
        }
        if (accento && qAcc > 0.01) {
            java.awt.Composite old = g.getComposite();
            if (!primario) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (qAcc * (isEnabled() ? 1 : .4))));
            Gfx.pillolaAcc(g, 0, 0, w, h, r, .18 * hv);
            g.setComposite(old);
        }
        java.awt.Color fg = Gfx.mix(p.fg, p.accFg, accento ? qAcc : 0);
        Font f = Tema.fb((float) fs);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        boolean tieneTesto = testo != null && !testo.isEmpty();
        double ic = icona != null ? fs + 4 : 0;
        double tw = tieneTesto ? fm.stringWidth(testo) : 0;
        double tot = ic + (ic > 0 && tw > 0 ? 8 : 0) + tw;
        double x = (w - tot) / 2;
        if (icona != null) {
            Gfx.glifo(g, icona, x + ic / 2, h / 2, ic * .92, fg);
            x += ic + (tw > 0 ? 8 : 0);
        }
        if (tieneTesto) {
            g.setColor(fg);
            g.drawString(testo, (float) x, (float) (h / 2 + (fm.getAscent() - fm.getDescent()) / 2.0));
        }
        if (isFocusOwner() && tastiera) {
            g.setColor(Gfx.a(p.acc1, 220));
            g.setStroke(new BasicStroke(2f));
            g.draw(Gfx.rr(-2, -2, w + 3, h + 3, r + 2));
        }
        g.dispose();
    }
}

/** Scelta fra poche voci, con l'indicatore che scivola da una all'altra. sel = -1: nessuna voce attiva. */
class Seg extends JComponent {
    final String[] opc;
    private int sel;
    private final Anim.Val pos = new Anim.Val(0, this::repaint);
    private final Anim.Val vis = new Anim.Val(1, this::repaint);
    private final Anim.Val[] hov;
    private int sopra = -1;
    IntConsumer onSel;
    double alt = 36, segW;
    float fs = 13f;

    Seg(String[] opc, int sel, double segW, IntConsumer onSel) {
        this.opc = opc;
        this.sel = sel;
        this.segW = segW;
        this.onSel = onSel;
        hov = new Anim.Val[opc.length];
        for (int i = 0; i < opc.length; i++) hov[i] = new Anim.Val(0, this::repaint);
        pos.jump(Math.max(0, sel));
        vis.jump(sel >= 0 ? 1 : 0);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                aggiornaSopra(indice(e.getX()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                aggiornaSopra(-1);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = indice(e.getX());
                if (i >= 0 && isEnabled()) seleziona(i, true, true);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    private double segLarg() {
        if (segW > 0) return segW;
        FontMetrics fm = getFontMetrics(Tema.fb(fs));
        double mx = 0;
        for (String s : opc) mx = Math.max(mx, fm.stringWidth(s));
        return mx + 34;
    }

    private int indice(int x) {
        double sw = segLarg();
        int i = (int) Math.floor((x - 3) / sw);
        return i >= 0 && i < opc.length ? i : -1;
    }

    private void aggiornaSopra(int i) {
        if (i == sopra) return;
        if (sopra >= 0) hov[sopra].to(0, 200);
        sopra = i;
        if (i >= 0) hov[i].to(1, 120);
    }

    int selezione() {
        return sel;
    }

    void seleziona(int i, boolean anim, boolean fire) {
        boolean cambia = i != sel;
        int prima = sel;
        sel = i;
        if (i < 0) {
            vis.to(0, anim ? 220 : 0);
        } else if (prima < 0 || !anim) {
            pos.jump(i);
            vis.to(1, anim ? 220 : 0);
        } else {
            pos.to(i, 280);
        }
        repaint();
        if (fire && cambia && onSel != null && i >= 0) onSel.accept(i);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension((int) Math.ceil(segLarg() * opc.length + 6), (int) alt);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        double w = getWidth(), h = getHeight(), sw = segLarg();
        g.setColor(p.btn);
        g.fill(Gfx.rr(0, 0, w, h, h / 2));
        g.setColor(p.btnBordo);
        g.setStroke(new BasicStroke(1f));
        g.draw(Gfx.rr(.5, .5, w - 1, h - 1, h / 2));
        double v = vis.get();
        if (v > 0.01) {
            java.awt.Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) v));
            Gfx.pillolaAcc(g, 3 + pos.get() * sw, 3, sw, h - 6, (h - 6) / 2, 0);
            g.setComposite(old);
        }
        for (int i = 0; i < opc.length; i++) {
            double sob = Math.max(0, 1 - Math.abs(pos.get() - i)) * v;
            java.awt.Color base = Gfx.mix(p.mut, p.fg, hov[i].get());
            java.awt.Color c = Gfx.mix(base, p.accFg, sob);
            Gfx.testoCentro(g, opc[i], sob > .5 ? Tema.fb(fs) : Tema.f(fs), c, 3 + i * sw + sw / 2, h / 2);
        }
        g.dispose();
    }
}

/** Cursore a trascinamento (qualità, dimensione): la manopola scorre dolcemente anche quando cambia da sola. */
class Cursore extends JComponent {
    final String titolo;
    final int min, max, passo;
    private int valore;
    private final Anim.Val mostrato = new Anim.Val(0, this::repaint);
    private final Anim.Val man = new Anim.Val(0, this::repaint);
    private boolean trascina;
    IntConsumer onCambio;
    IntFunction<String> formato = v -> "" + v;
    String suffissoDesc = "";

    Cursore(String titolo, int min, int max, int passo, int valore, IntConsumer onCambio) {
        this.titolo = titolo;
        this.min = min;
        this.max = max;
        this.passo = passo;
        this.valore = valore;
        this.onCambio = onCambio;
        mostrato.jump(valore);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!trascina) man.to(.55, 140);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!trascina) man.to(0, 220);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                trascina = true;
                man.to(1, 100);
                requestFocusInWindow();
                setValore(daX(e.getX()), true, true);
                if (!Anim.attive) mostrato.jump(grezzo(e.getX()));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (trascina) {
                    // il pallino segue il mouse al pixel; il valore invece resta "a scatti" (passo)
                    setValore(daX(e.getX()), false, true);
                    mostrato.jump(grezzo(e.getX()));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                trascina = false;
                mostrato.to(valore, 140);
                man.to(contains(e.getPoint()) ? .55 : 0, 200);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (isEnabled()) setValore(valore - e.getWheelRotation() * passo, true, true);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int d;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT: d = -passo; break;
                    case KeyEvent.VK_RIGHT: d = passo; break;
                    case KeyEvent.VK_HOME: setValore(min, true, true); e.consume(); return;
                    case KeyEvent.VK_END: setValore(max, true, true); e.consume(); return;
                    default: return;
                }
                e.consume();
                setValore(valore + d, true, true);
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }

    @Override
    public void setEnabled(boolean e) {
        super.setEnabled(e);
        setFocusable(e);
        setCursor(java.awt.Cursor.getPredefinedCursor(e ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
        repaint();
    }

    int valore() {
        return valore;
    }

    void setValore(int v, boolean anim, boolean fire) {
        v = Math.max(min, Math.min(max, v));
        v = min + Math.round((v - min) / (float) passo) * passo;
        v = Math.min(max, v);
        boolean cambia = v != valore;
        valore = v;
        mostrato.to(v, anim ? 260 : 0);
        repaint();
        if (fire && cambia && onCambio != null) onCambio.accept(v);
    }

    private static final int PAD = 11;

    private double grezzo(int x) {
        double r = (x - PAD) / (double) Math.max(1, getWidth() - 2 * PAD);
        return min + Math.max(0, Math.min(1, r)) * (max - min);
    }

    private int daX(int x) {
        double r = (x - PAD) / (double) Math.max(1, getWidth() - 2 * PAD);
        return (int) Math.round(min + Math.max(0, Math.min(1, r)) * (max - min));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, 52);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        double w = getWidth();
        if (!isEnabled()) g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, .38f));
        g.setFont(Tema.f(12f));
        g.setColor(p.mut);
        g.drawString(titolo, PAD - 1, 13);
        String vs = formato.apply(trascina ? valore : (int) Math.round(mostrato.get()));
        g.setFont(Tema.fb(13f));
        g.setColor(p.fg);
        g.drawString(vs, (float) (w - PAD + 1 - g.getFontMetrics().stringWidth(vs)), 13);

        double ty = 36, th = 6, x0 = PAD, x1 = w - PAD;
        double t = (mostrato.get() - min) / (double) (max - min);
        double kx = x0 + (x1 - x0) * t;
        g.setColor(isEnabled() ? p.btn : Gfx.mulA(p.btn, .5));
        g.fill(Gfx.rr(x0, ty - th / 2, x1 - x0, th, th / 2));
        g.setColor(Gfx.a(p.btnBordo, 40));
        g.setStroke(new BasicStroke(1f));
        g.draw(Gfx.rr(x0 + .5, ty - th / 2 + .5, x1 - x0 - 1, th - 1, th / 2));
        if (kx - x0 > 1) {
            g.setPaint(new GradientPaint((float) x0, 0, p.acc1, (float) x1, 0, p.acc2));
            g.fill(Gfx.rr(x0, ty - th / 2, kx - x0, th, th / 2));
        }
        double k = man.get();
        double r = 8.5 + 2.5 * k;
        for (int i = 6; i >= 1; i--) { // ombra morbida
            g.setColor(new java.awt.Color(0, 0, 0, (int) ((1 - i / 7.0) * 26)));
            g.fill(new Ellipse2D.Double(kx - r - i * .7, ty - r - i * .7 + 2, 2 * r + i * 1.4, 2 * r + i * 1.4));
        }
        g.setColor(java.awt.Color.WHITE);
        g.fill(new Ellipse2D.Double(kx - r, ty - r, 2 * r, 2 * r));
        g.setPaint(new GradientPaint((float) (kx - r), 0, p.acc1, (float) (kx + r), 0, p.acc2));
        g.setStroke(new BasicStroke((float) (2.2 + k * .6)));
        g.draw(new Ellipse2D.Double(kx - r + 1.1, ty - r + 1.1, 2 * r - 2.2, 2 * r - 2.2));
        if (isFocusOwner()) {
            g.setColor(Gfx.a(p.acc1, 120));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(kx - r - 3.5, ty - r - 3.5, 2 * r + 7, 2 * r + 7));
        }
        g.dispose();
    }
}

/** Riga con testo e interruttore a scorrimento. */
class Interruttore extends JComponent {
    final String titolo, desc;
    private boolean on;
    private final Anim.Val v = new Anim.Val(0, this::repaint);
    private final Anim.Val hov = new Anim.Val(0, this::repaint);
    java.util.function.Consumer<Boolean> onCambio;

    Interruttore(String titolo, String desc, boolean on, java.util.function.Consumer<Boolean> onCambio) {
        this.titolo = titolo;
        this.desc = desc;
        this.on = on;
        this.onCambio = onCambio;
        v.jump(on ? 1 : 0);
        v.ease(Anim.OUT_BACK);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hov.to(1, 140);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hov.to(0, 200);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                inverti();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) inverti();
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }

    private void inverti() {
        on = !on;
        v.to(on ? 1 : 0, 300);
        if (onCambio != null) onCambio.accept(on);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(300, desc == null ? 40 : 54);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        double w = getWidth(), h = getHeight();
        g.setColor(Gfx.mulA(p.btn, .4 + .6 * hov.get()));
        g.fill(Gfx.rr(0, 0, w, h, 12));
        g.setFont(Tema.fb(13f));
        g.setColor(p.fg);
        FontMetrics fm = g.getFontMetrics();
        double ty = desc == null ? h / 2 + (fm.getAscent() - fm.getDescent()) / 2.0 : 22;
        g.drawString(titolo, 14, (float) ty);
        if (desc != null) {
            g.setFont(Tema.f(11.5f));
            g.setColor(p.mut);
            g.drawString(Gfx.taglia(desc, g.getFontMetrics(), (int) w - 100), 14, 40);
        }
        double tw = 42, th = 24, tx = w - tw - 14, tyy = (h - th) / 2;
        double q = Math.max(0, Math.min(1, v.get()));
        g.setColor(Gfx.mix(p.campo, p.campo, 0));
        g.fill(Gfx.rr(tx, tyy, tw, th, th / 2));
        g.setColor(p.btnBordo);
        g.setStroke(new BasicStroke(1f));
        g.draw(Gfx.rr(tx + .5, tyy + .5, tw - 1, th - 1, th / 2));
        if (q > 0.01) {
            java.awt.Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) q));
            Gfx.pillolaAcc(g, tx, tyy, tw, th, th / 2, 0);
            g.setComposite(old);
        }
        double kr = 9, kx = tx + 3 + kr + (tw - 6 - 2 * kr) * v.get();
        g.setColor(new java.awt.Color(0, 0, 0, 40));
        g.fill(new Ellipse2D.Double(kx - kr, tyy + th / 2 - kr + 1.5, 2 * kr, 2 * kr));
        g.setColor(java.awt.Color.WHITE);
        g.fill(new Ellipse2D.Double(kx - kr, tyy + th / 2 - kr, 2 * kr, 2 * kr));
        if (isFocusOwner()) {
            g.setColor(Gfx.a(p.acc1, 200));
            g.setStroke(new BasicStroke(2f));
            g.draw(Gfx.rr(tx - 2, tyy - 2, tw + 4, th + 4, th / 2 + 2));
        }
        g.dispose();
    }
}
