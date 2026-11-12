import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * Crea icon.ico (16-256 px) a partire da icon.png. Uso: java tools/CreaIcona.java assets/icon.png build/icon.ico
 * Cambiare l'icona del programma = sostituire assets/icon.png con un PNG quadrato (meglio 512x512).
 */
public class CreaIcona {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        if (src == null) throw new IllegalArgumentException("icon.png non è un'immagine valida");
        int[] misure = {16, 24, 32, 48, 64, 128, 256};
        byte[][] png = new byte[misure.length][];
        for (int i = 0; i < misure.length; i++) {
            int s = misure[i];
            BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, s, s, null);
            g.dispose();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(b, "png", bo);
            png[i] = bo.toByteArray();
        }
        int offset = 6 + 16 * misure.length, tot = offset;
        for (byte[] p : png) tot += p.length;
        ByteBuffer o = ByteBuffer.allocate(tot).order(ByteOrder.LITTLE_ENDIAN);
        o.putShort((short) 0).putShort((short) 1).putShort((short) misure.length);
        for (int i = 0; i < misure.length; i++) {
            int s = misure[i];
            o.put((byte) (s == 256 ? 0 : s)).put((byte) (s == 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            o.putShort((short) 1).putShort((short) 32).putInt(png[i].length).putInt(offset);
            offset += png[i].length;
        }
        for (byte[] p : png) o.put(p);
        File dest = new File(args[1]);
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        Files.write(dest.toPath(), o.array());
    }
}
