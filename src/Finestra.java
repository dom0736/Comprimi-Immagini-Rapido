import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/** Finestra principale: elenco a sinistra, anteprima prima/dopo e controlli a destra. */
final class Finestra extends JFrame {
    static final String[] PRESET_NOMI = {"Minima", "Leggera", "Normale", "Nitida"};
    static final int[][] PRESET = {{50, 30}, {65, 50}, {75, 75}, {90, 100}}; // qualità, dimensione %

    // ---- stato
    final List<Voce> voci = new ArrayList<>();
    private final Set<Path> percorsi = new HashSet<>();
    private Voce cur;
    private BufferedImage origImg, vistaFull;
    private int seqCarica, seqAnteprima;
    private boolean inElaborazione, annullaElab;
    private Path ultimaUscitaDir;

    private final ExecutorService caricaEx = daemon(1, "carica");
    private final ExecutorService anteprimaEx = daemon(1, "anteprima");
    private final ExecutorService miniEx = daemon(2, "miniature");

    // ---- interfaccia
    final Stage stage = new Stage();
    final Overlay overlay = new Overlay();
    private final Intestazione intestazione;
    private final CodaCard coda;
    private final Velo confrontoWrap = new Velo();
    private final Confronto confronto = new Confronto();
    private final ControlliCard controlli;
    private final Piede piede = new Piede();
    private final ListaVoci lista = new ListaVoci();
    private final ZonaDrop zona;
    private final Timer deb;
    private boolean pieno;
    private Rectangle prima;
    private final Anim.Val foc = new Anim.Val(0, () -> {
        posiziona();
        stage.repaint();
    });

