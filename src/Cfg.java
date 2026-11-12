import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import javax.swing.SwingUtilities;

/**
 * Impostazioni, log, pulizia e istanza unica.
 * Tutto quello che il programma scrive per conto suo sta in %APPDATA%\ComprimiImmaginiRapido
 * (config.properties e log.txt): niente file accanto all'exe, niente cartelle in giro.
 */
final class Cfg {
    private Cfg() {
    }

    static final String APP = "Comprimi Immagini Rapido";
    static final String VERSION = "1.0";
    static final String EMAIL = "domenico.castaldod29@gmail.com";
    static final String CARTELLA_DEFAULT_NOME = "Comprimi Immagini Rapido";
    private static final long GIORNO_MS = 86_400_000L;
    private static final int PORTA = 47213;

    static final Path DIR = calcolaDir();
    static final Path FILE = DIR.resolve("config.properties");
    static final Path LOG = DIR.resolve("log.txt");
    static final Properties P = new Properties();
    private static ServerSocket server;

    private static Path calcolaDir() {
        String t = System.getProperty("cir.dir"); // solo per i test
        if (t != null && !t.isEmpty()) return Paths.get(t);
        String ad = System.getenv("APPDATA");
        Path base = ad != null && !ad.isEmpty() ? Paths.get(ad) : Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve("ComprimiImmaginiRapido");
    }

