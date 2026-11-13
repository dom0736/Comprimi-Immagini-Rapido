import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.nio.file.Path;

/** Finestra "a scheda" che compare sopra tutto con sfondo scurito (niente popup di Windows). */
class Overlay extends JComponent {
    final Anim.Val t = new Anim.Val(0, this::repaint);
    private Scheda scheda;
    private boolean chiudibile = true, chiudendo;
    private Runnable dopo;

    Overlay() {
        setLayout(null);
        setOpaque(false);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (scheda != null && chiudibile && !chiudendo && !scheda.getBounds().contains(e.getPoint())) chiudi();
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        setCursor(Cursor.getDefaultCursor());
    }

    boolean aperto() {
        return scheda != null && isVisible() && !chiudendo;
    }

    boolean puoChiudersiConEsc() {
        return aperto() && chiudibile;
    }

    void mostra(Scheda s, int w, int h, boolean chiudibile) {
        removeAll();
        scheda = s;
        s.overlay = this;
        s.cw = w;
        s.ch = h;
        this.chiudibile = chiudibile;
        chiudendo = false;
        add(s);
        setVisible(true);
        doLayout();
        t.to(1, 260);
        s.apri();
        repaint();
    }

    void chiudi() {
        chiudi(null);
    }

    void chiudi(Runnable dopoChiusura) {
        if (scheda == null || chiudendo) {
            if (dopoChiusura != null) dopoChiusura.run();
            return;
        }
        chiudendo = true;
        dopo = dopoChiusura;
        scheda.ent.to(0, 200);
        t.to(0, 240, () -> {
            setVisible(false);
            removeAll();
            if (scheda != null) scheda.chiuso();
            scheda = null;
            chiudendo = false;
            Runnable r = dopo;
            dopo = null;
            if (r != null) r.run();
        });
    }

    @Override
    public void doLayout() {
        if (scheda == null) return;
        int w = Math.min(scheda.cw, Math.max(300, getWidth() - 40));
        int h = Math.min(scheda.ch, Math.max(300, getHeight() - 40));
        scheda.setBounds((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        double q = t.get();
        if (q <= 0.001) return;
        Graphics2D g = Gfx.g2(gr);
        g.setColor(Tema.p.scuro ? new Color(5, 9, 18, (int) (110 * q)) : new Color(28, 38, 70, (int) (70 * q)));
        g.fillRect(0, 0, getWidth(), getHeight());
        if (scheda != null) {
            double e = scheda.ent.get();
            int sh = 9;
            for (int i = sh; i >= 1; i--) {
                g.setColor(Gfx.a(new Color(0, 0, 0), (int) ((1 - i / (sh + 1.0)) * (1 - i / (sh + 1.0)) * 26 * e)));
                g.fill(Gfx.rr(scheda.getX() - i, scheda.getY() - i + 3, scheda.getWidth() + 2 * i, scheda.getHeight() + 2 * i, 22 + i));
            }
        }
        g.dispose();
    }
}

/** Base delle schede: fondo pieno arrotondato, entrata con scala + dissolvenza. */
class Scheda extends JPanel {
    Overlay overlay;
    int cw = 560, ch = 480;
    final Anim.Val ent = new Anim.Val(0, () -> {
        if (getParent() != null) getParent().repaint();
        repaint();
    });

    Scheda() {
        setOpaque(false);
        setLayout(null);
        ent.ease(Anim.OUT_CUBIC);
        addMouseListener(new MouseAdapter() { // i clic dentro la scheda non devono chiuderla
        });
    }

    void apri() {
        ent.jump(0);
        ent.to(1, 340);
    }

    /** Chiamato quando la scheda è sparita del tutto. */
    void chiuso() {
    }

    @Override
    public void paint(Graphics gr) {
        double e = ent.get();
        if (e >= 0.999) {
            super.paint(gr);
            return;
        }
        Graphics2D g = (Graphics2D) gr.create();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, e * 1.2))));
        double s = .95 + .05 * e;
        g.translate(getWidth() / 2.0, getHeight() / 2.0 + (1 - e) * 14);
        g.scale(s, s);
        g.translate(-getWidth() / 2.0, -getHeight() / 2.0);
        super.paint(g);
        g.dispose();
    }

    static Color sfondoScheda() {
        return Tema.p.scuro ? Tema.hex("#243146") : Tema.hex("#f8f9fd");
    }

    @Override
    protected void paintComponent(Graphics gr) {
        Graphics2D g = Gfx.g2(gr);
        g.setColor(sfondoScheda());
        g.fill(Gfx.rr(0, 0, getWidth(), getHeight(), 22));
        g.setColor(Tema.p.scuro ? new Color(255, 255, 255, 36) : new Color(49, 65, 84, 40));
        g.setStroke(new BasicStroke(1f));
        g.draw(Gfx.rr(.5, .5, getWidth() - 1, getHeight() - 1, 21.5));
        g.dispose();
    }

    /** Intestazione: icona, titolo e pulsante di chiusura. */
    void titolo(Graphics2D g, String icona, String titolo, String sotto) {
        Tema.Pal p = Tema.p;
        double cx = 40, cy = 40;
        Gfx.pillolaAcc(g, cx - 18, cy - 18, 36, 36, 12, 0);
        Gfx.glifo(g, icona, cx, cy, 19, Color.WHITE);
        g.setFont(Tema.fb(17f));
        g.setColor(p.fg);
        g.drawString(titolo, 68, sotto == null ? 46 : 38);
        if (sotto != null) {
            g.setFont(Tema.f(12f));
            g.setColor(p.mut);
            g.drawString(sotto, 68, 56);
        }
    }

    Bott bottoneChiudi() {
        Bott b = new Bott("", "x", () -> overlay.chiudi());
        b.alt = 34;
        return b;
    }
}

