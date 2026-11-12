import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Colori e font. Stessa identità del Registratore Azioni: grigio-blu, accenti blu → magenta, pallino rosso. */
final class Tema {
    private Tema() {
    }

    static final class Pal {
        boolean scuro;
        Color[] grad;                 // sfondo, dall'alto al basso
        Color[] glowCol;              // bagliori colorati
        float[] glowA;                // intensità
        float[][] glowPos;            // cx, cy, diametro (in frazioni della finestra)
        Color card, cardBordo;        // vetro
        Color btn, btnHover, btnBordo;
        Color campo, campoBordo, linea;
        Color fg, mut, acc1, acc2, accFg;
        Color rec, pausa, ok, err;
        Color ombra;
    }

    static Color hex(String h, int a) {
        return new Color(Integer.parseInt(h.substring(1, 3), 16), Integer.parseInt(h.substring(3, 5), 16),
                Integer.parseInt(h.substring(5, 7), 16), a);
    }

    static Color hex(String h) {
        return hex(h, 255);
    }

    static final Pal SCURO = new Pal();
    static final Pal CHIARO = new Pal();

    static {
        Pal p = SCURO;
        p.scuro = true;
        p.grad = new Color[]{hex("#18212e"), hex("#223044"), hex("#2c3b52")};
        p.glowCol = new Color[]{hex("#7f42f7"), hex("#0e46fe")};
        p.glowA = new float[]{0.30f, 0.50f};
        p.glowPos = new float[][]{{1.0f, 0.0f, 1.1f}, {0.0f, 1.08f, 1.15f}};
        p.card = new Color(255, 255, 255, 20);
        p.cardBordo = new Color(255, 255, 255, 38);
        p.btn = new Color(255, 255, 255, 28);
        p.btnHover = new Color(255, 255, 255, 56);
        p.btnBordo = new Color(255, 255, 255, 58);
        p.campo = hex("#1c2636");
        p.campoBordo = new Color(255, 255, 255, 44);
        p.linea = hex("#3a4a62");
        p.fg = hex("#f4f7fb");
        p.mut = hex("#a9b8cf");
        p.acc1 = hex("#3d6fff");
        p.acc2 = hex("#c13af2");
        p.accFg = Color.WHITE;
        p.rec = hex("#ff4d5a");
        p.pausa = hex("#ffb020");
        p.ok = hex("#8be9b0");
        p.err = hex("#ffa08f");
        p.ombra = new Color(4, 8, 16, 120);

        p = CHIARO;
        p.scuro = false;
        p.grad = new Color[]{hex("#f5f7fb"), hex("#e6ebf6"), hex("#d4daf2")};
        p.glowCol = new Color[]{hex("#ffffff"), hex("#7f42f7")};
        p.glowA = new float[]{0.65f, 0.28f};
        p.glowPos = new float[][]{{0.0f, 0.0f, 1.0f}, {1.0f, 1.08f, 1.15f}};
        p.card = new Color(255, 255, 255, 150);
        p.cardBordo = new Color(255, 255, 255, 220);
        p.btn = new Color(49, 65, 84, 26);
        p.btnHover = new Color(49, 65, 84, 50);
        p.btnBordo = new Color(49, 65, 84, 60);
        p.campo = hex("#ffffff");
        p.campoBordo = new Color(49, 65, 84, 70);
        p.linea = hex("#cfd6e6");
        p.fg = hex("#1b2740");
        p.mut = hex("#5b6b88");
        p.acc1 = hex("#1f55ff");
        p.acc2 = hex("#b02fe0");
        p.accFg = Color.WHITE;
        p.rec = hex("#e11d3f");
        p.pausa = hex("#d97706");
        p.ok = hex("#17724a");
        p.err = hex("#b42318");
        p.ombra = new Color(40, 55, 90, 60);
    }

    static Pal p = SCURO;

    static void imposta(String nome) {
        p = "chiaro".equals(nome) ? CHIARO : SCURO;
    }

    static String nome() {
        return p == CHIARO ? "chiaro" : "scuro";
    }

    // ---------------------------------------------------------------- font
    private static final Set<String> FAMIGLIE = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
    static final String FAM = scegli("Segoe UI", "Inter", "SF Pro Text", "Helvetica Neue", "DejaVu Sans", "Dialog");
    private static final boolean SEMIBOLD = FAMIGLIE.contains("Segoe UI Semibold");
    private static final Map<String, Font> CACHE = new HashMap<>();

    private static String scegli(String... nomi) {
        for (String n : nomi) if (FAMIGLIE.contains(n)) return n;
        return "Dialog";
    }

    /** Testo normale. */
    static Font f(float size) {
        return CACHE.computeIfAbsent("n" + size, k -> new Font(FAM, Font.PLAIN, 12).deriveFont(size));
    }

    /** Testo "semibold" (Segoe UI Semibold su Windows). */
    static Font fb(float size) {
        return CACHE.computeIfAbsent("b" + size, k -> SEMIBOLD
                ? new Font("Segoe UI Semibold", Font.PLAIN, 12).deriveFont(size)
                : new Font(FAM, Font.BOLD, 12).deriveFont(size));
    }
}
