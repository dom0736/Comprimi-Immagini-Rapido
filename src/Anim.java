import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * Motore delle animazioni: un solo timer a ~60 fps che parte quando serve e si ferma
 * quando non c'è più nulla da animare (a riposo l'app non consuma CPU).
 * Va usato solo dal thread grafico (EDT).
 */
final class Anim {
    private Anim() {
    }

    static final DoubleUnaryOperator LINEARE = t -> t;
    static final DoubleUnaryOperator OUT_CUBIC = t -> 1 - Math.pow(1 - t, 3);
    static final DoubleUnaryOperator OUT_QUINT = t -> 1 - Math.pow(1 - t, 5);
    static final DoubleUnaryOperator IN_OUT = t -> t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    static final DoubleUnaryOperator OUT_BACK = t -> {
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    };

    /** Se false tutto scatta subito, senza movimento (impostazione «Animazioni»). */
    static boolean attive = Cfg.bool("animazioni", false);

    private static final List<Val> VALORI = new ArrayList<>();
    private static final List<BooleanSupplier> CICLI = new ArrayList<>();
    private static final Timer TIMER = new Timer(16, e -> tick());

    static {
        TIMER.setCoalesce(true);
    }

    static long ora() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Numero che si muove dolcemente verso un valore. */
    static final class Val {
        double cur, da, a;
        long t0;
        int durata;
        boolean inCorso;
        DoubleUnaryOperator easing = OUT_CUBIC;
        final Runnable cambio;
        Runnable fine;

        Val(double iniziale, Runnable cambio) {
            this.cur = this.da = this.a = iniziale;
            this.cambio = cambio;
        }

        double get() {
            return cur;
        }

        double target() {
            return a;
        }

        Val ease(DoubleUnaryOperator e) {
            easing = e;
            return this;
        }

        /** Va verso `v` in `ms` millisecondi. */
        Val to(double v, int ms) {
            return to(v, ms, null);
        }

        Val to(double v, int ms, Runnable alTermine) {
            fine = alTermine;
            if (ms <= 0 || !attive) {
                jump(v);
                if (alTermine != null) alTermine.run();
                return this;
            }
            da = cur;
            a = v;
            t0 = ora();
            durata = ms;
            if (!inCorso) {
                inCorso = true;
                VALORI.add(this);
            }
            avvia();
            return this;
        }

        /** Salta subito al valore. */
        Val jump(double v) {
            cur = da = a = v;
            inCorso = false;
            VALORI.remove(this);
            if (cambio != null) cambio.run();
            return this;
        }
    }

    private static void avvia() {
        if (!TIMER.isRunning()) TIMER.start();
    }

    /** Anima finché `passo` restituisce true (per effetti che si ripetono, es. un bagliore che pulsa). */
    static void ciclo(BooleanSupplier passo) {
        CICLI.add(passo);
        avvia();
    }

    /** Fa partire `r` fra `ms` millisecondi (sul thread grafico). */
    static void dopo(int ms, Runnable r) {
        pausa(attive ? ms : 1, r);
    }

    /** Come `dopo`, ma aspetta sempre: per le pause che servono davvero, non solo all'effetto. */
    static void pausa(int ms, Runnable r) {
        Timer t = new Timer(Math.max(1, ms), e -> r.run());
        t.setRepeats(false);
        t.start();
    }

    private static void tick() {
        long t = ora();
        for (Val v : new ArrayList<>(VALORI)) {
            if (!v.inCorso) continue;
            double p = v.durata <= 0 ? 1 : Math.min(1, Math.max(0, (t - v.t0) / (double) v.durata));
            v.cur = p >= 1 ? v.a : v.da + (v.a - v.da) * v.easing.applyAsDouble(p);
            if (p >= 1) {
                v.inCorso = false;
                VALORI.remove(v);
            }
            if (v.cambio != null) v.cambio.run();
            if (p >= 1 && v.fine != null) {
                Runnable f = v.fine;
                v.fine = null;
                f.run();
            }
        }
        for (BooleanSupplier c : new ArrayList<>(CICLI)) {
            boolean continua;
            try {
                continua = c.getAsBoolean();
            } catch (RuntimeException e) {
                continua = false;
                Cfg.err("ciclo animazione", e);
            }
            if (!continua) CICLI.remove(c);
        }
        if (VALORI.isEmpty() && CICLI.isEmpty()) TIMER.stop();
    }
}