/** La guida del programma. */
class GuidaScheda extends Scheda {
    private final JTextPane testo = new JTextPane();
    private final JScrollPane scroll;
    private final Bott chiudi = bottoneChiudi();

    static final String CONTENUTO = String.join("\n",
            "# COME SI USA",
            "1. Trascina le immagini (o una cartella intera) nella finestra. Oppure premi «Aggiungi» o Ctrl+V per incollare uno screenshot.",
            "2. Scegli come comprimere: i pulsanti Minima, Leggera, Normale e Nitida impostano la qualità (e la dimensione, se serve); con il cursore Qualità la regoli a mano. Formato, dimensione e altre opzioni stanno nella sezione «Altro», chiusa di base.",
            "3. Sposta la linea sull'anteprima per confrontare l'originale (a sinistra) con la versione compressa (a destra). Sotto vedi quanto pesa prima e dopo. Con la rotellina del mouse ingrandisci l'anteprima, trascinala per spostarti, doppio clic per tornare alla vista intera.",
            "4. «Comprimi tutto» salva tutte le immagini dell'elenco. «Salva», «Copia file» e «Copia immagine» lavorano solo su quella selezionata.",
            "",
            "# LE QUATTRO QUALITÀ",
            "• Minima: qualità 50, dimensione 30%. Per mandare tante foto in chat o per mail.",
            "• Leggera: qualità 65, dimensione 50%. Per siti e documenti.",
            "• Normale: qualità 75, dimensione 75%. È l'equilibrio consigliato.",
            "• Nitida: qualità 90, dimensione 100%. Peso ridotto senza rimpicciolire la foto.",
            "Minima, Leggera e Normale accendono da sole «Ridimensiona» (in «Altro»); Nitida la spegne e lascia la dimensione originale.",
            "Se muovi un cursore a mano, nessun pulsante resta acceso: la tua scelta viene ricordata alla prossima apertura.",
            "",
"# ALTRO",
            "Premi «Altro», accanto al cursore Qualità, per aprire le opzioni meno usate. Da chiusa, la sezione riassume cosa hai scelto.",
            "• Formato: JPEG, PNG o PNG 256 (vedi sotto).",
            "• Ridimensiona: di base è spenta e il cursore della dimensione è bloccato, così le immagini restano della misura originale. Spunta solo se vuoi anche rimpicciolirle, poi scegli la percentuale.",
            "• Progressivo (solo JPEG): la foto si carica prima sfocata e poi nitida, utile per il web.",
            "• Bianco e nero: toglie i colori e il file pesa meno. Vale per tutti i formati.",
            "• Dettaglio colore (solo JPEG): «Standard» tiene i colori a metà risoluzione, file più leggero; «Pieno» li tiene alla risoluzione piena, bordi colorati più nitidi (testi rossi, grafica) ma file più pesante.",
            "",
            "# IL FORMATO",
            "In «Altro» scegli il formato del file:",
            "• JPEG: il più leggero per le foto. Usa i cursori Qualità e Dimensione. Se nella cartella «tools» c'è MozJPEG, lo usa da solo e i file sono ancora più piccoli.",
            "• PNG: senza perdita di qualità e con le trasparenze. Per screenshot, loghi e grafica. Il cursore Qualità si spegne (passa il mouse per il motivo): è già al massimo.",
            "• PNG 256: come il PNG ma con meno colori (il cursore Qualità sceglie quanti, da 16 a 256), molto più leggero (di solito dal 50% al 70% in meno). Ottimo per screenshot e grafica, meno adatto a foto con sfumature.",
            "",
            "# RITAGLIARE",
            "Premi «Ritaglia» e trascina un rettangolo sull'immagine. Premi di nuovo «Ritaglia» per vedere il risultato; «Azzera» toglie il ritaglio. Il ritaglio è per singola immagine e vale anche con «Comprimi tutto». Nell'elenco le immagini ritagliate non cambiano nome.",
            "",
            "# DOVE VANNO I FILE",
            "• Cartella fissa: tutto finisce nella cartella scelta in Impostazioni (di base Documenti\\Comprimi Immagini Rapido).",
            "• Accanto all'originale: ogni immagine produce «nome_compressa.jpg» (o .png) nella stessa cartella.",
            "Il programma non sovrascrive mai nulla: se il nome esiste già aggiunge _2, _3… Rifare «Comprimi tutto» sulle stesse immagini aggiorna i file creati prima, senza duplicati. Gli originali non vengono mai toccati.",
            "",
            "# SCORCIATOIE",
            "Ctrl+O aggiungi  ·  Ctrl+V incolla  ·  Ctrl+S salva l'immagine selezionata  ·  Ctrl+Invio comprimi tutto  ·  F11 schermo intero  ·  F nasconde l'elenco e mostra solo l'immagine  ·  Canc toglie dall'elenco  ·  ↑ ↓ cambia immagine  ·  Esc chiude questa finestra, esce dal ritaglio o dallo schermo intero.",
            "",
            "# PRIVACY",
            "La compressione toglie i metadati: posizione GPS, data di scatto, modello del telefono. Va bene per condividere foto senza rivelare dove sono state fatte. Tutto resta sul tuo PC: non viene inviato nulla.",
            "",
            "# NIENTE FILE IN GIRO",
            "Il programma non lascia nulla accanto all'exe. In %APPDATA%\\ComprimiImmaginiRapido ci sono solo config.properties (le impostazioni) e log.txt (limitato a poche centinaia di KB). Durante il lavoro non usa file temporanei; se il programma si chiude male, i resti vengono tolti al prossimo avvio. I report di «Segnala un problema» sul Desktop spariscono dopo 14 giorni.",
            "",
            "# COSE DA SAPERE",
            "• Formati letti: JPG, PNG, BMP, GIF, TIFF. Il risultato è sempre un JPG.",
            "• Il JPG non ha la trasparenza: le parti trasparenti diventano bianche.",
            "• Le foto girate dal telefono vengono raddrizzate (si legge l'orientamento EXIF).",
            "• Il programma si apre una volta sola: se lo riavvii, riporta in primo piano quello già aperto.",
                        "",
            "# OPEN SOURCE E SEGNALAZIONI",
            "Comprimi Immagini Rapido " + Cfg.VERSION + " è open source (licenza MIT). Se qualcosa non funziona o hai un'idea, scrivimi a " + Cfg.EMAIL + ".",
            "Con «Segnala un problema» (in Impostazioni) il programma crea sul Desktop un file con i dati tecnici utili: versione, Windows, schermi, impostazioni ed errori registrati. Non contiene immagini né i nomi delle tue cartelle. Puoi leggerlo prima di inviarlo: allegalo alla mail con due righe su cosa stavi facendo.",
            "",
            "Fatto con cuore da Domenico Castaldo.");