    // ---------------------------------------------------------------- impostazioni
    static void carica() {
        try {
            Files.createDirectories(DIR);
        } catch (IOException ignored) {
        }
        if (!Files.isRegularFile(FILE)) return;
        try (InputStream in = Files.newInputStream(FILE)) {
            P.load(in);
        } catch (Exception e) {
            // file rovinato: lo metto da parte (ne tengo al massimo 3), non lo cancello subito
            P.clear();
            try {
                Files.move(FILE, uniquePath(DIR.resolve("config_rovinata.properties")));
                TreeSet<Path> vecchie = new TreeSet<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIR, "config_rovinata*.properties")) {
                    for (Path p : ds) vecchie.add(p);
                }
                List<Path> l = new ArrayList<>(vecchie);
                for (int i = 0; i < l.size() - 3; i++) Files.deleteIfExists(l.get(i));
            } catch (IOException ignored) {
            }
        }
    }

    static synchronized void salva() {
        try {
            Files.createDirectories(DIR);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            P.store(bo, APP);
            scriviAtomico(FILE, bo.toByteArray(), true);
        } catch (IOException e) {
            err("salva impostazioni", e);
        }
    }

    static String str(String k, String def) {
        return P.getProperty(k, def);
    }

    static int intero(String k, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(P.getProperty(k, "" + def).trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean bool(String k, boolean def) {
        String v = P.getProperty(k);
        return v == null ? def : v.trim().equalsIgnoreCase("true");
    }

    static void set(String k, Object v) {
        P.setProperty(k, String.valueOf(v));
    }

    static Path cartellaUscita() {
        String s = P.getProperty("cartella", "");
        if (s.isBlank()) return Paths.get(System.getProperty("user.home"), "Documents", CARTELLA_DEFAULT_NOME);
        return Paths.get(s);
    }

    // ---------------------------------------------------------------- file sicuri
    /** Un percorso che non esiste ancora: aggiunge _2, _3… Non sovrascrive mai niente. */
    static Path uniquePath(Path p) {
        if (!Files.exists(p)) return p;
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 2; i < 100000; i++) {
            Path c = p.resolveSibling(base + "_" + i + ext);
            if (!Files.exists(c)) return c;
        }
        return p.resolveSibling(base + "_" + System.currentTimeMillis() + ext);
    }

    /**
     * Scrive in un file temporaneo e poi lo sposta al posto giusto: se qualcosa va storto
     * non resta mezzo file e il temporaneo viene tolto.
     */
    static Path scriviAtomico(Path dest, byte[] dati, boolean sostituisci) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + (dest.getParent() != null && dest.getParent().equals(DIR) ? ".tmp" : ".cir.tmp"));
        try {
            Files.write(tmp, dati);
            if (sostituisci) {
                try {
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(tmp, dest);
            }
            return dest;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    /** Toglie i resti di una chiusura anomala e i vecchi report. Solo roba di questo programma. */
    static void pulisci() {
        togli(DIR, "*.tmp");
        togli(cartellaUscita(), "*.cir.tmp");
        Path desk = Paths.get(System.getProperty("user.home"), "Desktop");
        if (!Files.isDirectory(desk)) desk = Paths.get(System.getProperty("user.home"));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(desk, "report_comprimi_*.txt")) {
            for (Path p : ds) {
                try {
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > 14 * GIORNO_MS) Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        try { // il log non cresce all'infinito
            if (Files.size(LOG) > 200_000) {
                List<String> righe = Files.readAllLines(LOG, StandardCharsets.UTF_8);
                List<String> coda = righe.subList(Math.max(0, righe.size() - 800), righe.size());
                scriviAtomico(LOG, (String.join("\n", coda) + "\n").getBytes(StandardCharsets.UTF_8), true);
            }
        } catch (IOException ignored) {
        }
    }

    private static void togli(Path dir, String glob) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- log
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static synchronized void info(String s) {
        scrivi("INFO " + s);
    }

    static synchronized void err(String s, Throwable t) {
        StringBuilder sb = new StringBuilder("ERRORE " + s);
        if (t != null) {
            sb.append(": ").append(t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) sb.append("\n    at ").append(st[i]);
        }
        scrivi(sb.toString());
    }

    private static void scrivi(String riga) {
        try {
            Files.createDirectories(DIR);
            Files.write(LOG, (LocalDateTime.now().format(FMT) + " " + riga + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- istanza unica
    /**
     * true = questa è l'unica istanza (resta in ascolto); false = ce n'era già un'altra
     * e l'ho portata in primo piano. Se la porta è occupata da altro, si parte comunque.
     */
    static boolean istanzaUnica(Runnable portaInPrimoPiano) {
        try {
            server = new ServerSocket(PORTA, 4, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(1000);
                        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        if ("CIR-SHOW".equals(r.readLine())) {
                            OutputStream o = s.getOutputStream();
                            o.write("CIR-OK\n".getBytes(StandardCharsets.UTF_8));
                            o.flush();
                            SwingUtilities.invokeLater(portaInPrimoPiano);
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "istanza-unica");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException occupata) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORTA), 400);
                s.setSoTimeout(900);
                s.getOutputStream().write("CIR-SHOW\n".getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                String r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
                return !"CIR-OK".equals(r);
            } catch (IOException ex) {
                return true;
            }
        }
    }

    // ---------------------------------------------------------------- segnalazioni
    private static String anonimizza(String t) {
        for (String v : new String[]{System.getProperty("user.home"), System.getProperty("user.name")}) {
            if (v != null && !v.isEmpty()) t = t.replace(v, v.equals(System.getProperty("user.name")) ? "utente" : "~");
        }
        return t;
    }

    /** File di testo con i dati tecnici utili a risolvere un errore: niente immagini, niente percorsi personali. */
    static Path creaReport() throws IOException {
        List<String> r = new ArrayList<>();
        r.add("REPORT - " + APP + " " + VERSION);
        r.add("Creato: " + LocalDateTime.now().format(FMT));
        r.add("Sistema: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        r.add("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        r.add("Memoria massima: " + Runtime.getRuntime().maxMemory() / 1048576 + " MB");
        try {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                java.awt.DisplayMode m = d.getDisplayMode();
                r.add("Schermo: " + m.getWidth() + "x" + m.getHeight() + " scala " + d.getDefaultConfiguration().getDefaultTransform().getScaleX());
            }
        } catch (Throwable e) {
            r.add("Schermi: errore nel leggerli (" + e + ")");
        }
        r.add("");
        r.add("IMPOSTAZIONI (senza percorsi)");
        List<String> chiavi = new ArrayList<>(P.stringPropertyNames());
        Collections.sort(chiavi);
        for (String k : chiavi) if (!k.startsWith("cartella") && !k.startsWith("ultima")) r.add(k + " = " + P.getProperty(k));
        r.add("");
        r.add("ULTIME RIGHE DEL LOG");
        try {
            List<String> l = Files.readAllLines(LOG, StandardCharsets.UTF_8);
            r.addAll(l.subList(Math.max(0, l.size() - 200), l.size()));
            if (l.isEmpty()) r.add("(vuoto)");
        } catch (NoSuchFileException e) {
            r.add("(nessun log)");
        } catch (IOException e) {
            r.add("(log illeggibile)");
        }
        Path desk = Paths.get(System.getProperty("user.home"), "Desktop");
        if (!Files.isDirectory(desk)) desk = Paths.get(System.getProperty("user.home"));
        Path dest = uniquePath(desk.resolve("report_comprimi_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt"));
        return scriviAtomico(dest, (anonimizza(String.join("\n", r)) + "\n").getBytes(StandardCharsets.UTF_8), false);
    }
}
