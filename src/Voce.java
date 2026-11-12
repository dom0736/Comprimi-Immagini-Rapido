import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Un'immagine nell'elenco: un file su disco oppure uno screenshot incollato (solo in memoria). */
final class Voce {
    final Path file;            // null se incollata
    final BufferedImage mem;    // non null solo se incollata
    final String nome;
    long orig;                  // peso originale in byte
    Rectangle crop;             // ritaglio (coordinate dell'immagine)
    long out = -1;              // peso compresso stimato dall'anteprima
    int larg, alt;              // dimensioni originali (note dopo il caricamento)
    BufferedImage mini;         // miniatura quadrata già pronta
    String errore;              // se non si riesce a leggerla
    Path uscita;                // file creato con «Comprimi tutto» (si può rifare senza duplicare)

    Voce(Path file) {
        this.file = file;
        this.mem = null;
        this.nome = file.getFileName().toString();
    }

    Voce(BufferedImage mem, String nome) {
        this.file = null;
        this.mem = mem;
        this.nome = nome;
        this.larg = mem.getWidth();
        this.alt = mem.getHeight();
    }

    boolean incollata() {
        return mem != null;
    }
}