    GuidaScheda() {
        cw = 700;
        ch = 640;
        testo.setEditable(false);
        testo.setOpaque(false);
        testo.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 10));
        testo.setCaret(new javax.swing.text.DefaultCaret() {
            @Override
            public boolean isVisible() {
                return false;
            }
        });
        testo.setTransferHandler(null);
        testo.setSelectionColor(Gfx.a(Tema.p.acc1, 140));
        testo.setSelectedTextColor(Color.WHITE);
        riempi();
        scroll = ScrollSottile.crea(testo);
        add(scroll);
        add(chiudi);
    }

    private void riempi() {
        Tema.Pal p = Tema.p;
        StyledDocument d = testo.getStyledDocument();
        Style h = testo.addStyle("h", null);
        StyleConstants.setFontFamily(h, Tema.FAM);
        StyleConstants.setFontSize(h, 12);
        StyleConstants.setBold(h, true);
        StyleConstants.setForeground(h, p.scuro ? Tema.hex("#9db8ff") : p.acc1);
        Style n = testo.addStyle("n", null);
        StyleConstants.setFontFamily(n, Tema.FAM);
        StyleConstants.setFontSize(n, 13);
        StyleConstants.setForeground(n, Gfx.mix(p.fg, p.mut, .22));
        Style fin = testo.addStyle("f", n);
        StyleConstants.setItalic(fin, true);
        StyleConstants.setForeground(fin, p.mut);
        try {
            for (String riga : CONTENUTO.split("\n", -1)) {
                int inizio = d.getLength();
                SimpleAttributeSet pa = new SimpleAttributeSet();
                StyleConstants.setLineSpacing(pa, .18f);
                Style st = n;
                String t = riga;
                if (riga.startsWith("# ")) {
                    st = h;
                    t = riga.substring(2);
                    StyleConstants.setSpaceAbove(pa, 14);
                    StyleConstants.setSpaceBelow(pa, 4);
                } else if (riga.startsWith("• ")) {
                    StyleConstants.setLeftIndent(pa, 16);
                    StyleConstants.setFirstLineIndent(pa, -16);
                    StyleConstants.setSpaceBelow(pa, 3);
                } else if (riga.startsWith("Fatto con cuore")) {
                    st = fin;
                    StyleConstants.setSpaceAbove(pa, 12);
                } else if (riga.isEmpty()) {
                    StyleConstants.setFontSize(pa, 4);
                } else {
                    StyleConstants.setSpaceBelow(pa, 5);
                }
                d.insertString(d.getLength(), t + "\n", st);
                d.setParagraphAttributes(inizio, t.length() + 1, pa, false);
            }
            testo.setCaretPosition(0);
        } catch (javax.swing.text.BadLocationException ignored) {
        }
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        chiudi.setBounds(w - 22 - 34, 23, 34, 34);
        scroll.setBounds(26, 80, w - 52, h - 80 - 24);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        super.paintComponent(gr);
        Graphics2D g = Gfx.g2(gr);
        titolo(g, "book", "Guida", "Tutto quello che c'è da sapere, in breve");
        g.dispose();
    }
}

