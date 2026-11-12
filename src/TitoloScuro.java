import java.awt.Color;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import javax.swing.JFrame;

/**
 * Colora la barra del titolo di Windows come il resto della finestra (scura o chiara), come nel Registratore Azioni.
 * Usa le API native di Windows (DWM); se qualcosa non va, la barra resta com'è: nessun errore visibile.
 */
final class TitoloScuro {
    private TitoloScuro() {
    }

    static void applica(JFrame f) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win") || !f.isDisplayable()) return;
        try {
            Tema.Pal p = Tema.p;
            Linker linker = Linker.nativeLinker();
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
                SymbolLookup dwm = SymbolLookup.libraryLookup("dwmapi", arena);
                MethodHandle find = linker.downcallHandle(user32.find("FindWindowW").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MethodHandle set = linker.downcallHandle(dwm.find("DwmSetWindowAttribute").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                MemorySegment hwnd = (MemorySegment) find.invoke(wide(arena, "SunAwtFrame"), wide(arena, f.getTitle()));
                if (hwnd.equals(MemorySegment.NULL)) return;
                imposta(set, arena, hwnd, 20, p.scuro ? 1 : 0);                 // barra scura (Windows 10/11)
                imposta(set, arena, hwnd, 19, p.scuro ? 1 : 0);                 // vecchie versioni di Windows 10
                imposta(set, arena, hwnd, 35, colorref(p.grad[0]));             // colore barra (Windows 11)
                imposta(set, arena, hwnd, 36, colorref(p.fg));                  // colore del testo (Windows 11)
                imposta(set, arena, hwnd, 34, colorref(p.grad[0]));             // bordo (Windows 11)
            }
        } catch (Throwable e) {
            Cfg.info("barra del titolo: colore non applicato (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static int colorref(Color c) {
        return (c.getBlue() << 16) | (c.getGreen() << 8) | c.getRed();
    }

    private static MemorySegment wide(Arena a, String s) {
        MemorySegment m = a.allocate((s.length() + 1) * 2L, 2);
        for (int i = 0; i < s.length(); i++) m.setAtIndex(ValueLayout.JAVA_CHAR, i, s.charAt(i));
        m.setAtIndex(ValueLayout.JAVA_CHAR, s.length(), '\0');
        return m;
    }

    private static void imposta(MethodHandle set, Arena a, MemorySegment hwnd, int attr, int valore) throws Throwable {
        MemorySegment v = a.allocate(4, 4);
        v.set(ValueLayout.JAVA_INT, 0, valore);
        int r = (int) set.invoke(hwnd, attr, v, 4);
    }
}
