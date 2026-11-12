import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;

/**
 * Comprimi Immagini Rapido - comprime, ridimensiona e ritaglia le immagini con anteprima prima/dopo.
 * Fatto con cuore da Domenico Castaldo.
 */
public final class ComprimiImmaginiRapido {
    private ComprimiImmaginiRapido() {
    }

    public static void main(String[] args) {
        Cfg.carica();
        Finestra[] finestra = new Finestra[1];
        if (!Cfg.istanzaUnica(() -> {
            if (finestra[0] != null) finestra[0].portaInPrimoPiano();
        })) {
            return; // era già aperto: l'ho portato in primo piano
        }
        Cfg.pulisci();
        Cfg.info("avvio " + Cfg.APP + " " + Cfg.VERSION + " (Java " + System.getProperty("java.version") + ")");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Cfg.err("eccezione non gestita in " + t.getName(), e);
            SwingUtilities.invokeLater(() -> {
                if (finestra[0] != null) finestra[0].msg("Qualcosa è andato storto. Se si ripete: Impostazioni → Segnala un problema", false);
            });
        });
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            ToolTipManager.sharedInstance().setInitialDelay(500);
            Tema.applicaTooltip();
            Finestra f = new Finestra();
            finestra[0] = f;
            f.setVisible(true);
            TitoloScuro.applica(f);
            f.avvia();
        });
    }
}