/** Impostazioni: tema, dove salvare, cartella, opzioni; più «Segnala un problema». Se la finestra è bassa, le opzioni scorrono. */
class ImpostazioniScheda extends Scheda {
    private static final int TOP = 80, PIEDE = 88, ALTEZZA_CONTENUTO = 400;
    private final Finestra f;
    private final Seg tema, dove;
    private final Bott cambia, apri, segnala, chiudi, x;
    private final Interruttore apriFine, animazioni;
    private final Anim.Val dim = new Anim.Val(0, this::ridisegna);
    private final Contenuto contenuto = new Contenuto();
    private final JScrollPane scroll;

    ImpostazioniScheda(Finestra f) {
        this.f = f;
        cw = 580;
        ch = 580;
        tema = new Seg(new String[]{"Scuro", "Chiaro"}, "chiaro".equals(Cfg.str("tema", "scuro")) ? 1 : 0, 110, i -> f.cambiaTema(i == 1 ? "chiaro" : "scuro"));
        dove = new Seg(new String[]{"Cartella fissa", "Accanto all'originale"}, "accanto".equals(Cfg.str("dove", "fissa")) ? 1 : 0, 0, i -> {
            Cfg.set("dove", i == 1 ? "accanto" : "fissa");
            Cfg.salva();
            dim.to(i == 1 ? 1 : 0, 260);
            f.uscitaCambiata();
        });
        dim.jump("accanto".equals(Cfg.str("dove", "fissa")) ? 1 : 0);
        cambia = new Bott("Cambia…", "folder", () -> f.scegliCartellaUscita(this::ridisegna));
        cambia.alt = 34;
        cambia.fs = 12;
        apri = new Bott("Apri", null, () -> f.apriUscita());
        apri.alt = 34;
        apri.fs = 12;
        apriFine = new Interruttore("Apri la cartella alla fine", "Dopo «Comprimi tutto» si apre Esplora file sui risultati",
                Cfg.bool("apri_fine", true), v -> {
            Cfg.set("apri_fine", v);
            Cfg.salva();
        });
        animazioni = new Interruttore("Animazioni", "Movimenti e dissolvenze. Spegnile se l'app ti sembra lenta",
                Cfg.bool("animazioni", false), v -> {
            Cfg.set("animazioni", v);
            Cfg.salva();
            Anim.attive = v;
        });
        segnala = new Bott("Segnala un problema", "flag", () -> f.segnala());
        segnala.alt = 38;
        chiudi = new Bott("Fatto", null, true, () -> overlay.chiudi());
        chiudi.alt = 38;
        x = bottoneChiudi();
        for (JComponent c : new JComponent[]{tema, dove, cambia, apri, apriFine, animazioni}) contenuto.add(c);
        scroll = ScrollSottile.crea(contenuto);
        scroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
        add(scroll);
        for (JComponent c : new JComponent[]{segnala, chiudi, x}) add(c);
        aggiornaDim();
    }

