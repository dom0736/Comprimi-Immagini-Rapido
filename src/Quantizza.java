import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Riduce un'immagine a 256 colori (median cut + diffusione dell'errore): PNG molto più leggeri, trasparenza compresa. */
final class Quantizza {
    private Quantizza() {
    }

    private static final class Scatola {
        int[] bins;
        int n;
        long peso;

        Scatola(int[] bins, int n, long peso) {
            this.bins = bins;
            this.n = n;
            this.peso = peso;
        }
    }

    static BufferedImage a256(BufferedImage src) {
        final int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        boolean trasp = false;
        long[] cnt = new long[32768], sr = new long[32768], sg = new long[32768], sb = new long[32768];
        for (int p : px) {
            int a = p >>> 24;
            if (a < 128) {
                trasp = true;
                continue;
            }
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int k = (r >> 3) << 10 | (g >> 3) << 5 | (b >> 3);
            cnt[k]++;
            sr[k] += r;
            sg[k] += g;
            sb[k] += b;
        }
        int max = trasp ? 255 : 256;
        int nb = 0;
        for (int k = 0; k < 32768; k++) if (cnt[k] > 0) nb++;
        int[] tutti = new int[nb];
        long tot = 0;
        for (int k = 0, j = 0; k < 32768; k++) if (cnt[k] > 0) {
            tutti[j++] = k;
            tot += cnt[k];
        }
        List<Scatola> sc = new ArrayList<>();
        sc.add(new Scatola(tutti, nb, tot));
        while (sc.size() < max) {
            Scatola best = null;
            for (Scatola s : sc) if (s.n > 1 && (best == null || s.peso > best.peso)) best = s;
            if (best == null) break;
            sc.remove(best);
            int rmin = 31, rmax = 0, gmin = 31, gmax = 0, bmin = 31, bmax = 0;
            for (int i = 0; i < best.n; i++) {
                int k = best.bins[i];
                int r = k >> 10, g = (k >> 5) & 31, b = k & 31;
                rmin = Math.min(rmin, r); rmax = Math.max(rmax, r);
                gmin = Math.min(gmin, g); gmax = Math.max(gmax, g);
                bmin = Math.min(bmin, b); bmax = Math.max(bmax, b);
            }
            int dr = (rmax - rmin) * 3, dg = (gmax - gmin) * 4, db = (bmax - bmin) * 2; // l'occhio vede meglio il verde
            final int canale = dr >= dg && dr >= db ? 0 : dg >= db ? 1 : 2;
            Integer[] ord = new Integer[best.n];
            for (int i = 0; i < best.n; i++) ord[i] = best.bins[i];
            Arrays.sort(ord, (x, y) -> Integer.compare(c(x, canale), c(y, canale)));
            long meta = best.peso / 2, acc = 0;
            int taglio = 1;
            for (int i = 0; i < ord.length; i++) {
                acc += cnt[ord[i]];
                if (acc >= meta) {
                    taglio = Math.max(1, Math.min(ord.length - 1, i + 1));
                    break;
                }
            }
            int[] a = new int[taglio], b = new int[ord.length - taglio];
            long pa = 0, pb = 0;
            for (int i = 0; i < ord.length; i++) {
                if (i < taglio) {
                    a[i] = ord[i];
                    pa += cnt[ord[i]];
                } else {
                    b[i - taglio] = ord[i];
                    pb += cnt[ord[i]];
                }
            }
            sc.add(new Scatola(a, a.length, pa));
            sc.add(new Scatola(b, b.length, pb));
        }
        int n = sc.size() + (trasp ? 1 : 0), off = trasp ? 1 : 0;
        byte[] R = new byte[Math.max(n, 2)], G = new byte[R.length], B = new byte[R.length], A = new byte[R.length];
        Arrays.fill(A, (byte) 255);
        if (trasp) A[0] = 0;
        int[] pr = new int[n], pg = new int[n], pbl = new int[n];
        for (int i = 0; i < sc.size(); i++) {
            Scatola s = sc.get(i);
            long c = 0, r = 0, g = 0, b = 0;
            for (int j = 0; j < s.n; j++) {
                int k = s.bins[j];
                c += cnt[k];
                r += sr[k];
                g += sg[k];
                b += sb[k];
            }
            c = Math.max(1, c);
            pr[i + off] = (int) (r / c);
            pg[i + off] = (int) (g / c);
            pbl[i + off] = (int) (b / c);
            R[i + off] = (byte) pr[i + off];
            G[i + off] = (byte) pg[i + off];
            B[i + off] = (byte) pbl[i + off];
        }
        // colore più vicino per ognuna delle 32768 celle
        byte[] vicino = new byte[32768];
        for (int k = 0; k < 32768; k++) {
            int r = (k >> 10) << 3 | 4, g = ((k >> 5) & 31) << 3 | 4, b = (k & 31) << 3 | 4;
            int mi = off, md = Integer.MAX_VALUE;
            for (int i = off; i < n; i++) {
                int d = d2(r - pr[i], g - pg[i], b - pbl[i]);
                if (d < md) {
                    md = d;
                    mi = i;
                }
            }
            vicino[k] = (byte) mi;
        }
        IndexColorModel icm = new IndexColorModel(8, R.length, R, G, B, A);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
        byte[] dati = ((java.awt.image.DataBufferByte) out.getRaster().getDataBuffer()).getData();
        // Floyd-Steinberg (forza 0.75: meno rumore, file più piccoli)
        float[][] err = new float[2][(w + 2) * 3];
        for (int y = 0; y < h; y++) {
            float[] e0 = err[y & 1], e1 = err[(y + 1) & 1];
            Arrays.fill(e1, 0f);
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                if ((p >>> 24) < 128) {
                    dati[y * w + x] = 0;
                    continue;
                }
                int r = clamp(((p >> 16) & 255) + Math.round(e0[(x + 1) * 3])),
                        g = clamp(((p >> 8) & 255) + Math.round(e0[(x + 1) * 3 + 1])),
                        b = clamp((p & 255) + Math.round(e0[(x + 1) * 3 + 2]));
                int idx = vicino[(r >> 3) << 10 | (g >> 3) << 5 | (b >> 3)] & 255;
                dati[y * w + x] = (byte) idx;
                float er = (r - pr[idx]) * .75f, eg = (g - pg[idx]) * .75f, eb = (b - pbl[idx]) * .75f;
                add(e0, x + 2, er * 7 / 16f, eg * 7 / 16f, eb * 7 / 16f);
                add(e1, x, er * 3 / 16f, eg * 3 / 16f, eb * 3 / 16f);
                add(e1, x + 1, er * 5 / 16f, eg * 5 / 16f, eb * 5 / 16f);
                add(e1, x + 2, er / 16f, eg / 16f, eb / 16f);
            }
        }
        return out;
    }

    private static void add(float[] e, int i, float r, float g, float b) {
        e[i * 3] += r;
        e[i * 3 + 1] += g;
        e[i * 3 + 2] += b;
    }

    private static int c(int k, int canale) {
        return canale == 0 ? k >> 10 : canale == 1 ? (k >> 5) & 31 : k & 31;
    }

    private static int d2(int a, int b, int c) {
        return 3 * a * a + 4 * b * b + 2 * c * c;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