    private static ExecutorService daemon(int n, String nome) {
        return Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, nome);
            t.setDaemon(true);
            return t;
        });
    }

    Finestra() {
        super(Cfg.APP);
        Tema.imposta(Cfg.str("tema", "scuro"));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                chiudiApp();
            }
        });
        setIconImages(icone());

        zona = new ZonaDrop(this::scegliFile, this::scegliCartella);
        intestazione = new Intestazione();
        coda = new CodaCard();
        controlli = new ControlliCard();
        aggiornaQualita();
        deb = new Timer(220, e -> {
            salvaScelte();
            anteprima();
        });
        deb.setRepeats(false);

        confrontoWrap.setLayout(new java.awt.BorderLayout());
        confrontoWrap.add(confronto);
        confronto.onCrop = r -> {
            if (cur == null) return;
            cur.crop = r;
            confronto.cambiatoCrop(r);
            deb.restart();
        };
        confronto.onReset = () -> {
            if (cur == null) return;
            cur.crop = null;
            confronto.cambiatoCrop(null);
            deb.restart();
        };
        confronto.vuoto();

        lista.onSeleziona = this::seleziona;
        lista.onRimuovi = this::rimuovi;

        stage.add(intestazione);
        stage.add(coda);
        stage.add(confrontoWrap);
        stage.add(controlli);
        stage.add(piede);
        stage.posiziona = this::posiziona;
        setContentPane(stage);
        setGlassPane(overlay);

        installaScorciatoie();
        installaDrop();

        Dimension schermo = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.min(1120, schermo.width - 40), h = Math.min(740, schermo.height - 80);
        setMinimumSize(new Dimension(Math.min(980, w), Math.min(660, h)));
        setSize(w, h);
        setLocationRelativeTo(null);
        mostraVista();
        piede.msg("Trascina qui le tue immagini per iniziare", null);
    }

    private List<java.awt.Image> icone() {
        List<java.awt.Image> l = new ArrayList<>();
        try (java.io.InputStream in = Finestra.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                BufferedImage b = ImageIO.read(in);
                if (b != null) {
                    for (int s : new int[]{256, 64, 32, 16}) l.add(Gfx.scala(b, s, s));
                    return l;
                }
            }
        } catch (Exception ignored) {
        }
        for (int s : new int[]{256, 64, 32, 16}) l.add(Gfx.icona(s));
        return l;
    }

    /** Fa partire le animazioni di entrata. */
    void avvia() {
        intestazione.entra(0);
        coda.entra(90);
        confrontoWrap.entra(170);
        controlli.entra(250);
        piede.entra(330);
    }

    void portaInPrimoPiano() {
        if ((getExtendedState() & ICONIFIED) != 0) setExtendedState(NORMAL);
        setVisible(true);
        toFront();
        setAlwaysOnTop(true);
        setAlwaysOnTop(false);
        requestFocus();
    }

    // ---------------------------------------------------------------- disposizione
    private void posiziona() {
        int W = stage.getWidth(), H = stage.getHeight();
        double f = foc.get();
        int m = (int) Math.round(24 - 10 * f), hh = (int) Math.round(76 - 14 * f), fh = (int) Math.round(44 - 8 * f);
        controlli.setCompatto(f > 0.5);
        intestazione.setBounds(0, 0, W, hh);
        int bodyH = H - hh - fh;
        int qw = Math.max(260, Math.min(320, (int) (W * 0.26)));
        int qe = (int) Math.round(qw * (1 - f));
        coda.setVisible(qe > 24);
        coda.setBounds(m, hh, Math.max(qe, 0), bodyH);
        int rx = m + (int) Math.round((qw + 16) * (1 - f)), rw = W - rx - m, ch = (int) Math.round(172 - 92 * f);
        int gp = (int) Math.round(14 - 6 * f);
        confrontoWrap.setBounds(rx, hh, rw, bodyH - ch - gp);
        controlli.setBounds(rx, hh + bodyH - ch, rw, ch);
        piede.setBounds(0, H - fh, W, fh);
        // setBounds su un pannello senza layout non fa ridisporre i figli: lo facciamo qui,
        // altrimenti anteprima e controlli restano con le misure vecchie finché la finestra non si ridisegna (es. alt+tab)
        for (java.awt.Component c : stage.getComponents()) c.validate();
    }

    /** Mostra solo l'immagine: l'elenco si richiude e l'anteprima occupa tutta la larghezza. */
    void soloImmagine() {
        if (voci.isEmpty()) return;
        voglioSolo = !voglioSolo;
        intestazione.solo.setOn(voglioSolo, true);
        aggiornaFocus();
        if (voglioSolo && !voci.isEmpty()) msg("Elenco nascosto: ↑ ↓ cambia immagine, F lo rimostra", null);
    }

    private boolean voglioSolo, soloPrimaDiPieno;

    private void aggiornaFocus() {
        foc.to(voglioSolo && !voci.isEmpty() ? 1 : 0, 420);
    }

    /** Schermo intero (F11). Esc per uscire. */
    void schermoIntero() {
        try {
            java.awt.GraphicsDevice d = getGraphicsConfiguration().getDevice();
            boolean nuovo = !pieno;
            if (nuovo) {
                prima = getBounds();
                soloPrimaDiPieno = voglioSolo;
                voglioSolo = true;
            } else {
                voglioSolo = soloPrimaDiPieno;
            }
            intestazione.solo.setOn(voglioSolo, true);
            aggiornaFocus();
            // Niente schermo intero "esclusivo" (setFullScreenWindow): su Windows nasconde le finestre di
            // dialogo native (Esplora file) dietro la finestra. Usiamo invece una finestra senza bordi grande
            // come lo schermo: i dialoghi restano sopra e si copre comunque la barra delle applicazioni.
            java.awt.Rectangle schermo = d.getDefaultConfiguration().getBounds();
            if (d.getFullScreenWindow() == this) d.setFullScreenWindow(null);
            dispose();
            setUndecorated(nuovo);
            setExtendedState(NORMAL);
            if (nuovo) setBounds(schermo);
            else if (prima != null) setBounds(prima);
            setVisible(true);
            pieno = nuovo;
            intestazione.schermo.setOn(nuovo, true);
            TitoloScuro.applica(this);
            stage.revalidate();
            stage.repaint();
        } catch (RuntimeException e) {
            Cfg.err("schermo intero", e);
            msg("Lo schermo intero non è disponibile qui", false);
        }
    }

    private void mostraVista() {
        coda.mostra(voci.isEmpty());
        aggiornaSolo();
        aggiornaFocus();
    }

    /** Il tasto «solo immagine» ha senso solo se c'è almeno un'immagine: altrimenti sparisce. */
    private void aggiornaSolo() {
        boolean ci = !voci.isEmpty();
        if (!ci && !pieno && voglioSolo) {
            voglioSolo = false;
            intestazione.solo.setOn(false, false);
        }
        if (intestazione.solo.isVisible() != ci) {
            intestazione.solo.setVisible(ci);
            intestazione.repaint();
        }
    }

    // ---------------------------------------------------------------- stato in basso
    void msg(String t, Boolean ok) {
        piede.msg(t, ok);
    }

    // ---------------------------------------------------------------- intestazione
    private final class Intestazione extends Velo {
        final Bott guida = new Bott("Guida", "book", () -> overlay.mostra(new GuidaScheda(), 700, 640, true));
        final Bott impost = new Bott("Impostazioni", "gear", () -> overlay.mostra(new ImpostazioniScheda(Finestra.this), 580, 620, true));
        final Bott solo = new Bott("", "panel", () -> soloImmagine());
        final Bott schermo = new Bott("", "expand", () -> schermoIntero());
        private final Anim.Val logo = new Anim.Val(0, this::repaint);

        Intestazione() {
            setLayout(null);
            dy = -10;
            guida.alt = 38;
            impost.alt = 38;
            for (Bott b : new Bott[]{solo, schermo}) {
                b.toggle = true;
                b.alt = 38;
                add(b);
            }
            solo.setToolTipText("Mostra solo l'immagine (F)");
            solo.setVisible(false);
            schermo.setToolTipText("Schermo intero (F11)");
            add(guida);
            add(impost);
            logo.ease(Anim.OUT_BACK);
        }

        @Override
        void entra(int r) {
            super.entra(r);
            if (!Anim.attive) {
                logo.jump(1);
                return;
            }
            logo.jump(0);
            Anim.dopo(r + 120, () -> logo.to(1, 700));
        }

        @Override
        public void doLayout() {
            int w = getWidth(), m = (int) Math.round(24 - 10 * foc.get());
            int a = impost.getPreferredSize().width, b = guida.getPreferredSize().width;
            impost.setBounds(w - m - a, 19, a, 38);
            guida.setBounds(w - m - a - 10 - b, 19, b, 38);
            schermo.setBounds(w - m - a - 10 - b - 18 - 38, 19, 38, 38);
            solo.setBounds(w - m - a - 10 - b - 18 - 38 - 8 - 38, 19, 38, 38);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            Graphics2D g = Gfx.g2(gr);
            Tema.Pal p = Tema.p;
            double s = Math.max(0.01, logo.get());
            double cx = 24 + 22, cy = 38;
            g.translate(cx, cy);
            g.scale(s, s);
            g.rotate((1 - Math.min(1, s)) * -0.5);
            g.drawImage(logoImg(), -22, -22, 44, 44, null);
            g.dispose();
            g = Gfx.g2(gr);
            g.setFont(Tema.fb(20f));
            g.setColor(p.fg);
            g.drawString(Cfg.APP, 24 + 44 + 14, 36);
            g.setFont(Tema.f(12f));
            g.setColor(p.mut);
            g.drawString("Meno peso, stessa foto", 24 + 44 + 14, 56);
            g.dispose();
        }
    }

    private BufferedImage logoCache;

    BufferedImage logoImg() {
        if (logoCache == null) {
            List<java.awt.Image> l = icone();
            java.awt.Image i = l.get(0);
            logoCache = Gfx.icona(176);
            if (i instanceof BufferedImage) logoCache = Gfx.scala((BufferedImage) i, 128, 128);
        }
        return logoCache;
    }

    // ---------------------------------------------------------------- scheda "Immagini"
    private final class CodaCard extends Velo {
        final Bott aggiungi = new Bott("Aggiungi", "plus", this::scegli);
        final Bott svuota = new Bott("Svuota", "x", () -> svuotaElenco());
        final Velo corpo = new Velo();
        final CardLayout cl = new CardLayout();
        private final Anim.Val num = new Anim.Val(0, this::repaint);

        CodaCard() {
            setLayout(null);
            aggiungi.alt = 32;
            aggiungi.fs = 12;
            svuota.alt = 30;
            svuota.fs = 12;
            corpo.setLayout(cl);
            corpo.dy = 8;
            corpo.add(zona, "z");
            corpo.add(lista, "l");
            add(aggiungi);
            add(svuota);
            add(corpo);
        }

        private void scegli() {
            scegliFile();
        }

        void mostra(boolean vuota) {
            cl.show(corpo, vuota ? "z" : "l");
            corpo.entra(0);
            aggiungi.setVisible(!vuota);
            svuota.setVisible(!vuota);
            num.to(voci.size(), 300);
            doLayout();
            repaint();
        }

        void contaCambiata() {
            num.to(voci.size(), 300);
        }

        @Override
        public void doLayout() {
            int w = getWidth(), h = getHeight();
            int aw = aggiungi.getPreferredSize().width;
            aggiungi.setBounds(w - 16 - aw, 12, aw, 32);
            boolean vuota = voci.isEmpty();
            int piede = vuota ? 12 : 50;
            corpo.setBounds(12, 56, w - 24, h - 56 - piede);
            int sw = svuota.getPreferredSize().width;
            svuota.setBounds(16, h - 40, sw, 30);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            Graphics2D g = Gfx.g2(gr);
            Tema.Pal p = Tema.p;
            Gfx.card(g, 0, 0, getWidth(), getHeight(), 20);
            g.setFont(Tema.fb(15f));
            g.setColor(p.fg);
            g.drawString("Immagini", 20, 34);
            int tw = g.getFontMetrics().stringWidth("Immagini");
            int n = (int) Math.round(num.get());
            if (n > 0) {
                String t = "" + n;
                g.setFont(Tema.fb(11.5f));
                int bw = g.getFontMetrics().stringWidth(t) + 16;
                Gfx.pillolaAcc(g, 20 + tw + 10, 18, bw, 22, 11, 0);
                g.setColor(Color.WHITE);
                g.drawString(t, 20 + tw + 18, 33);
            }
            if (!voci.isEmpty()) {
                long tot = 0;
                for (Voce v : voci) tot += v.orig;
                g.setFont(Tema.f(12f));
                g.setColor(p.mut);
                String s = "Totale " + Motore.peso(tot);
                g.drawString(s, getWidth() - 18 - g.getFontMetrics().stringWidth(s), getHeight() - 20);
            }
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- controlli
    private final class ControlliCard extends Velo {
        final Seg preset;
        final Scelta formato;
        final Cursore qualita, dimensione;
        final Bott salva, copiaFile, copiaImg, tutto;

        ControlliCard() {
            setLayout(null);
            int q = Cfg.intero("qualita", 75, 0, 100), s = Cfg.intero("scala", 75, 0, 100);
            preset = new Seg(PRESET_NOMI, presetDi(q, s), 0, i -> applicaPreset(i));
            formato = new Scelta(Motore.FORMATI, Motore.note(), Cfg.intero("formato", 0, 0, Motore.FORMATI.length - 1), i -> cambioFormato());
            qualita = new Cursore("Qualità", 0, 100, 1, q, v -> cambioManuale());
            qualita.formato = v -> "" + v;
            dimensione = new Cursore("Dimensione", 0, 100, 5, s, v -> cambioManuale());
            dimensione.formato = v -> v + "%";
            salva = new Bott("Salva", "save", () -> salvaCorrente(0));
            copiaFile = new Bott("Copia file", "copy", () -> salvaCorrente(1));
            copiaImg = new Bott("Copia immagine", "image", () -> salvaCorrente(2));
            tutto = new Bott("Comprimi tutto", "play", true, () -> comprimiTutto());
            for (Bott b : new Bott[]{salva, copiaFile, copiaImg}) {
                b.alt = 36;
                b.fs = 12;
            }
            tutto.alt = 44;
            tutto.fs = 14;
            for (JComponent c : new JComponent[]{preset, formato, qualita, dimensione, salva, copiaFile, copiaImg, tutto}) add(c);
            abilita(false);
        }

        void abilita(boolean v) {
            salva.setEnabled(v);
            copiaFile.setEnabled(v);
            copiaImg.setEnabled(v);
        }

        private boolean compatto;

        void setCompatto(boolean c) {
            if (c == compatto) return;
            compatto = c;
            salva.setTesto(c ? "" : "Salva");
            copiaFile.setTesto(c ? "" : "Copia file");
            copiaImg.setTesto(c ? "" : "Copia immagine");
            salva.setToolTipText(c ? "Salva (Ctrl+S)" : null);
            copiaFile.setToolTipText(c ? "Copia file" : null);
            copiaImg.setToolTipText(c ? "Copia immagine" : null);
            vis.jump(.3);
            vis.to(1, 260);
            revalidate();
        }

        private void layoutCompatto() {
            int w = getWidth(), h = getHeight(), m = 18;
            int y = (h - 36) / 2;
            int tw = 140;
            tutto.setBounds(w - m - tw, (h - 44) / 2, tw, 44);
            int x = w - m - tw - 10 - 36;
            for (Bott b : new Bott[]{salva, copiaFile, copiaImg}) {
                b.setBounds(x, y, 36, 36);
                x -= 40;
            }
            int to = x + 40 - 8;
            formato.larghezza = 118;
            int fw = formato.getPreferredSize().width;
            preset.segW = 58;
            int pw = preset.getPreferredSize().width;
            // prima i cursori, poi il formato; i preset solo se resta posto
            boolean conPreset = to - (m + fw + 14 + pw + 14) >= 2 * 130 + 16;
            preset.setVisible(conPreset);
            int px = m;
            if (conPreset) {
                preset.setBounds(px, y, pw, 36);
                px += pw + 12;
            }
            formato.setBounds(px, y, fw, 36);
            int from = px + fw + 14;
            int cw = Math.max(80, (to - from - 16) / 2);
            qualita.setBounds(from, (h - 52) / 2, cw, 52);
            dimensione.setBounds(from + cw + 16, (h - 52) / 2, cw, 52);
        }

        @Override
        public void doLayout() {
            if (compatto) {
                layoutCompatto();
                return;
            }
            preset.setVisible(true);
            int w = getWidth(), h = getHeight(), m = 22;
            int sw = Math.max(76, Math.min(110, (w - 2 * m - 190) / 4));
            preset.segW = sw;
            preset.setBounds(m, 14, preset.getPreferredSize().width, 36);
            formato.larghezza = 168;
            int fw = formato.getPreferredSize().width;
            formato.setBounds(w - m - fw, 14, fw, 36);
            int gap = 28, cw = (w - 2 * m - gap) / 2;
            qualita.setBounds(m, 58, cw, 52);
            dimensione.setBounds(m + cw + gap, 58, cw, 52);
            int y = h - 14 - 44;
            int x = m;
            for (Bott b : new Bott[]{salva, copiaFile, copiaImg}) {
                int bw = b.getPreferredSize().width;
                b.setBounds(x, y + 4, bw, 36);
                x += bw + 8;
            }
            int tw = Math.max(176, tutto.getPreferredSize().width);
            tutto.setBounds(w - m - tw, y, tw, 44);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            Graphics2D g = Gfx.g2(gr);
            Tema.Pal p = Tema.p;
            Gfx.card(g, 0, 0, getWidth(), getHeight(), 20);
            int i = preset.selezione();
            String d = i >= 0 ? PRESET_DESC[i] : "Regolazione personalizzata";
            g.setFont(Tema.f(12f));
            FontMetrics fm = g.getFontMetrics();
            int x = preset.getX() + preset.getWidth() + 16;
            int fine = formato.getX() - 16;
            if (!compatto && fine - x > fm.stringWidth(d)) {
                g.setColor(p.mut);
                g.drawString(d, fine - fm.stringWidth(d), 37);
            }
            g.dispose();
        }
    }

    static final String[] PRESET_DESC = {"Chat e mail: file piccolissimi", "Siti e documenti", "Equilibrio consigliato", "Pesa meno, senza rimpicciolire"};

    static int presetDi(int q, int s) {
        for (int i = 0; i < PRESET.length; i++) if (PRESET[i][0] == q && PRESET[i][1] == s) return i;
        return -1;
    }

    private void applicaPreset(int i) {
        controlli.qualita.setValore(PRESET[i][0], true, false);
        controlli.dimensione.setValore(PRESET[i][1], true, false);
        deb.restart();
    }

    private void cambioManuale() {
        int i = presetDi(controlli.qualita.valore(), controlli.dimensione.valore());
        if (i != controlli.preset.selezione()) controlli.preset.seleziona(i, true, false);
        deb.restart();
    }

    private void cambioFormato() {
        aggiornaQualita();
        deb.restart();
    }

    /** Il cursore Qualità vale per JPEG (compressione) e PNG 256 (numero di colori); il PNG normale è senza perdita. */
    private void aggiornaQualita() {
        int f = formatoCorrente();
        controlli.qualita.setEnabled(f != 1);
        controlli.qualita.setToolTipText(f == 1 ? "Il PNG è senza perdita: la qualità è sempre massima. Scegli JPEG o PNG 256 per regolarla." : null);
        controlli.qualita.formato = f == 2 ? (v -> Motore.coloriDa(v) + " col.") : (v -> "" + v);
        controlli.qualita.repaint();
    }

    private int formatoCorrente() {
        return Math.max(0, controlli.formato.selezione());
    }

    private void salvaScelte() {
        Cfg.set("formato", formatoCorrente());
        Cfg.set("qualita", controlli.qualita.valore());
        Cfg.set("scala", controlli.dimensione.valore());
        Cfg.salva();
    }

    // ---------------------------------------------------------------- piede
    private final class Piede extends Velo {
        private String testo = "";
        private Boolean ok;
        private final Anim.Val flash = new Anim.Val(1, this::repaint);
        private final Anim.Val cuore = new Anim.Val(0, this::repaint);
        private final Timer battito;

        Piede() {
            dy = 8;
            flash.ease(Anim.OUT_CUBIC);
            battito = new Timer(3200, e -> {
                if (isShowing()) cuore.to(1, 150, () -> cuore.to(0, 420));
            });
            battito.start();
        }

        void msg(String t, Boolean ok) {
            testo = t;
            this.ok = ok;
            flash.jump(0);
            flash.to(1, 320);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            Graphics2D g = Gfx.g2(gr);
            Tema.Pal p = Tema.p;
            int w = getWidth(), h = getHeight();
            double f = flash.get();
            g.setFont(Tema.f(12.5f));
            FontMetrics fm = g.getFontMetrics();
            String cr = "Fatto con";
            String nome = "da Domenico Castaldo";
            int wc = fm.stringWidth(cr), wn = fm.stringWidth(nome);
            double cx = w - 24 - wn - 8 - 14;
            int maxMsg = (int) (cx - wc - 40 - 40);
            java.awt.Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) f));
            Color c = ok == null ? p.mut : ok ? p.ok : p.err;
            if (ok != null) {
                g.setColor(c);
                g.fill(new java.awt.geom.Ellipse2D.Double(26, h / 2.0 - 4 + (1 - f) * 6, 8, 8));
            }
            g.setColor(ok == null ? p.mut : c);
            g.drawString(Gfx.taglia(testo, fm, Math.max(80, maxMsg)), ok == null ? 28 : 42, (float) (h / 2.0 + 4.5 + (1 - f) * 6));
            g.setComposite(old);
            // «Fatto con ❤ da Domenico Castaldo»
            g.setColor(Gfx.mulA(p.mut, .9));
            double tx = w - 24 - wn - 22 - wc;
            g.drawString(cr, (float) tx, (float) (h / 2.0 + 4.5));
            double k = 1 + .28 * cuore.get();
            Gfx.cuore(g, tx + wc + 11, h / 2.0 + 0.5, 12 * k, p.rec);
            g.setColor(Gfx.mulA(p.mut, .9));
            g.drawString(nome, (float) (w - 24 - wn), (float) (h / 2.0 + 4.5));
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- sfondo
    static final class Stage extends JPanel {
        private BufferedImage bg;
        private String bgTema = "";
        final Anim.Val drag = new Anim.Val(0, this::repaint);
        Runnable posiziona;

        Stage() {
            super(null);
            setOpaque(true);
        }

        @Override
        public void doLayout() {
            if (posiziona != null) posiziona.run();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            String chiave = Tema.nome() + w + "x" + h;
            if (bg == null || !chiave.equals(bgTema)) {
                bg = Gfx.fondo(w, h);
                bgTema = chiave;
            }
            g.drawImage(bg, 0, 0, null);
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            double d = drag.get();
            if (d <= 0.01) return;
            Graphics2D g2 = Gfx.g2(g);
            Tema.Pal p = Tema.p;
            int w = getWidth(), h = getHeight();
            g2.setColor(Gfx.a(p.scuro ? new Color(10, 16, 30) : new Color(220, 228, 250), (int) (150 * d)));
            g2.fillRect(0, 0, w, h);
            double m = 18 + (1 - d) * 14;
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{12f, 10f}, (float) (Anim.ora() % 2000 / 2000.0 * 22)));
            g2.setPaint(new java.awt.GradientPaint(0, 0, Gfx.mulA(p.acc1, d), w, h, Gfx.mulA(p.acc2, d)));
            g2.draw(Gfx.rr(m, m, w - 2 * m, h - 2 * m, 26));
            double cy = h / 2.0;
            double r = 44;
            Gfx.pillolaAcc(g2, w / 2.0 - r, cy - 70 - r, 2 * r, 2 * r, r * .42, 0);
            Gfx.glifo(g2, "save", w / 2.0, cy - 70, 42, Color.WHITE);
            Gfx.testoCentro(g2, "Rilascia per aggiungere le immagini", Tema.fb(20f), p.fg, w / 2.0, cy + 10);
            Gfx.testoCentro(g2, "Anche una cartella intera", Tema.f(13f), p.mut, w / 2.0, cy + 38);
            g2.dispose();
        }
    }

    // ---------------------------------------------------------------- drag & drop e scorciatoie
    private void installaDrop() {
        new DropTarget(getRootPane(), DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            private boolean valido(DropTargetDragEvent e) {
                return !overlay.aperto() && e.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public void dragEnter(DropTargetDragEvent e) {
                if (valido(e)) {
                    e.acceptDrag(DnDConstants.ACTION_COPY);
                    stage.drag.to(1, 220);
                    zona.drag.to(1, 220);
                    Anim.ciclo(() -> {
                        stage.repaint();
                        return stage.drag.target() > 0.5 || stage.drag.get() > 0.02;
                    });
                } else {
                    e.rejectDrag();
                }
            }

            @Override
            public void dragOver(DropTargetDragEvent e) {
                if (valido(e)) e.acceptDrag(DnDConstants.ACTION_COPY);
                else e.rejectDrag();
            }

            @Override
            public void dragExit(DropTargetEvent e) {
                stage.drag.to(0, 220);
                zona.drag.to(0, 220);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void drop(DropTargetDropEvent e) {
                stage.drag.to(0, 220);
                zona.drag.to(0, 220);
                if (overlay.aperto() || !e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    e.rejectDrop();
                    return;
                }
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    e.dropComplete(true);
                    List<Path> l = new ArrayList<>();
                    for (File f : files) l.add(f.toPath());
                    aggiungiPercorsi(l);
                } catch (Exception ex) {
                    Cfg.err("drop", ex);
                    e.dropComplete(false);
                    msg("Non sono riuscito a leggere quello che hai trascinato", false);
                }
            }
        });
    }

    private void scorciatoia(String nome, KeyStroke k, Runnable r) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, nome);
        getRootPane().getActionMap().put(nome, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private boolean fuocoSuCursore() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof Cursore;
    }

    private long ultimoF11;

    /** F11 vale ovunque nell'app (anche con Guida, Impostazioni o menu aperti), qualunque cosa abbia il fuoco. */
    private void installaF11() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_F11 || e.getModifiersEx() != 0) return false;
            if (!(e.getSource() instanceof java.awt.Component)) return false;
            java.awt.Window w = e.getSource() instanceof java.awt.Window ? (java.awt.Window) e.getSource()
                    : SwingUtilities.getWindowAncestor((java.awt.Component) e.getSource());
            if (w != this && (w == null || w.getOwner() != this)) return false;
            long ora = System.currentTimeMillis();
            if (ora - ultimoF11 > 400) { // tenendo premuto il tasto non deve lampeggiare
                ultimoF11 = ora;
                schermoIntero();
            }
            return true;
        });
    }

    private void installaScorciatoie() {
        installaF11();
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        scorciatoia("aggiungi", KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl), () -> {
            if (!overlay.aperto()) scegliFile();
        });
        scorciatoia("incolla", KeyStroke.getKeyStroke(KeyEvent.VK_V, ctrl), () -> {
            if (!overlay.aperto()) incolla();
        });
        scorciatoia("salva", KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl), () -> {
            if (!overlay.aperto()) salvaCorrente(0);
        });
        scorciatoia("tutto", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, ctrl), () -> {
            if (!overlay.aperto()) comprimiTutto();
        });
        scorciatoia("canc", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), () -> {
            if (!overlay.aperto() && cur != null) rimuovi(cur);
        });
        scorciatoia("su", KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> naviga(-1));
        scorciatoia("giu", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> naviga(1));
        scorciatoia("esc", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> {
            if (controlli.formato.aperta()) controlli.formato.chiudi();
            else if (overlay.puoChiudersiConEsc()) overlay.chiudi();
            else if (!overlay.aperto() && confronto.inCropMode()) confronto.esciDaCrop();
            else if (!overlay.aperto() && pieno) schermoIntero();
        });
        scorciatoia("solo", KeyStroke.getKeyStroke(KeyEvent.VK_F, 0), () -> {
            if (!overlay.aperto()) soloImmagine(); // F vale anche con il fuoco su un cursore (le frecce no)
        });
    }

    private void naviga(int d) {
        if (overlay.aperto() || voci.isEmpty() || fuocoSuCursore()) return;
        int i = cur == null ? 0 : voci.indexOf(cur) + d;
        seleziona(voci.get(Math.max(0, Math.min(voci.size() - 1, i))));
    }

    // ---------------------------------------------------------------- aggiungere immagini
    private void scegliFile() {
        FileDialog fd = new FileDialog(this, "Scegli le immagini", FileDialog.LOAD);
        fd.setMultipleMode(true);
        fd.setFile("*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.tif;*.tiff");
        String ult = Cfg.str("ultima_cartella", "");
        if (!ult.isEmpty() && Files.isDirectory(Paths.get(ult))) fd.setDirectory(ult);
        fd.setVisible(true);
        File[] fs = fd.getFiles();
        if (fs == null || fs.length == 0) return;
        Cfg.set("ultima_cartella", fs[0].getParent());
        List<Path> l = new ArrayList<>();
        for (File f : fs) l.add(f.toPath());
        aggiungiPercorsi(l);
    }

    private void scegliCartella() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Scegli una cartella di immagini");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String ult = Cfg.str("ultima_cartella", "");
        if (!ult.isEmpty() && Files.isDirectory(Paths.get(ult))) fc.setCurrentDirectory(new File(ult));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        Cfg.set("ultima_cartella", f.getParent() == null ? f.getPath() : f.getParent());
        aggiungiPercorsi(List.of(f.toPath()));
    }

    /** Trova le immagini (anche nelle cartelle, fino a 4 livelli) senza bloccare la finestra. */
    void aggiungiPercorsi(List<Path> input) {
        msg("Cerco le immagini…", null);
        Path esclusa = Cfg.cartellaUscita().toAbsolutePath().normalize();
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() {
                List<Path> trovate = new ArrayList<>();
                for (Path p : input) {
                    try {
                        if (Files.isDirectory(p)) {
                            try (Stream<Path> s = Files.walk(p, 4)) {
                                s.filter(Files::isRegularFile).filter(Motore::isImmagine)
                                        .filter(x -> !x.toAbsolutePath().normalize().startsWith(esclusa))
                                        .sorted().forEach(trovate::add);
                            }
                        } else if (Files.isRegularFile(p) && Motore.isImmagine(p)) {
                            trovate.add(p);
                        }
                    } catch (IOException | RuntimeException e) {
                        Cfg.err("scansione " + p.getFileName(), e);
                    }
                }
                return trovate;
            }

            @Override
            protected void done() {
                try {
                    aggiungiVoci(get());
                } catch (Exception e) {
                    Cfg.err("aggiungi", e);
                    msg("Non sono riuscito ad aggiungere le immagini", false);
                }
            }
        }.execute();
    }

    private void aggiungiVoci(List<Path> trovate) {
        List<Voce> nuove = new ArrayList<>();
        int doppie = 0;
        for (Path p : trovate) {
            Path k = p.toAbsolutePath().normalize();
            if (!percorsi.add(k)) {
                doppie++;
                continue;
            }
            Voce v = new Voce(p);
            try {
                v.orig = Files.size(p);
            } catch (IOException e) {
                v.errore = "File non leggibile";
            }
            nuove.add(v);
        }
        if (nuove.isEmpty()) {
            msg(trovate.isEmpty() ? "Non ho trovato immagini (JPG, PNG, BMP, GIF, TIFF)" : "Erano già nell'elenco", trovate.isEmpty() ? false : null);
            return;
        }
        inserisci(nuove);
        msg(nuove.size() + (nuove.size() == 1 ? " immagine aggiunta" : " immagini aggiunte") + (doppie > 0 ? " (" + doppie + " già presenti)" : ""), true);
    }

    private void inserisci(List<Voce> nuove) {
        boolean eraVuota = voci.isEmpty();
        int i = 0;
        for (Voce v : nuove) {
            voci.add(v);
            VoceCard c = lista.aggiungi(v, Math.min(i, 12) * 50);
            i++;
            caricaMiniatura(v, c);
        }
        if (eraVuota) mostraVista();
        coda.contaCambiata();
        coda.doLayout();
        coda.repaint();
        if (cur == null || nuove.size() > 0) seleziona(nuove.get(0));
    }

    private void caricaMiniatura(Voce v, VoceCard c) {
        miniEx.submit(() -> {
            try {
                BufferedImage m = v.mem != null ? v.mem : Motore.miniatura(v.file, 104);
                BufferedImage q = ZonaDrop.quadrata(m, 104);
                SwingUtilities.invokeLater(() -> {
                    v.mini = q;
                    c.arrivataMiniatura();
                });
            } catch (Throwable e) {
                SwingUtilities.invokeLater(() -> {
                    v.errore = "Non riesco a leggerla";
                    c.repaint();
                });
            }
        });
    }

    private void incolla() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null) return;
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> l = new ArrayList<>();
                for (File f : files) l.add(f.toPath());
                aggiungiPercorsi(l);
            } else if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                java.awt.Image im = (java.awt.Image) t.getTransferData(DataFlavor.imageFlavor);
                BufferedImage b = new BufferedImage(im.getWidth(null), im.getHeight(null), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = b.createGraphics();
                g.drawImage(im, 0, 0, null);
                g.dispose();
                Voce v = new Voce(b, "Incollata " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH.mm.ss")) + ".png");
                v.orig = Motore.pesoPng(b);
                inserisci(List.of(v));
                msg("Immagine incollata", true);
            } else {
                msg("Negli appunti non c'è nessuna immagine", false);
            }
        } catch (UnsupportedFlavorException | IOException | RuntimeException e) {
            Cfg.err("incolla", e);
            msg("Non sono riuscito a incollare", false);
        }
    }

    // ---------------------------------------------------------------- selezione e anteprima
    private void svuotaElenco() {
        if (inElaborazione) return;
        lista.svuota();
        voci.clear();
        percorsi.clear();
        cur = null;
        origImg = vistaFull = null;
        seqCarica++;
        seqAnteprima++;
        confronto.vuoto();
        controlli.abilita(false);
        Anim.dopo(260, this::mostraVista);
        coda.contaCambiata();
        coda.repaint();
        msg("Elenco svuotato (i file originali non sono stati toccati)", null);
    }

    private void rimuovi(Voce v) {
        if (inElaborazione) return;
        int idx = voci.indexOf(v);
        if (idx < 0) return;
        voci.remove(v);
        if (v.file != null) percorsi.remove(v.file.toAbsolutePath().normalize());
        lista.rimuovi(v);
        coda.contaCambiata();
        coda.repaint();
        if (cur == v) {
            cur = null;
            origImg = vistaFull = null;
            seqCarica++;
            seqAnteprima++;
            if (voci.isEmpty()) {
                confronto.vuoto();
                controlli.abilita(false);
                Anim.dopo(260, this::mostraVista);
            } else {
                seleziona(voci.get(Math.min(idx, voci.size() - 1)));
            }
        }
    }

    void seleziona(Voce v) {
        if (v == cur && origImg != null) return;
        cur = v;
        lista.seleziona(v);
        origImg = vistaFull = null;
        controlli.abilita(false);
        int tok = ++seqCarica;
        ++seqAnteprima;
        confronto.carico(v.nome);
        caricaEx.submit(() -> {
            try {
                BufferedImage img = v.mem != null ? v.mem : Motore.leggi(v.file);
                BufferedImage vista = Motore.ridotta(Motore.rgb(img), Confronto.MAX_VISTA);
                SwingUtilities.invokeLater(() -> {
                    if (tok != seqCarica || cur != v) return;
                    origImg = img;
                    vistaFull = vista;
                    v.larg = img.getWidth();
                    v.alt = img.getHeight();
                    v.errore = null;
                    confronto.immagine(v.nome, vista, v.larg, v.alt, v.crop, v.larg + " × " + v.alt + " px  ·  " + Motore.peso(v.orig));
                    controlli.abilita(true);
                    lista.carta(v).repaint();
                    anteprima();
                });
            } catch (Throwable e) {
                Cfg.err("carica " + v.nome, e);
                String t = e instanceof OutOfMemoryError ? "Immagine troppo grande per la memoria" : "Non riesco a leggere questa immagine";
                SwingUtilities.invokeLater(() -> {
                    if (tok != seqCarica || cur != v) return;
                    v.errore = t;
                    confronto.errore(v.nome, t);
                    VoceCard c = lista.carta(v);
                    if (c != null) c.repaint();
                });
            }
        });
    }

    /** Ricalcola l'anteprima con le impostazioni attuali (in un thread a parte). */
    void anteprima() {
        if (cur == null || origImg == null) return;
        final Voce v = cur;
        final BufferedImage src = origImg, full = vistaFull;
        final Rectangle crop = v.crop == null ? null : new Rectangle(v.crop);
        final int q = controlli.qualita.valore(), pct = controlli.dimensione.valore();
        final boolean prog = Cfg.bool("progressivo", true);
        final int fmt = formatoCorrente();
        final int tok = ++seqAnteprima;
        confronto.inCalcolo(true);
        anteprimaEx.submit(() -> {
            try {
                if (tok != seqAnteprima) return;
                byte[] d = Motore.render(src, crop, pct, q, prog, fmt);
                if (tok != seqAnteprima) return;
                BufferedImage dec = Motore.decodifica(d);
                Rectangle rc = Motore.ritaglioValido(src, crop);
                BufferedImage vO = rc == null ? null : Motore.ridotta(Motore.rgb(src.getSubimage(rc.x, rc.y, rc.width, rc.height)), Confronto.MAX_VISTA);
                BufferedImage base = vO != null ? vO : full;
                BufferedImage vR = dec.getWidth() == base.getWidth() && dec.getHeight() == base.getHeight()
                        ? dec : Gfx.scala(Motore.rgb(dec), base.getWidth(), base.getHeight());
                SwingUtilities.invokeLater(() -> {
                    if (tok != seqAnteprima || cur != v) return;
                    v.out = d.length;
                    confronto.risultato(vO, vR, v.orig, d.length, dec.getWidth(), dec.getHeight());
                    VoceCard c = lista.carta(v);
                    if (c != null) c.repaint();
                    coda.repaint();
                });
            } catch (Throwable e) {
                Cfg.err("anteprima", e);
                SwingUtilities.invokeLater(() -> {
                    if (tok != seqAnteprima) return;
                    confronto.inCalcolo(false);
                    msg(e instanceof OutOfMemoryError ? "Immagine troppo grande per la memoria" : "Non riesco a comprimere questa immagine", false);
                });
            }
        });
    }

    // ---------------------------------------------------------------- salvataggio
    private Path destinazione(Voce v) {
        String ext = Motore.ESTENSIONI[formatoCorrente()];
        if (v.uscita != null && v.uscita.getFileName().toString().toLowerCase().endsWith(ext)) return v.uscita; // un file creato da noi: lo aggiorno
        String base = v.file != null ? Motore.nomeBase(v.nome) : Motore.nomeBase(v.nome).replace(' ', '_');
        if (v.file != null && "accanto".equals(Cfg.str("dove", "fissa")) && v.file.getParent() != null) {
            return v.file.resolveSibling(base + "_compressa" + ext);
        }
        return Cfg.cartellaUscita().resolve(base + ext);
    }

    private Path scrivi(Voce v, byte[] d) throws IOException {
        Path dest = destinazione(v);
        boolean nostro = v.uscita != null && dest.equals(v.uscita);
        Files.createDirectories(dest.getParent());
        if (!nostro) dest = Cfg.uniquePath(dest);
        Cfg.scriviAtomico(dest, d, nostro);
        v.uscita = dest;
        ultimaUscitaDir = dest.getParent();
        return dest;
    }

    /** modo 0 = salva, 1 = salva e copia il file, 2 = copia i pixel. */
    void salvaCorrente(int modo) {
        if (cur == null || origImg == null || inElaborazione) return;
        final Voce v = cur;
        final BufferedImage src = origImg;
        final int q = controlli.qualita.valore(), pct = controlli.dimensione.valore();
        final boolean prog = Cfg.bool("progressivo", true);
        final int fmt = formatoCorrente();
        final Rectangle crop = v.crop == null ? null : new Rectangle(v.crop);
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                byte[] d = Motore.render(src, crop, pct, q, prog, fmt);
                if (modo == 2) return new Object[]{d, null};
                return new Object[]{d, scrivi(v, d)};
            }

            @Override
            protected void done() {
                try {
                    Object[] r = get();
                    byte[] d = (byte[]) r[0];
                    Path p = (Path) r[1];
                    if (modo == 2) {
                        BufferedImage im = Motore.decodifica(d);
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImmagineSel(im), null);
                        msg("Immagine copiata (" + Motore.peso(d.length) + ")", true);
                    } else {
                        if (modo == 1) {
                            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new FileSel(p.toFile()), null);
                            msg("File copiato: " + p.getFileName() + " (" + Motore.peso(d.length) + ")", true);
                        } else {
                            msg("Salvato: " + p.getFileName() + " (" + Motore.peso(d.length) + ")", true);
                        }
                        VoceCard c = lista.carta(v);
                        if (c != null) c.repaint();
                    }
                } catch (Exception e) {
                    Cfg.err("salva", e);
                    msg("Non sono riuscito a salvare: " + causa(e), false);
                }
            }
        }.execute();
    }

    private static String causa(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 80 ? m.substring(0, 80) + "…" : m);
    }

    private static final class ImmagineSel implements Transferable {
        private final java.awt.Image img;

        ImmagineSel(java.awt.Image img) {
            this.img = img;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor f) {
            return DataFlavor.imageFlavor.equals(f);
        }

        @Override
        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
            return img;
        }
    }

    private static final class FileSel implements Transferable {
        private final List<File> files;

        FileSel(File f) {
            files = List.of(f);
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor f) {
            return DataFlavor.javaFileListFlavor.equals(f);
        }

        @Override
        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
            return files;
        }
    }

    // ---------------------------------------------------------------- comprimi tutto
    void comprimiTutto() {
        if (inElaborazione) return;
        if (voci.isEmpty()) {
            msg("Non c'è ancora nessuna immagine: trascinale qui", false);
            return;
        }
        inElaborazione = true;
        annullaElab = false;
        final List<Voce> lotto = new ArrayList<>(voci);
        final int q = controlli.qualita.valore(), pct = controlli.dimensione.valore();
        final boolean prog = Cfg.bool("progressivo", true);
        final int fmt = formatoCorrente();
        AvanzamentoScheda av = new AvanzamentoScheda(() -> annullaElab = true);
        overlay.mostra(av, 520, 270, false);
        new SwingWorker<long[], Object[]>() {
            final List<Voce> inErrore = new ArrayList<>();

            @Override
            protected long[] doInBackground() {
                long prima = 0, dopo = 0, n = 0;
                for (int i = 0; i < lotto.size(); i++) {
                    if (annullaElab) break;
                    Voce v = lotto.get(i);
                    publish(new Object[]{i, lotto.size(), v.nome});
                    try {
                        BufferedImage img = v.mem != null ? v.mem : Motore.leggi(v.file);
                        byte[] d = Motore.render(img, v.crop, pct, q, prog, fmt);
                        scrivi(v, d);
                        v.out = d.length;
                        prima += v.orig;
                        dopo += d.length;
                        n++;
                    } catch (Throwable e) {
                        Cfg.err("comprimi " + v.nome, e);
                        v.errore = e instanceof OutOfMemoryError ? "Troppo grande per la memoria" : "Non riesco a comprimerla";
                        inErrore.add(v);
                    }
                }
                return new long[]{n, inErrore.size(), prima, dopo};
            }

            @Override
            protected void process(List<Object[]> chunks) {
                Object[] c = chunks.get(chunks.size() - 1);
                av.avanza((Integer) c[0], (Integer) c[1], (String) c[2]);
            }

            @Override
            protected void done() {
                long[] r;
                try {
                    r = get();
                } catch (Exception e) {
                    Cfg.err("comprimi tutto", e);
                    r = new long[]{0, lotto.size(), 0, 0};
                }
                final long[] ris = r;
                av.completa();
                inElaborazione = false;
                for (VoceCard c : lista.carte) c.repaint();
                coda.repaint();
                final boolean annullato = annullaElab;
                Anim.pausa(annullato ? 100 : 380, () -> overlay.chiudi(() -> {
                    EsitoScheda es = new EsitoScheda((int) ris[0], (int) ris[1], ris[2], ris[3], annullato, Finestra.this::apriUltima);
                    overlay.mostra(es, 520, es.ch, true);
                    if (ris[0] > 0 && Cfg.bool("apri_fine", true)) apriUltima();
                }));
                if (ris[0] > 0) msg((annullato ? "Annullato: " : "Fatto: ") + ris[0] + (ris[0] == 1 ? " immagine salvata" : " immagini salvate"), !annullato ? true : null);
            }
        }.execute();
    }

    // ---------------------------------------------------------------- cartelle
    private void apri(Path p) {
        try {
            if (p == null || !Files.isDirectory(p)) {
                msg("La cartella non esiste ancora: si crea al primo salvataggio", null);
                return;
            }
            java.awt.Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) {
            Cfg.err("apri cartella", e);
            msg("Non riesco ad aprire la cartella", false);
        }
    }

    void apriUscita() {
        apri(Cfg.cartellaUscita());
    }

    private void apriUltima() {
        apri(ultimaUscitaDir != null ? ultimaUscitaDir : Cfg.cartellaUscita());
    }

    void scegliCartellaUscita(Runnable dopo) {
        JFileChooser fc = new JFileChooser(Cfg.cartellaUscita().toFile().isDirectory() ? Cfg.cartellaUscita().toFile() : Cfg.cartellaUscita().getParent() != null && Cfg.cartellaUscita().getParent().toFile().isDirectory() ? Cfg.cartellaUscita().getParent().toFile() : null);
        fc.setDialogTitle("Dove salvo le immagini compresse?");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Cfg.set("cartella", fc.getSelectedFile().getAbsolutePath());
        Cfg.salva();
        if (dopo != null) dopo.run();
        msg("Le immagini andranno in " + fc.getSelectedFile().getName(), true);
    }

    void uscitaCambiata() {
        msg("Dove salvare: " + ("accanto".equals(Cfg.str("dove", "fissa")) ? "accanto all'originale" : "cartella fissa"), null);
    }

    // ---------------------------------------------------------------- tema e segnalazioni
    void cambiaTema(String nome) {
        Tema.imposta(nome);
        Cfg.set("tema", nome);
        Cfg.salva();
        TitoloScuro.applica(this);
        getContentPane().repaint();
        overlay.repaint();
    }

    void segnala() {
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                return Cfg.creaReport();
            }

            @Override
            protected void done() {
                try {
                    Path p = get();
                    msg("Report creato sul Desktop: " + p.getFileName() + ". Allegalo a una mail per " + Cfg.EMAIL, true);
                    try {
                        java.awt.Desktop.getDesktop().open(p.toFile());
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    Cfg.err("report", e);
                    msg("Non sono riuscito a creare il report", false);
                }
            }
        }.execute();
    }

    // ---------------------------------------------------------------- chiusura
    private void chiudiApp() {
        if (inElaborazione) {
            msg("Sto ancora comprimendo: aspetta la fine o premi Annulla", null);
            return;
        }
        salvaScelte();
        Cfg.salva();
        dispose();
        System.exit(0);
    }

    // ---------------------------------------------------------------- aiuti per i test
    Voce corrente() {
        return cur;
    }

    void impostaFormato(int i) {
        controlli.formato.seleziona(i, true, true);
    }

    java.awt.Component formatoPerTest() {
        return controlli.formato;
    }

    void overlayChiudi() {
        overlay.chiudi();
    }

    Confronto confrontoPerTest() {
        return confronto;
    }

    List<String> nomi() {
        return Arrays.asList(voci.stream().map(v -> v.nome).toArray(String[]::new));
    }
}