    private void ridisegna() {
        contenuto.repaint();
        repaint();
    }

    private void aggiornaDim() {
        boolean accanto = dove.selezione() == 1;
        cambia.setEnabled(!accanto);
        apri.setEnabled(!accanto);
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        x.setBounds(w - 22 - 34, 23, 34, 34);
        scroll.setBounds(0, TOP, w, Math.max(60, h - TOP - PIEDE));
        int bw = segnala.getPreferredSize().width;
        segnala.setBounds(30, h - PIEDE, bw, 38);
        chiudi.setBounds(w - 30 - 120, h - PIEDE, 120, 38);
        scroll.revalidate();
    }

    @Override
    protected void paintComponent(Graphics gr) {
        super.paintComponent(gr);
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        titolo(g, "gear", "Impostazioni", "Il programma ricorda le tue scelte");
        g.setFont(Tema.f(11.5f));
        g.setColor(Gfx.mulA(p.mut, .8));
        g.drawString("Versione " + Cfg.VERSION + "  ·  Fatto con cuore da Domenico Castaldo", 31, getHeight() - 20);
        g.dispose();
    }

    /** Area scorrevole con tutte le opzioni (coordinate relative alla sua cima). */
    private final class Contenuto extends JPanel implements javax.swing.Scrollable {
        Contenuto() {
            setOpaque(false);
            setLayout(null);
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            return new java.awt.Dimension(10, ALTEZZA_CONTENUTO);
        }

