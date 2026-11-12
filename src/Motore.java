import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/** Lettura, ridimensionamento, ritaglio e compressione. Nessun file temporaneo: tutto in memoria. */
final class Motore {
    private Motore() {
    }

    static final String[] EXT = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".tif", ".tiff"};

    static {
        ImageIO.setUseCache(false); // niente file temporanei di ImageIO
    }

    static boolean isImmagine(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : EXT) if (n.endsWith(e)) return true;
        return false;
    }

    static String nomeBase(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    static String peso(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format(Locale.ITALY, "%.0f KB", b / 1024.0);
        return String.format(Locale.ITALY, "%.2f MB", b / 1048576.0);
    }

    // ---------------------------------------------------------------- lettura
    static BufferedImage leggi(Path p) throws IOException {
        BufferedImage im = ImageIO.read(p.toFile());
        if (im == null) throw new IOException("formato non riconosciuto");
        return orienta(im, exifOrientamento(p));
    }

    /** Miniatura veloce (legge solo una parte dei pixel nelle foto grandi). */
    static BufferedImage miniatura(Path p, int max) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) throw new IOException("formato non riconosciuto");
            ImageReader r = it.next();
            try {
                r.setInput(in, true, true);
                int w = r.getWidth(0), h = r.getHeight(0);
                int n = Math.max(1, Math.min(w, h) / (max * 2));
                ImageReadParam prm = r.getDefaultReadParam();
                if (n > 1) prm.setSourceSubsampling(n, n, 0, 0);
                BufferedImage im = r.read(0, prm);
                return orienta(im, exifOrientamento(p));
            } finally {
                r.dispose();
            }
        }
    }

    /** Valore EXIF «Orientation» (1..8) di un JPEG; 1 se non c'è. Le foto del telefono ne hanno bisogno. */
    static int exifOrientamento(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(n.endsWith(".jpg") || n.endsWith(".jpeg"))) return 1;
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = in.readNBytes(131072);
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
            int i = 2;
            while (i + 4 < b.length) {
                if ((b[i] & 0xFF) != 0xFF) return 1;
                int m = b[i + 1] & 0xFF;
                if (m == 0xFF) {
                    i++;
                    continue;
                }
                int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (m == 0xE1 && i + 10 < b.length && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                    int t = i + 10;
                    boolean le = b[t] == 'I';
                    int ifd = t + u32(b, t + 4, le);
                    if (ifd < 0 || ifd + 2 > b.length) return 1;
                    int cnt = u16(b, ifd, le);
                    for (int k = 0; k < cnt; k++) {
                        int e = ifd + 2 + k * 12;
                        if (e + 12 > b.length) return 1;
                        if (u16(b, e, le) == 0x0112) {
                            int v = u16(b, e + 8, le);
                            return v >= 1 && v <= 8 ? v : 1;
                        }
                    }
                    return 1;
                }
                if (m == 0xDA || m == 0xD9) return 1;
                i += 2 + len;
            }
        } catch (Exception e) {
            // se non si riesce a leggere l'EXIF, la foto resta com'è
        }
        return 1;
    }

    private static int u16(byte[] b, int o, boolean le) {
        return le ? (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) : ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int u32(byte[] b, int o, boolean le) {
        return le ? u16(b, o, true) | (u16(b, o + 2, true) << 16) : (u16(b, o, false) << 16) | u16(b, o + 2, false);
    }

    static BufferedImage orienta(BufferedImage s, int o) {
        if (o <= 1 || o > 8) return s;
        int w = s.getWidth(), h = s.getHeight();
        boolean scambia = o >= 5;
        AffineTransform t = new AffineTransform();
        switch (o) {
            case 2: t.setTransform(-1, 0, 0, 1, w, 0); break;
            case 3: t.setTransform(-1, 0, 0, -1, w, h); break;
            case 4: t.setTransform(1, 0, 0, -1, 0, h); break;
            case 5: t.setTransform(0, 1, 1, 0, 0, 0); break;
            case 6: t.setTransform(0, 1, -1, 0, h, 0); break;
            case 7: t.setTransform(0, -1, -1, 0, h, w); break;
            default: t.setTransform(0, -1, 1, 0, 0, w); break;
        }
        BufferedImage out = new BufferedImage(scambia ? h : w, scambia ? w : h,
                s.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(s, t, null);
        g.dispose();
        return out;
    }

    // ---------------------------------------------------------------- elaborazione
    /** Porta l'immagine in RGB; le trasparenze diventano bianco (il JPG non le ha). */
    static BufferedImage rgb(BufferedImage s) {
        if (s.getType() == BufferedImage.TYPE_INT_RGB) return s;
        BufferedImage o = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, o.getWidth(), o.getHeight());
        g.drawImage(s, 0, 0, null);
        g.dispose();
        return o;
    }

    static Rectangle ritaglioValido(BufferedImage src, Rectangle crop) {
        if (crop == null) return null;
        Rectangle r = crop.intersection(new Rectangle(0, 0, src.getWidth(), src.getHeight()));
        return r.width >= 2 && r.height >= 2 ? r : null;
    }

    static BufferedImage regione(BufferedImage src, Rectangle crop) {
        Rectangle r = ritaglioValido(src, crop);
        return r == null ? src : src.getSubimage(r.x, r.y, r.width, r.height);
    }

    static final String[] FORMATI = {"JPEG", "PNG", "PNG 256"};
    static String[] note() {
        return new String[]{mozDisponibile() ? "Foto, con MozJPEG: il più leggero" : "Foto: piccolo e va ovunque",
                "Senza perdita, con trasparenze", "Max 256 colori: grafica e screenshot"};
    }
    static final String[] ESTENSIONI = {".jpg", ".png", ".png"};

    /** Ritaglia, ridimensiona e codifica in JPEG. */
    static byte[] render(BufferedImage src, Rectangle crop, int pct, int q, boolean progressivo) throws IOException {
        return render(src, crop, pct, q, progressivo, 0);
    }

    /** Ritaglia, ridimensiona e codifica: formato 0 = JPEG, 1 = PNG, 2 = PNG a 256 colori. */
    static byte[] render(BufferedImage src, Rectangle crop, int pct, int q, boolean progressivo, int formato) throws IOException {
        BufferedImage img = regione(src, crop);
        int w = Math.max(1, Math.round(img.getWidth() * pct / 100f));
        int h = Math.max(1, Math.round(img.getHeight() * pct / 100f));
        if (formato == 0) img = rgb(img);
        else if (img.getType() != BufferedImage.TYPE_INT_ARGB && img.getType() != BufferedImage.TYPE_INT_RGB) img = argb(img);
        if (w != img.getWidth() || h != img.getHeight()) img = Gfx.scala(img, w, h);
        if (formato == 0) {
            BufferedImage rgb = rgb(img);
            if (mozDisponibile()) {
                try {
                    return mozjpeg(rgb, q, progressivo);
                } catch (IOException | RuntimeException e) {
                    Cfg.err("MozJPEG non ha funzionato, uso il JPEG normale", e);
                    mozGuasto = true;
                }
            }
            return jpeg(rgb, q, progressivo);
        }
        if (formato == 2) img = Quantizza.aColori(img, coloriDa(q));
        return png(img);
    }

    /** Qualità 0..100 → colori del PNG 256: 0 = 16 colori, 100 = 256 (crescita esponenziale: i passi sembrano uniformi all'occhio). */
    static int coloriDa(int q) {
        double t = Math.max(0, Math.min(1, q / 100.0));
        return (int) Math.round(Math.pow(2, 4 + 4 * t));
    }

    // ---------------------------------------------------------------- MozJPEG (programma esterno cjpeg, se presente in "tools")
    private static Path moz;
    private static boolean mozCercato, mozGuasto;

    private static synchronized Path moz() {
        if (mozGuasto) return null;
        if (!mozCercato) {
            mozCercato = true;
            java.util.List<Path> dirs = new java.util.ArrayList<>();
            String sp = System.getProperty("cir.tools");
            if (sp != null) dirs.add(Path.of(sp));
            try {
                Path jar = Path.of(Motore.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path base = Files.isDirectory(jar) ? jar : jar.getParent();
                if (base != null) {
                    dirs.add(base.resolve("tools"));
                    if (base.getParent() != null) dirs.add(base.getParent().resolve("tools"));
                }
            } catch (Exception ignored) {
            }
            outer:
            for (Path d : dirs) {
                for (String n : new String[]{"cjpeg.exe", "cjpeg", "cjpeg-static"}) {
                    Path c = d.resolve(n);
                    if (Files.isRegularFile(c) && (n.endsWith(".exe") || Files.isExecutable(c))) {
                        moz = c;
                        break outer;
                    }
                }
            }
            Cfg.info(moz != null ? "MozJPEG trovato: " + moz : "MozJPEG non presente: uso il JPEG di Java");
        }
        return moz;
    }

    static boolean mozDisponibile() {
        return moz() != null;
    }

    /** Codifica con cjpeg (MozJPEG): l'immagine entra come PPM dallo standard input, il JPEG esce dallo standard output. */
    static byte[] mozjpeg(BufferedImage img, int q, boolean progressivo) throws IOException {
        Path exe = moz();
        if (exe == null) throw new IOException("cjpeg non trovato");
        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "-quality", String.valueOf(Math.max(1, Math.min(100, q))),
                progressivo ? "-progressive" : "-baseline");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        final int w = img.getWidth(), h = img.getHeight();
        Thread scrittore = new Thread(() -> {
            try (java.io.OutputStream os = new java.io.BufferedOutputStream(p.getOutputStream(), 1 << 16)) {
                os.write(("P6\n" + w + " " + h + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                int[] riga = new int[w];
                byte[] buf = new byte[w * 3];
                for (int y = 0; y < h; y++) {
                    img.getRGB(0, y, w, 1, riga, 0, w);
                    for (int x = 0, j = 0; x < w; x++) {
                        int c = riga[x];
                        buf[j++] = (byte) (c >> 16);
                        buf[j++] = (byte) (c >> 8);
                        buf[j++] = (byte) c;
                    }
                    os.write(buf);
                }
            } catch (IOException ignored) {
                // se cjpeg si chiude prima, lo si vede dal risultato
            }
        }, "moz-in");
        scrittore.setDaemon(true);
        scrittore.start();
        byte[] out;
        try (InputStream is = p.getInputStream()) {
            out = is.readAllBytes();
        }
        try {
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("cjpeg troppo lento");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrotto");
        }
        if (p.exitValue() > 2 || out.length < 4 || (out[0] & 255) != 0xFF || (out[1] & 255) != 0xD8) {
            throw new IOException("cjpeg ha restituito un errore (" + p.exitValue() + ")");
        }
        return out;
    }

    static BufferedImage argb(BufferedImage s) {
        BufferedImage o = new BufferedImage(s.getWidth(), s.getHeight(), s.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(s, 0, 0, null);
        g.dispose();
        return o;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("png").next();
        try {
            ImageWriteParam pr = w.getDefaultWriteParam();
            if (pr.canWriteCompressed()) {
                pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                pr.setCompressionQuality(0f); // 0 = deflate al massimo
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bo)) {
                w.setOutput(ios);
                w.write(null, new IIOImage(img, null, null), pr);
            }
            return bo.toByteArray();
        } finally {
            w.dispose();
        }
    }

    static byte[] jpeg(BufferedImage img, int q, boolean progressivo) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            JPEGImageWriteParam pr = (JPEGImageWriteParam) w.getDefaultWriteParam();
            pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            pr.setCompressionQuality(Math.max(1, Math.min(100, q)) / 100f);
            pr.setProgressiveMode(progressivo ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
            pr.setOptimizeHuffmanTables(true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bo)) {
                w.setOutput(ios);
                w.write(null, new IIOImage(img, null, null), pr);
            }
            return bo.toByteArray();
        } finally {
            w.dispose();
        }
    }

    static BufferedImage decodifica(byte[] d) throws IOException {
        BufferedImage im = ImageIO.read(new ByteArrayInputStream(d));
        if (im == null) throw new IOException("anteprima non leggibile");
        return im;
    }

    /** Peso che avrebbe l'immagine salvata come PNG (serve per gli screenshot incollati). */
    static long pesoPng(BufferedImage im) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(im, "png", bo);
            return bo.size();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Versione ridotta (lato massimo `max`), mai ingrandita. */
    static BufferedImage ridotta(BufferedImage s, int max) {
        int w = s.getWidth(), h = s.getHeight();
        if (w <= max && h <= max) return s;
        double k = Math.min((double) max / w, (double) max / h);
        return Gfx.scala(s, Math.max(1, (int) Math.round(w * k)), Math.max(1, (int) Math.round(h * k)));
    }
}