        @Override
        public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(40, r.height - 30);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public void doLayout() {
            int w = getWidth(), m = 30;
            tema.setBounds(m, 50, tema.getPreferredSize().width, 36);
            dove.setBounds(m, 134, dove.getPreferredSize().width, 36);
            int fy = 214, d1 = cambia.getPreferredSize().width, d2 = apri.getPreferredSize().width;
            apri.setBounds(w - m - d2, fy, d2, 40);
            cambia.setBounds(w - m - d2 - 8 - d1, fy, d1, 40);
            apriFine.setBounds(m, 280, w - 2 * m, 54);
            animazioni.setBounds(m, 338, w - 2 * m, 54);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            aggiornaDim();
            Graphics2D g = Gfx.g2(gr);
            Tema.Pal p = Tema.p;
            g.setFont(Tema.f(12f));
            g.setColor(p.mut);
            g.drawString("Aspetto", 31, 38);
            g.drawString("Dove salvare", 31, 122);
            double q = dim.get();
            g.setColor(Gfx.mulA(p.mut, 1 - .55 * q));
            g.drawString("Cartella di destinazione", 31, 202);
            // campo con il percorso
            int m = 30;
            int fx = m, fy = 214, fw = cambia.getX() - 10 - fx, fh = 40;
            java.awt.Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (1 - .55 * q)));
            g.setColor(p.campo);
            g.fill(Gfx.rr(fx, fy, fw, fh, 12));
            g.setColor(p.campoBordo);
            g.setStroke(new BasicStroke(1f));
            g.draw(Gfx.rr(fx + .5, fy + .5, fw - 1, fh - 1, 11.5));
            Gfx.glifo(g, "folder", fx + 20, fy + fh / 2.0, 16, p.mut);
            g.setFont(Tema.f(12.5f));
            g.setColor(p.fg);
            String s = Cfg.cartellaUscita().toString();
            FontMetrics fm = g.getFontMetrics();
            int max = fw - 44 - 12;
            if (fm.stringWidth(s) > max) { // taglia l'inizio: la parte finale del percorso è la più utile
                while (s.length() > 4 && fm.stringWidth("…" + s) > max) s = s.substring(1);
                s = "…" + s;
            }
            g.drawString(s, fx + 38, (float) (fy + fh / 2.0 + (fm.getAscent() - fm.getDescent()) / 2.0));
            g.setComposite(old);
            if (q > .3) {
                g.setFont(Tema.f(11.5f));
                g.setColor(p.mut);
                g.drawString("Con «Accanto all'originale» ogni immagine produce nome_compressa.jpg/.png", 31, 274);
            }
            g.dispose();
        }
    }
}

/** Barra di avanzamento mentre si comprimono tutte le immagini. */
class AvanzamentoScheda extends Scheda {
    private final Anim.Val prog = new Anim.Val(0, this::repaint);
    private String sotto = "Preparo…";
    private final Bott annulla;
    private double sheen;
    private boolean attivo, finito;
    private long ultimo;
    final Runnable onAnnulla;

    AvanzamentoScheda(Runnable onAnnulla) {
        this.onAnnulla = onAnnulla;
        cw = 520;
        ch = 270;
        annulla = new Bott("Annulla", null, null);
        annulla.azione = () -> {
            annulla.setEnabled(false);
            annulla.setTesto("Annullo…");
            if (onAnnulla != null) onAnnulla.run();
        };
        annulla.alt = 38;
        add(annulla);
        prog.ease(Anim.OUT_CUBIC);
    }

    @Override
    void apri() {
        super.apri();
        attivo = true;
        Anim.ciclo(() -> {
            if (!attivo || !isShowing()) return false;
            long t = Anim.ora();
            if (t - ultimo >= 16) {
                ultimo = t;
                sheen = (t % 1500) / 1500.0;
                repaint();
            }
            return true;
        });
    }

    @Override
    void chiuso() {
        attivo = false;
    }

    void avanza(int fatte, int totale, String nome) {
        prog.to(totale == 0 ? 0 : fatte / (double) totale, 360);
        sotto = Math.min(fatte + 1, totale) + " di " + totale + "  ·  " + nome;
        repaint();
    }

    void completa() {
        prog.to(1, 300);
        finito = true;
        sotto = "Finito";
    }

    @Override
    public void doLayout() {
        annulla.setBounds((getWidth() - 120) / 2, getHeight() - 24 - 38, 120, 38);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        super.paintComponent(gr);
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        titolo(g, "image", "Comprimo le immagini", null);
        int w = getWidth(), m = 30;
        g.setFont(Tema.f(12.5f));
        g.setColor(p.mut);
        g.drawString(Gfx.taglia(sotto, g.getFontMetrics(), w - 2 * m), m, 108);
        double bx = m, by = 130, bw = w - 2 * m, bh = 12;
        g.setColor(p.campo);
        g.fill(Gfx.rr(bx, by, bw, bh, bh / 2));
        double pv = Math.max(0, Math.min(1, prog.get()));
        double fw = Math.max(bh, bw * pv);
        if (pv > 0.001) {
            java.awt.Shape clip = Gfx.rr(bx, by, fw, bh, bh / 2);
            g.setPaint(new GradientPaint((float) bx, 0, p.acc1, (float) (bx + bw), 0, p.acc2));
            g.fill(clip);
            java.awt.Shape old = g.getClip();
            g.clip(clip);
            double sx = bx - 80 + (fw + 160) * sheen;
            g.setPaint(new GradientPaint((float) sx, 0, new Color(255, 255, 255, 0), (float) (sx + 50), 0, new Color(255, 255, 255, 90)));
            g.fill(new java.awt.geom.Rectangle2D.Double(sx, by, 50, bh));
            g.setPaint(new GradientPaint((float) (sx + 50), 0, new Color(255, 255, 255, 90), (float) (sx + 100), 0, new Color(255, 255, 255, 0)));
            g.fill(new java.awt.geom.Rectangle2D.Double(sx + 50, by, 50, bh));
            g.setClip(old);
        }
        Gfx.testoCentro(g, Math.round(pv * 100) + "%", Tema.fb(26f), p.fg, w / 2.0, 176);
        g.dispose();
    }
}

/** Riepilogo finale: spunta che si disegna e numeri che contano. */
class EsitoScheda extends Scheda {
    private final Anim.Val chk = new Anim.Val(0, this::repaint);
    private final Anim.Val num = new Anim.Val(0, this::repaint);
    private final int n, errori;
    private final long prima, dopoByte;
    private final Bott chiudi, apriCartella;
    private final boolean annullato;

    EsitoScheda(int n, int errori, long prima, long dopoByte, boolean annullato, Runnable apriCartella) {
        this.n = n;
        this.errori = errori;
        this.prima = prima;
        this.dopoByte = dopoByte;
        this.annullato = annullato;
        cw = 520;
        ch = 430 + (errori > 0 ? 20 : 0);
        this.apriCartella = new Bott("Apri cartella", "folder", apriCartella);
        this.apriCartella.alt = 38;
        chiudi = new Bott("Chiudi", null, true, () -> overlay.chiudi());
        chiudi.alt = 38;
        add(this.apriCartella);
        add(chiudi);
        chk.ease(Anim.IN_OUT);
        num.ease(Anim.OUT_CUBIC);
    }

    @Override
    void apri() {
        super.apri();
        chk.jump(0);
        num.jump(0);
        Anim.dopo(200, () -> chk.to(1, 800));
        Anim.dopo(450, () -> num.to(1, 1000));
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        int a = apriCartella.getPreferredSize().width;
        apriCartella.setBounds(w / 2 - 6 - a, h - 24 - 38, a, 38);
        chiudi.setBounds(w / 2 + 6, h - 24 - 38, 130, 38);
        if (n == 0) apriCartella.setVisible(false);
    }

    @Override
    protected void paintComponent(Graphics gr) {
        super.paintComponent(gr);
        Graphics2D g = Gfx.g2(gr);
        Tema.Pal p = Tema.p;
        int w = getWidth();
        double cx = w / 2.0, cy = 82, r = 36;
        double c = chk.get();
        // disco verde acqua che "esplode" e anello che si chiude
        double disco = Anim.OUT_BACK.applyAsDouble(Math.min(1, c * 1.8));
        Color v1 = Tema.hex("#34e0c0"), v2 = Tema.hex("#0f9f8a");
        for (int i = 10; i >= 1; i--) {
            g.setColor(Gfx.a(v1, (int) ((1 - i / 11.0) * (1 - i / 11.0) * 34 * Math.min(1, c * 2))));
            g.fill(new Ellipse2D.Double(cx - r * disco - i * 1.5, cy - r * disco - i * 1.5 + 4, 2 * r * disco + i * 3, 2 * r * disco + i * 3));
        }
        g.setPaint(new GradientPaint(0, (float) (cy - r), annullato ? p.pausa : v1, 0, (float) (cy + r), annullato ? Gfx.mix(p.pausa, Color.BLACK, .25) : v2));
        g.fill(new Ellipse2D.Double(cx - r * disco, cy - r * disco, 2 * r * disco, 2 * r * disco));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(5.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double q = Math.max(0, (c - .45) / .55);
        if (annullato) {
            Gfx.glifo(g, "x", cx, cy, 34 * Math.min(1, q * 2), Color.WHITE);
        } else if (q > 0) {
            double[][] pt = {{-.40, .04}, {-.12, .32}, {.42, -.30}};
            double s = 40;
            double l1 = Math.hypot(pt[1][0] - pt[0][0], pt[1][1] - pt[0][1]), l2 = Math.hypot(pt[2][0] - pt[1][0], pt[2][1] - pt[1][1]);
            double L = q * (l1 + l2);
            Path2D path = new Path2D.Double();
            path.moveTo(cx + pt[0][0] * s, cy + pt[0][1] * s);
            if (L <= l1) {
                double k = L / l1;
                path.lineTo(cx + (pt[0][0] + (pt[1][0] - pt[0][0]) * k) * s, cy + (pt[0][1] + (pt[1][1] - pt[0][1]) * k) * s);
            } else {
                double k = (L - l1) / l2;
                path.lineTo(cx + pt[1][0] * s, cy + pt[1][1] * s);
                path.lineTo(cx + (pt[1][0] + (pt[2][0] - pt[1][0]) * k) * s, cy + (pt[1][1] + (pt[2][1] - pt[1][1]) * k) * s);
            }
            g.draw(path);
        }
        Gfx.testoCentro(g, annullato ? "Annullato" : "Fatto!", Tema.fb(22f), p.fg, cx, 150);
        String sotto = annullato ? n + (n == 1 ? " immagine già salvata" : " immagini già salvate")
                : n + (n == 1 ? " immagine compressa" : " immagini compresse");
        Gfx.testoCentro(g, sotto, Tema.f(13f), p.mut, cx, 176);
        // riquadro con i numeri
        double bx = 30, by = 202, bw = w - 60, bh = 110;
        g.setColor(p.campo);
        g.fill(Gfx.rr(bx, by, bw, bh, 16));
        g.setColor(p.campoBordo);
        g.setStroke(new BasicStroke(1f));
        g.draw(Gfx.rr(bx + .5, by + .5, bw - 1, bh - 1, 15.5));
        double k = num.get();
        long a = Math.round(prima * k), b = Math.round(dopoByte * k);
        int risp = prima > 0 ? (int) Math.round(100.0 - 100.0 * dopoByte / prima) : 0;
        long rk = Math.round(risp * k);
        Gfx.testoCentro(g, "Prima", Tema.f(11.5f), p.mut, bx + bw * .17, by + 26);
        Gfx.testoCentro(g, Motore.peso(a), Tema.fb(18f), p.fg, bx + bw * .17, by + 54);
        Gfx.testoCentro(g, "Dopo", Tema.f(11.5f), p.mut, bx + bw * .5, by + 26);
        Gfx.testoCentro(g, Motore.peso(b), Tema.fb(18f), p.fg, bx + bw * .5, by + 54);
        Gfx.testoCentro(g, "Risparmiato", Tema.f(11.5f), p.mut, bx + bw * .83, by + 26);
        Gfx.testoCentro(g, (risp >= 0 ? "−" : "+") + Math.abs(rk) + "%", Tema.fb(26f), risp >= 0 ? p.ok : p.err, bx + bw * .83, by + 56);
        // barra del risparmio
        double barY = by + bh - 20, barX = bx + 24, barW = bw - 48;
        g.setColor(Gfx.a(p.mut, 40));
        g.fill(Gfx.rr(barX, barY, barW, 6, 3));
        double fill = Math.max(0, Math.min(1, (prima > 0 ? dopoByte / (double) prima : 1) * k + (1 - k)));
        g.setPaint(new GradientPaint((float) barX, 0, p.acc1, (float) (barX + barW), 0, p.acc2));
        g.fill(Gfx.rr(barX, barY, Math.max(6, barW * fill), 6, 3));
        if (errori > 0) {
            Gfx.testoCentro(g, errori + (errori == 1 ? " immagine non letta (vedi l'elenco)" : " immagini non lette (vedi l'elenco)"),
                    Tema.f(12f), p.err, cx, by + bh + 22);
        }
        g.dispose();
    }
}
