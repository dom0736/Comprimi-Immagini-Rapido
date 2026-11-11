"""
Comprimi Immagini Rapido - comprime, ridimensiona e ritaglia immagini con anteprima prima/dopo.

Trascini le foto (o intere cartelle), regoli qualita' e dimensione, premi Inizia.
Un solo file, una sola finestra, nessun popup. Gli originali non vengono mai toccati.
Niente file in giro: le impostazioni stanno in %APPDATA%\\ComprimiImmaginiRapido, i risultati
nella cartella scelta (creata solo al primo salvataggio), nessun file temporaneo.
"""
import ctypes
import datetime
import io
import json
import logging
import logging.handlers
import math
import os
import platform
import queue
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import webbrowser
from functools import lru_cache
from ctypes import wintypes
from pathlib import Path

import tkinter as tk
import tkinter.font as tkfont
from tkinter import filedialog

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps

APP = "Comprimi Immagini Rapido"
VERSION = "2.0"
EMAIL = "domenico.castaldod29@gmail.com"
IS_WIN = os.name == "nt"

# Coordinate del mouse e pixel dello schermo devono coincidere (schermi HiDPI)
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass
try:
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("ComprimiImmaginiRapido.App")
except Exception:
    pass

Image.MAX_IMAGE_PIXELS = 400_000_000  # oltre questo limite l'immagine viene rifiutata con un messaggio

EST_OK = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tif", ".tiff"}
FORMATI = {"JPG": ("JPEG", ".jpg"), "WebP": ("WEBP", ".webp")}
QUAL_MIN, QUAL_MAX = 30, 95
DIM_MIN, DIM_MAX = 5, 100
MAX_FILE = 5000          # massimo di immagini aggiunte in un colpo solo
GIORNO = 86400
SUFFISSO_TMP = ".comprimi.tmp"
MARCHIO = "marchio_comprimi.txt"

_log = logging.getLogger("comprimi")
_log.addHandler(logging.NullHandler())


def risorsa(nome: str) -> Path:
    """File incluso nell'exe (o accanto allo script, quando si lancia da Python)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / nome


# --------------------------------------------------------------------------
# Configurazione (solo in %APPDATA%, mai accanto all'exe)
# --------------------------------------------------------------------------
CONFIG_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "ComprimiImmaginiRapido"
CONFIG_FILE = CONFIG_DIR / "config.json"
LOG_FILE = CONFIG_DIR / "log.txt"

CONFIG_DEFAULT = {
    "cartella_uscita": "",
    "qualita": 75,
    "dimensione": 20,
    "formato": "JPG",
    "extra": True,          # compressione extra: piu' lenta, file piu' piccoli
    "apri_a_fine": False,   # apre la cartella dei risultati quando l'elaborazione finisce
    "tema": "scuro",
}


def avvia_log() -> None:
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        h = logging.handlers.RotatingFileHandler(LOG_FILE, maxBytes=200_000, backupCount=1, encoding="utf-8")
        h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        _log.addHandler(h)
        _log.setLevel(logging.INFO)
    except Exception:
        pass


def unique_path(p: Path) -> Path:
    """Restituisce un percorso che non esiste ancora (aggiunge _2, _3, ...)."""
    p = Path(p)
    if not p.exists():
        return p
    i = 2
    while True:
        cand = p.with_name(f"{p.stem}_{i}{p.suffix}")
        if not cand.exists():
            return cand
        i += 1


def scrivi_testo_atomico(path: Path, testo: str) -> None:
    tmp = path.with_name(path.name + SUFFISSO_TMP)
    try:
        tmp.write_text(testo, encoding="utf-8")
        os.replace(tmp, path)
    except BaseException:
        try:
            tmp.unlink()
        except OSError:
            pass
        raise


def scrivi_byte_atomico(path: Path, dati: bytes) -> None:
    """Scrive in un temporaneo e poi lo rinomina. Non sovrascrive mai un file esistente."""
    tmp = path.with_name(path.name + SUFFISSO_TMP)
    try:
        with open(tmp, "wb") as f:
            f.write(dati)
        if path.exists():
            raise FileExistsError(str(path))
        os.replace(tmp, path)
    except BaseException:
        try:
            tmp.unlink()
        except OSError:
            pass
        raise


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def valida_config(cfg: dict) -> dict:
    def intero(k, lo, hi):
        try:
            return clamp(int(cfg.get(k, CONFIG_DEFAULT[k])), lo, hi)
        except (TypeError, ValueError):
            return CONFIG_DEFAULT[k]
    cfg["qualita"] = intero("qualita", QUAL_MIN, QUAL_MAX)
    cfg["dimensione"] = intero("dimensione", DIM_MIN, DIM_MAX)
    if cfg.get("formato") not in FORMATI:
        cfg["formato"] = "JPG"
    if cfg.get("tema") not in ("scuro", "chiaro"):
        cfg["tema"] = "scuro"
    for k in ("extra", "apri_a_fine"):
        cfg[k] = bool(cfg.get(k, CONFIG_DEFAULT[k]))
    if not isinstance(cfg.get("cartella_uscita"), str):
        cfg["cartella_uscita"] = ""
    return cfg


def carica_config() -> dict:
    cfg = dict(CONFIG_DEFAULT)
    try:
        dati = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        if isinstance(dati, dict):
            cfg.update({k: v for k, v in dati.items() if k in CONFIG_DEFAULT})
    except FileNotFoundError:
        pass
    except Exception:
        try:  # config rovinata: la metto da parte, non la cancello (ne tengo 3)
            CONFIG_FILE.rename(unique_path(CONFIG_FILE.with_name("config_rovinata.json")))
            vecchie = sorted(CONFIG_DIR.glob("config_rovinata*.json"), key=lambda f: f.stat().st_mtime)
            for f in vecchie[:-3]:
                f.unlink()
        except OSError:
            pass
    return valida_config(cfg)


def salva_config(cfg: dict) -> None:
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        scrivi_testo_atomico(CONFIG_FILE, json.dumps(cfg, ensure_ascii=False, indent=2))
    except OSError:
        pass


def cartella_uscita_default() -> Path:
    home = Path.home()
    for nome in ("Pictures", "Immagini", "Documents"):
        if (home / nome).is_dir():
            return home / nome / "Comprimi Immagini Rapido"
    return home / "Comprimi Immagini Rapido"


# ---- pulizia: niente resti che si accumulano nel tempo ----
_marchio_aperto = None


def togli_tmp(cartella: Path) -> None:
    """Elimina i temporanei di QUESTO programma rimasti da una chiusura anomala."""
    try:
        for f in Path(cartella).glob("*" + SUFFISSO_TMP):
            try:
                f.unlink()
            except OSError:
                pass
    except OSError:
        pass


def pulisci_temporanei() -> None:
    """L'exe si scompatta in una cartella _MEIxxxx dentro Temp. Se viene chiuso a forza la cartella resta:
    tolgo quelle vecchie lasciate da questo stesso programma (riconosciute dal marchio), mai quelle di
    altri programmi ne' quella in uso."""
    global _marchio_aperto
    meipass = getattr(sys, "_MEIPASS", None)
    if not meipass:
        return
    try:  # tengo il marchio aperto: finche' giro, nessun'altra copia puo' cancellare la mia cartella
        _marchio_aperto = open(risorsa(MARCHIO), "rb")
    except OSError:
        return
    ora = time.time()
    try:
        for d in Path(meipass).parent.glob("_MEI*"):
            try:
                if d == Path(meipass) or not d.is_dir() or ora - d.stat().st_mtime < GIORNO:
                    continue
                marchio, libero = d / MARCHIO, d / (MARCHIO + ".libero")
                if marchio.is_file():
                    os.replace(marchio, libero)  # fallisce se un'altra copia lo sta usando: non tocco nulla
                elif not libero.is_file():
                    continue
                shutil.rmtree(d, ignore_errors=True)
            except OSError:
                continue
    except OSError:
        pass


# --------------------------------------------------------------------------
# Motore: lettura, ritaglio, ridimensionamento, codifica
# --------------------------------------------------------------------------
def fmt_peso(n: int) -> str:
    if n < 1024:
        return f"{n} B"
    if n < 1024 ** 2:
        v = n / 1024
        return (f"{v:.0f}" if v >= 100 else f"{v:.1f}").replace(".", ",") + " KB"
    v = n / 1024 ** 2
    return f"{v:.1f}".replace(".", ",") + " MB"


def risparmio(orig: int, nuovo: int) -> int:
    """Percentuale risparmiata (negativa se il file e' diventato piu' grande)."""
    return 0 if orig <= 0 else int(round(100 - nuovo * 100 / orig))


def pulisci_nome(nome: str) -> str:
    nome = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", nome).strip(" .")
    if nome.upper() in {"CON", "PRN", "AUX", "NUL"} or re.fullmatch(r"(COM|LPT)\d", nome.upper() or ""):
        nome = "_" + nome
    return nome


def chiave_percorso(p) -> str:
    return os.path.normcase(os.path.abspath(str(p)))


def espandi_percorsi(percorsi, gia=(), massimo=MAX_FILE):
    """Da file e cartelle (anche annidate) ricava l'elenco delle immagini nuove. Restituisce (lista, troncato)."""
    visti = {chiave_percorso(p) for p in gia}
    out = []
    troncato = False

    def aggiungi(f: Path) -> bool:
        nonlocal troncato
        if f.suffix.lower() not in EST_OK:
            return True
        k = chiave_percorso(f)
        if k in visti:
            return True
        if len(out) >= massimo:
            troncato = True
            return False
        visti.add(k)
        out.append(f)
        return True

    for p in percorsi:
        try:
            p = Path(p)
            if p.is_dir():
                for radice, cartelle, files in os.walk(p):
                    cartelle[:] = sorted(c for c in cartelle if not c.startswith("."))
                    for n in sorted(files):
                        if not aggiungi(Path(radice) / n):
                            return out, troncato
            elif p.is_file():
                if not aggiungi(p):
                    return out, troncato
        except OSError:
            continue
    return out, troncato


def nome_uscita(cartella: Path, stem: str, ext: str, riservati: set) -> Path:
    """Nome libero nella cartella (nome.jpg, nome_2.jpg...) tenendo conto anche di quelli gia' assegnati."""
    stem = pulisci_nome(stem) or "immagine"
    cand = Path(cartella) / f"{stem}{ext}"
    i = 2
    while cand.exists() or str(cand).lower() in riservati:
        cand = Path(cartella) / f"{stem}_{i}{ext}"
        i += 1
    riservati.add(str(cand).lower())
    return cand


def apri_immagine(percorso) -> Image.Image:
    """Apre l'immagine per intero, con l'orientamento EXIF applicato, in RGB / RGBA / L."""
    with Image.open(percorso) as src:
        src.load()
        im = ImageOps.exif_transpose(src)
        if im is src:
            im = src.copy()
    if im.mode in ("I;16", "I;16L", "I;16B", "I"):
        try:
            im = im.point(lambda v: v * (1 / 256)).convert("L")
        except Exception:
            im = im.convert("RGB")
    elif im.mode not in ("RGB", "RGBA", "L"):
        con_alpha = "A" in im.mode or "transparency" in im.info
        im = im.convert("RGBA" if con_alpha else "RGB")
    return im


def dimensioni_orientate(percorso):
    """(larghezza, altezza) leggendo solo l'intestazione, con l'orientamento EXIF."""
    with Image.open(percorso) as im:
        w, h = im.size
        try:
            if im.getexif().get(0x0112) in (5, 6, 7, 8):
                w, h = h, w
        except Exception:
            pass
    return w, h


def a_rgb(im: Image.Image) -> Image.Image:
    """RGB senza trasparenza: lo sfondo trasparente diventa bianco."""
    if im.mode == "RGB":
        return im
    if im.mode in ("RGBA", "LA", "PA"):
        rgba = im.convert("RGBA")
        fondo = Image.new("RGB", rgba.size, (255, 255, 255))
        fondo.paste(rgba, mask=rgba.getchannel("A"))
        return fondo
    return im.convert("RGB")


def ritaglia(im: Image.Image, crop) -> Image.Image:
    if not crop:
        return im
    x0, y0, x1, y1 = crop
    x0, y0 = clamp(int(x0), 0, im.width - 1), clamp(int(y0), 0, im.height - 1)
    x1, y1 = clamp(int(x1), x0, im.width), clamp(int(y1), y0, im.height)
    if x1 - x0 < 2 or y1 - y0 < 2:
        return im
    return im.crop((x0, y0, x1, y1))


def codifica(im: Image.Image, fmt: str, q: int, extra: bool) -> bytes:
    buf = io.BytesIO()
    if fmt == "WebP":
        if im.mode not in ("RGB", "RGBA"):
            im = im.convert("RGBA" if "A" in im.mode else "RGB")
        im.save(buf, "WEBP", quality=q, method=6 if extra else 4)
    else:
        im = a_rgb(im)
        im.save(buf, "JPEG", quality=q, optimize=True, progressive=bool(extra),
                subsampling="4:4:4" if q >= 90 else "4:2:0")
    return buf.getvalue()


def elabora(im: Image.Image, crop, pct: int, q: int, fmt: str, extra: bool):
    """Ritaglia, ridimensiona e codifica. Restituisce (byte, (larghezza, altezza))."""
    base = ritaglia(im, crop)
    w, h = max(1, round(base.width * pct / 100)), max(1, round(base.height * pct / 100))
    if (w, h) != base.size:
        base = base.resize((w, h), Image.Resampling.LANCZOS, reducing_gap=3.0)
    return codifica(base, fmt, q, extra), base.size


def messaggio_errore(e: BaseException) -> str:
    if isinstance(e, Image.DecompressionBombError):
        return "Immagine troppo grande"
    if isinstance(e, (Image.UnidentifiedImageError,)):
        return "Formato non riconosciuto"
    if isinstance(e, FileExistsError):
        return "Il file esiste già"
    if isinstance(e, PermissionError):
        return "Accesso negato"
    if isinstance(e, FileNotFoundError):
        return "File non trovato"
    if isinstance(e, MemoryError):
        return "Memoria insufficiente"
    if isinstance(e, ValueError) and "dimension" in str(e).lower():
        return "Troppo grande per questo formato"
    if isinstance(e, OSError):
        return "Errore di lettura o scrittura"
    return "Errore imprevisto"


# --------------------------------------------------------------------------
# Windows: finestra, appunti, trascinamento file, istanza unica
# --------------------------------------------------------------------------
def hwnd_finestra(widget) -> int:
    """Finestra di primo livello (quella vera di Windows) di un widget Tk."""
    try:
        u = ctypes.windll.user32
        u.GetAncestor.restype = ctypes.c_void_p
        u.GetAncestor.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        h = int(widget.winfo_id())
        return int(u.GetAncestor(h, 2) or h)  # 2 = GA_ROOT
    except Exception:
        return 0


def colora_titolo(hwnd: int, sfondo, testo, scuro: bool) -> None:
    """Barra del titolo di Windows nello stesso colore dell'app (Windows 11; su Windows 10 almeno scura)."""
    try:
        dwm = ctypes.windll.dwmapi

        def attr(n, v):
            val = ctypes.c_int(v)
            return dwm.DwmSetWindowAttribute(wintypes.HWND(hwnd), n, ctypes.byref(val), 4)

        if attr(20, 1 if scuro else 0) != 0:
            attr(19, 1 if scuro else 0)
        rgb = lambda c: c[0] | (c[1] << 8) | (c[2] << 16)
        attr(35, rgb(sfondo))
        attr(34, rgb(sfondo))
        attr(36, rgb(testo))
    except Exception:
        pass


def _appunti(formati, hwnd: int) -> bool:
    """Mette uno o piu' formati (id, byte) negli appunti. Serve una finestra vera come proprietaria."""
    if not IS_WIN or not hwnd:
        return False
    try:
        u, k = ctypes.windll.user32, ctypes.windll.kernel32
        k.GlobalAlloc.restype = ctypes.c_void_p
        k.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
        k.GlobalLock.restype = ctypes.c_void_p
        k.GlobalLock.argtypes = [ctypes.c_void_p]
        k.GlobalUnlock.argtypes = [ctypes.c_void_p]
        k.GlobalFree.argtypes = [ctypes.c_void_p]
        u.OpenClipboard.argtypes = [ctypes.c_void_p]
        u.OpenClipboard.restype = wintypes.BOOL
        u.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
        u.SetClipboardData.restype = ctypes.c_void_p
        for _ in range(20):
            if u.OpenClipboard(ctypes.c_void_p(hwnd)):
                break
            time.sleep(0.05)
        else:
            return False
        try:
            u.EmptyClipboard()
            for fmt, dati in formati:
                h = k.GlobalAlloc(0x0002, len(dati))  # GMEM_MOVEABLE
                if not h:
                    return False
                p = k.GlobalLock(h)
                if not p:
                    k.GlobalFree(h)
                    return False
                ctypes.memmove(p, dati, len(dati))
                k.GlobalUnlock(h)
                if not u.SetClipboardData(fmt, h):
                    k.GlobalFree(h)
                    return False
            return True
        finally:
            u.CloseClipboard()
    except Exception:
        _log.exception("Appunti")
        return False


def dati_copia_file(percorsi) -> bytes:
    """Struttura DROPFILES (CF_HDROP): e' quello che Esplora risorse si aspetta per 'Copia'."""
    testo = ("\0".join(str(p) for p in percorsi) + "\0\0").encode("utf-16-le")
    return struct.pack("<IiiII", 20, 0, 0, 0, 1) + testo


def copia_file_negli_appunti(percorsi, hwnd: int) -> bool:
    return _appunti([(15, dati_copia_file(percorsi))], hwnd)  # 15 = CF_HDROP


def copia_immagine_negli_appunti(im: Image.Image, hwnd: int) -> bool:
    buf = io.BytesIO()
    a_rgb(im).save(buf, "BMP")
    return _appunti([(8, buf.getvalue()[14:])], hwnd)  # 8 = CF_DIB (senza l'intestazione del file BMP)


_DROP_PROC = None
_DROP_OLD = None


def attiva_drop(hwnd: int, callback) -> bool:
    """Accetta file e cartelle trascinati sulla finestra (senza librerie esterne)."""
    global _DROP_PROC, _DROP_OLD
    if not IS_WIN or not hwnd:
        return False
    try:
        u, s = ctypes.windll.user32, ctypes.windll.shell32
        proc_t = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t)
        u.SetWindowLongPtrW.restype = ctypes.c_void_p
        u.SetWindowLongPtrW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
        u.CallWindowProcW.restype = ctypes.c_ssize_t
        u.CallWindowProcW.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
        u.ChangeWindowMessageFilterEx.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint, ctypes.c_void_p]
        s.DragQueryFileW.restype = ctypes.c_uint
        s.DragQueryFileW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_wchar_p, ctypes.c_uint]
        s.DragFinish.argtypes = [ctypes.c_void_p]
        s.DragAcceptFiles.argtypes = [ctypes.c_void_p, wintypes.BOOL]

        def proc(h, msg, wp, lp):
            if msg == 0x0233:  # WM_DROPFILES
                try:
                    hdrop = ctypes.c_void_p(wp)
                    n = s.DragQueryFileW(hdrop, 0xFFFFFFFF, None, 0)
                    lista = []
                    for i in range(n):
                        lung = s.DragQueryFileW(hdrop, i, None, 0)
                        buf = ctypes.create_unicode_buffer(lung + 1)
                        s.DragQueryFileW(hdrop, i, buf, lung + 1)
                        lista.append(buf.value)
                    s.DragFinish(hdrop)
                    callback(lista)
                except Exception:
                    _log.exception("Drop")
                return 0
            return u.CallWindowProcW(_DROP_OLD, h, msg, wp, lp)

        _DROP_PROC = proc_t(proc)
        for m in (0x0233, 0x0049, 0x004A):  # funziona anche se il programma e' aperto come amministratore
            u.ChangeWindowMessageFilterEx(ctypes.c_void_p(hwnd), m, 1, None)
        _DROP_OLD = u.SetWindowLongPtrW(ctypes.c_void_p(hwnd), -4, ctypes.cast(_DROP_PROC, ctypes.c_void_p))
        if not _DROP_OLD:
            return False
        s.DragAcceptFiles(ctypes.c_void_p(hwnd), True)
        return True
    except Exception:
        _log.exception("Impossibile attivare il trascinamento dei file")
        return False


def apri_cartella(percorso: Path) -> bool:
    try:
        if IS_WIN:
            os.startfile(str(percorso))  # noqa
        elif sys.platform == "darwin":
            subprocess.Popen(["open", str(percorso)])
        else:
            subprocess.Popen(["xdg-open", str(percorso)])
        return True
    except Exception:
        return False


def mostra_file(percorso: Path) -> None:
    try:
        if IS_WIN:
            subprocess.Popen(["explorer", "/select,", str(percorso)])
        else:
            apri_cartella(percorso.parent)
    except Exception:
        pass


_MUTEX = None
_EVENTO = None


def _istanza_unica() -> bool:
    """True se questa e' la prima istanza. Se ce n'e' gia' un'altra, le chiede di mostrarsi e restituisce False."""
    global _MUTEX, _EVENTO
    if not IS_WIN:
        return True
    try:
        k = ctypes.WinDLL("kernel32", use_last_error=True)
        k.CreateMutexW.restype = ctypes.c_void_p
        k.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
        k.CreateEventW.restype = ctypes.c_void_p
        k.CreateEventW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
        k.SetEvent.argtypes = [ctypes.c_void_p]
        _MUTEX = k.CreateMutexW(None, False, "Local\\ComprimiImmaginiRapido.IstanzaUnica")
        gia_aperto = ctypes.get_last_error() == 183  # ERROR_ALREADY_EXISTS
        _EVENTO = k.CreateEventW(None, False, False, "Local\\ComprimiImmaginiRapido.MostraFinestra")
        if not gia_aperto:
            return True
        if _EVENTO:
            k.SetEvent(_EVENTO)
        ctypes.windll.user32.MessageBoxW(
            0, f"{APP} è già aperto: l'ho riportato in primo piano.\n\n"
               "Se non lo vedi, controlla la barra delle applicazioni.", APP, 0x40 | 0x40000)
        return False
    except Exception:
        return True


# --------------------------------------------------------------------------
# Grafica: sfondo sfumato, bottoni e campi arrotondati (disegnati con Pillow)
# --------------------------------------------------------------------------
SS = 3  # supersampling: bordi arrotondati senza scalini


def _rgb(h: str):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def _hex(rgb) -> str:
    return "#%02x%02x%02x" % tuple(int(v) for v in rgb)


def _mix(a, b, t: float):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def ease_out(t: float) -> float:
    return 1 - (1 - t) ** 3


def ease_in_out(t: float) -> float:
    return 3 * t * t - 2 * t ** 3


def sfumatura(w: int, h: int, colori, verticale=True) -> Image.Image:
    n = len(colori) - 1
    lung = max(h if verticale else w, 2)
    riga = Image.new("RGB", (1, lung) if verticale else (lung, 1))
    for i in range(lung):
        u = i / (lung - 1) * n
        j = min(int(u), n - 1)
        riga.putpixel((0, i) if verticale else (i, 0), _mix(colori[j], colori[j + 1], u - j))
    return riga.resize((w, h), Image.Resampling.NEAREST)


def img_sfondo(w: int, h: int, t: dict) -> Image.Image:
    base = sfumatura(w, h, [_rgb(c) for c in t["grad"]], True)
    for colore, alpha, cx, cy, diam in t["bagliori"]:
        d = max(8, int(max(w, h) * diam))
        m = Image.radial_gradient("L").resize((d, d), Image.Resampling.BICUBIC)
        m = m.point(lambda v, a=alpha: int(max(0.0, 1 - v / 180) ** 2 * 255 * a))
        mask = Image.new("L", (w, h), 0)
        mask.paste(m, (int(w * cx - d / 2), int(h * cy - d / 2)))
        base.paste(Image.new("RGB", (w, h), _rgb(colore)), (0, 0), mask)
    return base.convert("RGBA")


@lru_cache(maxsize=512)
def _capa_rr(w: int, h: int, r: int, fill=None, outline=None, ancho=1) -> Image.Image:
    w, h = max(1, w), max(1, h)
    capa = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
    kw = {"width": ancho * SS} if outline else {}
    ImageDraw.Draw(capa).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], radius=max(0, r) * SS,
                                           fill=fill, outline=outline, **kw)
    return capa.resize((w, h), Image.Resampling.LANCZOS)


def rr_su(base: Image.Image, box, r: int, fill=None, outline=None, ancho=1) -> None:
    x0, y0, x1, y1 = box
    base.alpha_composite(_capa_rr(x1 - x0, y1 - y0, r, fill, outline, ancho), (x0, y0))


@lru_cache(maxsize=128)
def img_bottone(w: int, h: int, r: int, c1, c2) -> Image.Image:
    """Bottone pieno con sfumatura orizzontale c1 -> c2 e un leggero riflesso in alto."""
    w, h = max(1, w), max(1, h)
    img = sfumatura(w, h, [c1, c2], verticale=False).convert("RGBA")
    mask = _capa_rr(w, h, r, fill=(255, 255, 255, 255)).getchannel("A")
    luce = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    ImageDraw.Draw(luce).rectangle([0, 0, w, h // 2], fill=(255, 255, 255, 34))
    luce.putalpha(ImageChops.multiply(luce.getchannel("A"), mask))
    img.putalpha(mask)
    img.alpha_composite(luce)
    return img


def _perimetro(w: float, h: float, r: float, passo: float = 1.0):
    """Punti lungo il bordo di un rettangolo arrotondato (in senso orario, da in alto a sinistra)."""
    r = min(r, w / 2, h / 2)
    pts = []

    def arco(cx, cy, a0):
        n = max(6, int(r * math.pi / 2 / passo))
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))

    def retta(x0, y0, x1, y1):
        n = max(1, int(math.hypot(x1 - x0, y1 - y0) / passo))
        for i in range(1, n):
            pts.append((x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n))

    arco(r, r, 180)
    retta(r, 0, w - r, 0)
    arco(w - r, r, 270)
    retta(w, r, w, h - r)
    arco(w - r, h - r, 0)
    retta(w - r, h, r, h)
    arco(r, h - r, 90)
    retta(0, h - r, 0, r)
    pts.append(pts[0])
    return pts


def _capa_tratteggio(w: int, h: int, r: int, colore, spessore=2, tratto=9, vuoto=7) -> Image.Image:
    """Bordo tratteggiato arrotondato (per la zona dove si trascinano le immagini)."""
    w, h = max(4, w), max(4, h)
    capa = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(capa)
    m = spessore * SS / 2
    pts = _perimetro(w * SS - 2 * m, h * SS - 2 * m, r * SS)
    pts = [(x + m, y + m) for x, y in pts]
    ciclo, acc, corrente = (tratto + vuoto) * SS, 0.0, []
    for i in range(len(pts) - 1):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        if acc % ciclo < tratto * SS:
            if not corrente:
                corrente = [(x0, y0)]
            corrente.append((x1, y1))
        elif corrente:
            d.line(corrente, fill=colore, width=spessore * SS, joint="curve")
            corrente = []
        acc += math.hypot(x1 - x0, y1 - y0)
    if corrente:
        d.line(corrente, fill=colore, width=spessore * SS, joint="curve")
    return capa.resize((w, h), Image.Resampling.LANCZOS)


def img_pallino(d: int, colore, bordo=None, ombra=True) -> Image.Image:
    """Cerchio (manopola dei cursori) con una piccola ombra."""
    pad = max(2, d // 4) if ombra else 0
    s = d + 2 * pad
    capa = Image.new("RGBA", (s * SS, s * SS), (0, 0, 0, 0))
    dr = ImageDraw.Draw(capa)
    if ombra:
        dr.ellipse([pad * SS, (pad + 1) * SS, (pad + d) * SS, (pad + d + 1) * SS], fill=(0, 0, 0, 90))
        capa = capa.filter(ImageFilter.GaussianBlur(SS * 1.4))
        dr = ImageDraw.Draw(capa)
    dr.ellipse([pad * SS, pad * SS, (pad + d) * SS - 1, (pad + d) * SS - 1], fill=colore,
               outline=bordo, width=SS * 2 if bordo else 0)
    return capa.resize((s, s), Image.Resampling.LANCZOS)


def icona_default(n: int = 512) -> Image.Image:
    """Icona: foto con montagne e sole su fondo blu notte, con il badge della freccia che 'riduce'."""
    s = n * 2
    im = sfumatura(s, s, [(16, 30, 44), (22, 48, 66), (36, 44, 90)], True).convert("RGBA")
    x0, y0, x1, y1 = int(s * .17), int(s * .20), int(s * .83), int(s * .74)
    w, h = x1 - x0, y1 - y0
    foglio = sfumatura(w, h, [(20, 184, 166), (56, 120, 240), (124, 92, 246)], False).convert("RGBA")
    d = ImageDraw.Draw(foglio)
    d.polygon([(w * .05, h * .92), (w * .36, h * .40), (w * .56, h * .68), (w * .70, h * .50), (w * .96, h * .92)],
              fill=(255, 255, 255, 235))
    d.ellipse([w * .66, h * .14, w * .66 + s * .09, h * .14 + s * .09], fill=(255, 255, 255, 245))
    mask = _capa_rr(w // SS, h // SS, int(s * .07 / SS), fill=(255, 255, 255, 255)).resize((w, h), Image.Resampling.LANCZOS)
    foglio.putalpha(mask.getchannel("A"))
    im.alpha_composite(foglio, (x0, y0))
    cx, cy, r = int(s * .70), int(s * .72), int(s * .15)
    d = ImageDraw.Draw(im)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255))
    a = r * .55
    d.polygon([(cx - a, cy - a * .15), (cx + a, cy - a * .15), (cx, cy + a * .75)], fill=(22, 48, 66, 255))
    d.rectangle([cx - a * .32, cy - a * .85, cx + a * .32, cy - a * .1], fill=(22, 48, 66, 255))
    return im.resize((n, n), Image.Resampling.LANCZOS)


def icona_pil(dim: int) -> Image.Image:
    """icon.png (o quella di riserva) con gli angoli arrotondati, grande dim x dim."""
    try:
        im = Image.open(risorsa("icon.png")).convert("RGBA")
        im = ImageOps.fit(im, (512, 512), Image.Resampling.LANCZOS)
    except Exception:
        im = icona_default(512)
    if im.getpixel((2, 2))[3] > 200:  # angoli pieni: la arrotondo io
        mask = _capa_rr(512, 512, 112, fill=(255, 255, 255, 255)).getchannel("A")
        im.putalpha(ImageChops.multiply(im.getchannel("A"), mask))
    return im.resize((dim, dim), Image.Resampling.LANCZOS)


# --------------------------------------------------------------------------
# Temi (grigio-blu profondo, accenti verde acqua -> indaco)
# --------------------------------------------------------------------------
TEMI = {
    "scuro": {
        "grad": ["#0f1b27", "#14283a", "#1a3348"],
        "bagliori": [("#6366f1", 0.30, 1.0, 0.0, 1.1), ("#14b8a6", 0.38, 0.0, 1.1, 1.2)],
        "card": (255, 255, 255, 18), "card_bordo": (255, 255, 255, 34),
        "campo": "#142130", "campo_bordo": (255, 255, 255, 40), "linea": "#2c4258",
        "fg": "#f2f7fb", "mut": "#9db2c7",
        "acc1": "#14b8a6", "acc2": "#6366f1", "acc_fg": "#ffffff",
        "vetro": (255, 255, 255, 26), "vetro_hover": (255, 255, 255, 56), "vetro_bordo": (255, 255, 255, 52),
        "sel": (20, 184, 166, 46), "sel_bordo": (45, 212, 191, 200),
        "ok": "#6ee7b7", "err": "#fda4af", "warn": "#fcd34d", "ombra": (0, 0, 0, 90),
    },
    "chiaro": {
        "grad": ["#f4f9fa", "#e5eff3", "#d4e3ee"],
        "bagliori": [("#ffffff", 0.70, 0.0, 0.0, 1.0), ("#6366f1", 0.22, 1.0, 1.1, 1.2)],
        "card": (255, 255, 255, 150), "card_bordo": (255, 255, 255, 220),
        "campo": "#ffffff", "campo_bordo": (30, 60, 90, 60), "linea": "#c5d6e2",
        "fg": "#14283a", "mut": "#566c82",
        "acc1": "#0d9488", "acc2": "#4f46e5", "acc_fg": "#ffffff",
        "vetro": (30, 60, 90, 24), "vetro_hover": (30, 60, 90, 50), "vetro_bordo": (30, 60, 90, 58),
        "sel": (13, 148, 136, 40), "sel_bordo": (13, 148, 136, 210),
        "ok": "#0f766e", "err": "#be123c", "warn": "#b45309", "ombra": (30, 60, 90, 70),
    },
}
FONT = ("Segoe UI", 10)
FONT_B = ("Segoe UI Semibold", 10)
FONT_T = ("Segoe UI Semibold", 15)
FONT_G = ("Segoe UI Semibold", 12)
FONT_S = ("Segoe UI", 9)
FONT_SB = ("Segoe UI Semibold", 9)


# --------------------------------------------------------------------------
# Animazioni e lavoratori in secondo piano
# --------------------------------------------------------------------------
class Animatore:
    """Un solo 'orologio' per tutte le animazioni: parte quando serve e si ferma quando non c'e' piu' niente."""

    def __init__(self, root):
        self.root = root
        self.att = {}
        self._id = None

    def avvia(self, chiave, durata_ms, fn, fine=None, ease=ease_out, ritardo_ms=0):
        """fn(e) con e che va da 0 a 1 (con l'easing). Con durata_ms=None gira all'infinito e fn riceve i secondi trascorsi."""
        self.att[chiave] = {"t0": time.perf_counter() + ritardo_ms / 1000,
                            "d": None if durata_ms is None else max(durata_ms, 1) / 1000,
                            "fn": fn, "fine": fine, "ease": ease}
        if self._id is None:
            self._id = self.root.after(16, self._tick)

    def ferma(self, chiave):
        self.att.pop(chiave, None)

    def ferma_tutto(self):
        self.att.clear()

    def _tick(self):
        self._id = None
        ora = time.perf_counter()
        for chiave, a in list(self.att.items()):
            if self.att.get(chiave) is not a:
                continue
            t = ora - a["t0"]
            if t < 0:
                continue
            try:
                if a["d"] is None:
                    a["fn"](t)
                    continue
                e = t / a["d"]
                a["fn"](1.0 if e >= 1 else a["ease"](e))
                if e >= 1 and self.att.get(chiave) is a:
                    del self.att[chiave]
                    if a["fine"]:
                        a["fine"]()
            except Exception:
                _log.exception("Errore in un'animazione")
                self.att.pop(chiave, None)
        if self.att and self._id is None:
            self._id = self.root.after(16, self._tick)


class Lavoratore(threading.Thread):
    """Esegue solo l'ultimo lavoro richiesto (l'anteprima: se muovi lo slider in fretta, vale l'ultima posizione)."""

    def __init__(self, uscita: "queue.Queue", nome: str):
        super().__init__(daemon=True)
        self.uscita, self.nome = uscita, nome
        self._ev, self._lock, self._job = threading.Event(), threading.Lock(), None
        self.start()

    def richiedi(self, fn):
        with self._lock:
            self._job = fn
            self._ev.set()

    def run(self):
        while True:
            self._ev.wait()
            with self._lock:
                fn, self._job = self._job, None
                self._ev.clear()
            if fn is None:
                continue
            try:
                r = fn()
            except Exception as e:
                _log.exception("Lavoratore %s", self.nome)
                r = {"err": messaggio_errore(e)}
            self.uscita.put((self.nome, r))


class Gruppo:
    """Pochi thread che elaborano in parallelo l'elenco delle immagini. Sono 'daemon': non bloccano mai la chiusura."""

    def __init__(self, uscita: "queue.Queue", lavori, n: int):
        self.uscita = uscita
        self.lavori = queue.Queue()
        for j in lavori:
            self.lavori.put(j)
        self.stop = threading.Event()
        for _ in range(max(1, n)):
            threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while not self.stop.is_set():
            try:
                fn = self.lavori.get_nowait()
            except queue.Empty:
                return
            try:
                r = fn(self.stop)
            except Exception as e:
                _log.exception("Lavoro in blocco")
                r = {"err": messaggio_errore(e)}
            self.uscita.put(("blocco", r))


class Voce:
    """Un'immagine nell'elenco."""
    __slots__ = ("percorso", "nome", "peso", "dim", "thumb", "thumb_tk", "crop", "salvato", "err", "t_in", "stato")

    def __init__(self, percorso: Path):
        self.percorso = Path(percorso)
        self.nome = self.percorso.name
        try:
            self.peso = self.percorso.stat().st_size
        except OSError:
            self.peso = 0
        self.dim = None
        self.thumb = None
        self.thumb_tk = None
        self.crop = None          # (x0, y0, x1, y1) in pixel dell'immagine orientata
        self.salvato = None       # {"firma", "percorso", "peso", "dim"}
        self.err = None
        self.t_in = 0.0
        self.stato = ""           # "", "coda", "ok", "err"


def firma_di(voce: Voce, p: dict):
    return (voce.crop, p["dimensione"], p["qualita"], p["formato"], p["extra"])


# --------------------------------------------------------------------------
# Guida e report per le segnalazioni
# --------------------------------------------------------------------------
GUIDA = f"""PRIMO AVVIO
Apri il programma e trascina le immagini nella finestra (anche intere cartelle), oppure usa «Aggiungi…» e «Cartella…». Non serve installare niente.
Il programma non crea cartelle accanto a sé: le impostazioni stanno in %APPDATA%\\ComprimiImmaginiRapido e i risultati nella cartella che scegli in Impostazioni. Quella dei risultati viene creata solo quando salvi la prima immagine.

COMPRIMERE LE IMMAGINI
1. Aggiungi le immagini: trascinale, oppure «Aggiungi…» / «Cartella…».
2. Clicca un'immagine nell'elenco: vedi subito l'anteprima.
3. Regola i due cursori:
• Qualità (30–95): più alta = foto migliore e file più pesante. 75 è un buon compromesso.
• Dimensione % (5–100): riduce larghezza e altezza. Al 20% una foto da 4000 px diventa 800 px.
4. Scegli il formato: JPG va bene ovunque, WebP è più leggero e mantiene la trasparenza.
5. Premi «Inizia»: tutte le immagini vengono salvate nella cartella dei risultati. Puoi fermarti con «Annulla».
I valori che scegli restano salvati per la prossima volta.

CONFRONTARE PRIMA E DOPO
Clicca o trascina sull'anteprima: la linea divide «Originale» (a sinistra) e «Compressa» (a destra). Sotto vedi peso e dimensioni prima → dopo e la percentuale risparmiata. Se il file diventasse più grande, la percentuale compare in giallo.

RITAGLIARE
1. Premi «Ritaglia».
2. Trascina un rettangolo sull'immagine (puoi rifarlo quante volte vuoi).
3. Premi «Fatto»: l'anteprima mostra solo la parte scelta.
«Azzera» toglie il ritaglio. Il ritaglio è per singola immagine e vale anche con «Inizia»; nell'elenco le immagini ritagliate hanno una ✂.

UNA SOLA IMMAGINE
Con un'immagine selezionata puoi usare, in basso:
• Salva: salva la versione compressa nella cartella dei risultati.
• Copia file: copia il file negli appunti; poi incolli dove vuoi (mail, chat, cartella).
• Copia immagine: copia i pixel, per incollarla in un documento.
• Cartella: apre la cartella dei risultati.

SCORCIATOIE
• Ctrl+O: aggiungi immagini.   • Invio: Inizia.   • Canc: toglie dall'elenco l'immagine selezionata (il file non viene toccato).
• Frecce su / giù: cambia immagine.   • Esc: chiude ritaglio, guida o impostazioni.
• Rotellina sull'elenco: scorre.

IMPOSTAZIONI
Cartella dei risultati, tema scuro o chiaro, compressione extra (più lenta, file più piccoli) e se aprire la cartella alla fine.

DOMANDE FREQUENTI
• Che formati accetta? JPG, PNG, BMP, GIF, WebP e TIFF. In uscita: JPG o WebP.
• Le immagini PNG con trasparenza? Il JPG non supporta la trasparenza: lo sfondo diventa bianco. Con WebP la trasparenza resta.
• Due file con lo stesso nome? Il programma non sovrascrive mai: il secondo diventa nome_2.jpg, e così via. Lo stesso se rielabori un'immagine con impostazioni diverse.
• Vengono modificati gli originali? No, mai.
• Le foto ruotate dal telefono? L'orientamento viene applicato. I dati EXIF (posizione, fotocamera) non vengono copiati nel file compresso.
• Le GIF animate? Viene usato solo il primo fotogramma.

NIENTE FILE IN GIRO
Nessun file temporaneo: i file si scrivono con un nome provvisorio che diventa definitivo solo a lavoro finito, e quelli rimasti da una chiusura anomala vengono tolti al prossimo avvio (compresa la cartella _MEI dell'exe dentro Temp, se più vecchia di un giorno). Il log è limitato a poche centinaia di KB e i vecchi report di «Segnala un problema» sul Desktop spariscono dopo 14 giorni.
Il programma si apre una volta sola: se lo riavvii, riporta in primo piano quello già aperto.

OPEN SOURCE E SEGNALAZIONI
Comprimi Immagini Rapido {VERSION} è open source (licenza MIT). Se qualcosa non funziona o hai un'idea per migliorarlo, scrivimi a {EMAIL}.
Con «Segnala un problema» il programma crea un file di report con i dati tecnici utili per sistemare l'errore (versione, Windows, impostazioni ed errori registrati). Non contiene immagini né percorsi personali. Leggilo prima di inviarlo e allegalo alla mail, insieme a due righe su cosa stavi facendo.
"""


def _anonimizza(testo: str) -> str:
    for valore, sostituto in ((str(Path.home()), "~"), (os.environ.get("USERNAME", ""), "utente")):
        if valore:
            testo = testo.replace(valore, sostituto)
    return testo


def crea_report(cfg: dict, schermo: str = "") -> Path:
    """Crea un file di testo con dati tecnici utili per risolvere un errore (niente immagini, niente percorsi personali)."""
    righe = [f"REPORT - {APP} {VERSION}",
             f"Creato: {datetime.datetime.now().isoformat(timespec='seconds')}",
             f"Sistema: {platform.platform()} ({platform.version()})",
             f"Python: {sys.version.split()[0]}  |  exe PyInstaller: {'sì' if getattr(sys, 'frozen', False) else 'no'}",
             f"Tk: {tk.TkVersion}  |  Pillow: {Image.__version__}",
             f"Schermo: {schermo or 'sconosciuto'}"]
    sicuro = {k: v for k, v in cfg.items() if k != "cartella_uscita"}
    righe += ["", "IMPOSTAZIONI (senza percorsi)", json.dumps(sicuro, ensure_ascii=False, indent=2), "",
              "ULTIME RIGHE DEL LOG"]
    try:
        righe += LOG_FILE.read_text(encoding="utf-8", errors="replace").splitlines()[-200:] or ["(vuoto)"]
    except OSError:
        righe.append("(nessun log)")
    testo = _anonimizza("\n".join(righe)) + "\n"

    cartella = Path.home() / "Desktop"
    if not cartella.is_dir():
        cartella = Path.home()
    for vecchio in cartella.glob("report_comprimi_*.txt"):  # i report di piu' di 14 giorni fa non servono piu'
        try:
            if time.time() - vecchio.stat().st_mtime > 14 * GIORNO:
                vecchio.unlink()
        except OSError:
            pass
    percorso = unique_path(cartella / f"report_comprimi_{datetime.datetime.now():%Y%m%d_%H%M%S}.txt")
    with open(percorso, "xb") as f:
        f.write(testo.encode("utf-8"))
    return percorso


# --------------------------------------------------------------------------
# Interfaccia
# --------------------------------------------------------------------------
W_WIN, H_WIN = 940, 640
# elenco immagini (a sinistra)
LX, LY, LW, RH, NVIS = 40, 124, 262, 60, 6
# anteprima (a destra)
PVX, PVY, PVW, PVH = 346, 96, 554, 288


class Bottone:
    """Bottone arrotondato con passaggio di colore animato quando ci passi sopra."""

    def __init__(self, app, x, y, w, h, testo, cmd, primario=False, font=FONT_B):
        self.app, self.cmd, self.prim, self.font = app, cmd, primario, font
        self.x, self.y, self.w, self.h = x, y, w, h
        self.abil, self.liv, self._idx = True, 0.0, 0
        cv = app.cv
        self.it = cv.create_image(app._px(x), app._px(y), anchor="nw")
        self.tx = cv.create_text(app._px(x + w / 2), app._px(y + h / 2), text=testo, font=font)
        self._costruisci()
        app._mani((self.it, self.tx), entra=self._entra, esce=self._esce, click=self._click)

    def _costruisci(self):
        a = self.app
        c = a.c
        W, H, R = a._px(self.w), a._px(self.h), a._px(self.h / 2)
        pil = []
        for i in range(5):
            t = i / 4
            if self.prim:
                pil.append(img_bottone(W, H, R, _mix(_rgb(c["acc1"]), (255, 255, 255), .18 * t),
                                       _mix(_rgb(c["acc2"]), (255, 255, 255), .18 * t)))
            else:
                pil.append(_capa_rr(W, H, R, _mix(c["vetro"], c["vetro_hover"], t), c["vetro_bordo"]))
        pil.append(_capa_rr(W, H, R, tuple(c["vetro"][:3]) + (12,), tuple(c["vetro_bordo"][:3]) + (24,)))
        self.fotos = [a._foto(p) for p in pil]
        self._applica(self.liv, forza=True)

    def _applica(self, liv, forza=False):
        self.liv = liv
        if not self.abil:
            idx = 5
        else:
            idx = int(round(clamp(liv, 0, 1) * 4))
        if idx != self._idx or forza:
            self._idx = idx
            self.app.cv.itemconfig(self.it, image=self.fotos[idx])
        c = self.app.c
        col = c["acc_fg"] if (self.prim and self.abil) else (c["fg"] if self.abil else c["mut"])
        self.app.cv.itemconfig(self.tx, fill=col)

    def _verso(self, meta):
        inizio = self.liv
        self.app.anim.avvia(("btn", id(self)), 140, lambda e: self._applica(inizio + (meta - inizio) * e))

    def _entra(self, _e):
        if self.abil:
            self.app.cv.config(cursor="hand2")
            self._verso(1.0)

    def _esce(self, _e):
        self.app.cv.config(cursor="")
        if self.abil:
            self._verso(0.0)

    def _click(self, _e):
        if self.abil and self.cmd:
            self.cmd()

    def imposta(self, testo=None, cmd=None, primario=None, abilitato=None):
        if testo is not None:
            self.app.cv.itemconfig(self.tx, text=testo)
        if cmd is not None:
            self.cmd = cmd
        if abilitato is not None:
            self.abil = abilitato
        if primario is not None and primario != self.prim:
            self.prim = primario
            self._costruisci()
        self._applica(0.0 if not self.abil else self.liv, forza=True)


class Cursore:
    """Cursore a trascinamento con la bolla del valore e la manopola che si ingrandisce."""

    def __init__(self, app, x, y, w, minimo, massimo, valore, etichetta, suffisso, su_cambio, su_fine):
        self.app, self.x, self.y, self.w = app, x, y, w
        self.min, self.max, self.v = minimo, massimo, valore
        self.suffisso, self.su_cambio, self.su_fine = suffisso, su_cambio, su_fine
        self.attivo = False
        c = app.c
        cv = app.cv
        self.H = 8
        app._testo(x, y, etichetta, FONT_B)
        self.t_val = app._testo(x + w, y, "", FONT_B, c["acc1"], anchor="ne")
        ty = y + 32
        self.ty = ty
        self.f_traccia = app._foto(_capa_rr(app._px(w), app._px(self.H), app._px(self.H / 2),
                                            tuple(c["vetro"][:3]) + (40,), c["vetro_bordo"]))
        cv.create_image(app._px(x), app._px(ty - self.H / 2), image=self.f_traccia, anchor="nw")
        self.it_fill = cv.create_image(app._px(x), app._px(ty - self.H / 2), anchor="nw")
        self.f_man = app._foto(img_pallino(app._px(18), (255, 255, 255), None))
        self.f_man_big = app._foto(img_pallino(app._px(23), (255, 255, 255), _rgb(c["acc1"])))
        self.it_man = cv.create_image(0, 0, image=self.f_man, anchor="center")
        self.bolla = []
        self._disegna()

    def _px_pos(self):
        return self.app._px(self.x + (self.v - self.min) / (self.max - self.min) * self.w)

    def _disegna(self):
        a, c = self.app, self.app.c
        cv = a.cv
        cx = self._px_pos()
        fw = max(a._px(self.H), cx - a._px(self.x))
        self._f_fill = a._ImageTk.PhotoImage(img_bottone(fw, a._px(self.H), a._px(self.H / 2), _rgb(c["acc1"]), _rgb(c["acc2"])))
        cv.itemconfig(self.it_fill, image=self._f_fill)
        cv.coords(self.it_man, cx, a._px(self.ty))
        cv.itemconfig(self.t_val, text=f"{self.v}{self.suffisso}")
        if self.bolla:
            self._bolla(cx)

    def _bolla(self, cx):
        a, c = self.app, self.app.c
        cv = a.cv
        for b in self.bolla:
            cv.delete(b)
        txt = f"{self.v}{self.suffisso}"
        w = int(a._mis(txt, FONT_SB)) + 18
        h = 24
        f = a._ImageTk.PhotoImage(_capa_rr(a._px(w), a._px(h), a._px(h / 2), _rgb(c["acc1"]) + (255,), None))
        self._f_bolla = f
        by = a._px(self.ty - 34)
        i1 = cv.create_image(cx, by, image=f, anchor="center")
        i2 = cv.create_text(cx, by, text=txt, font=FONT_SB, fill=c["acc_fg"])
        self.bolla = [i1, i2]

    def colpisce(self, ex, ey):
        a = self.app
        return (a._px(self.x - 12) <= ex <= a._px(self.x + self.w + 12)
                and a._px(self.ty - 16) <= ey <= a._px(self.ty + 16))

    def _da_x(self, ex):
        a = self.app
        t = clamp((ex - a._px(self.x)) / max(1, a._px(self.w)), 0, 1)
        nuovo = int(round(self.min + t * (self.max - self.min)))
        if nuovo != self.v:
            self.v = nuovo
            self.su_cambio(nuovo)
        self._disegna()

    def inizia(self, ex):
        self.attivo = True
        self.app.cv.itemconfig(self.it_man, image=self.f_man_big)
        self.bolla = []
        self._bolla(self._px_pos())
        self._da_x(ex)

    def muovi(self, ex):
        if self.attivo:
            self._da_x(ex)

    def fine(self):
        if not self.attivo:
            return
        self.attivo = False
        self.app.cv.itemconfig(self.it_man, image=self.f_man)
        for b in self.bolla:
            self.app.cv.delete(b)
        self.bolla = []
        self.su_fine()

    def imposta(self, v):
        self.v = clamp(int(v), self.min, self.max)
        self._disegna()


class App:
    """Tutto in una sola finestra: cambia solo il contenuto (nessun popup di Windows)."""

    def __init__(self):
        from PIL import ImageTk
        self._ImageTk = ImageTk
        self.cfg = carica_config()
        self.tema = self.cfg["tema"]

        self.root = tk.Tk()
        self.root.title(APP)
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self._chiudi)
        self.root.report_callback_exception = self._errore_tk
        try:  # schermi HiDPI: i pixel del disegno seguono la scala di Windows
            self.k = max(1.0, float(self.root.winfo_fpixels("1i")) / 96.0)
        except Exception:
            self.k = 1.0
        self.cv = tk.Canvas(self.root, width=self._px(W_WIN), height=self._px(H_WIN), highlightthickness=0, bd=0)
        self.cv.pack(fill="both", expand=True)

        self.q = queue.Queue()
        self.anim = Animatore(self.root)
        self.meta = queue.Queue()
        threading.Thread(target=self._giro_meta, daemon=True).start()
        self.lav_prev = Lavoratore(self.q, "prev")

        self.scena = None
        self.base = None
        self._refs = []
        self._font_cache = {}
        self._after_prev = None
        self._sporca = False
        self._drag = None
        self.items = []
        self.sel = None
        self.scroll = 0
        self.cursori = []
        self.modo_crop = False
        self.crop_tmp = None
        self.risultato = None
        self.pv_orig = self.pv_dopo = self.pv_full = None
        self.pv_scala = 1.0
        self._pv_photo = None
        self._pv_geom = None
        self._cache_orig = (None, None)
        self._tok = 0
        self.split = 0.5
        self.gruppo = None
        self._f_riga = None
        self._f_pulse = None
        self.prog_vis = 0.0
        self.prog_meta = 0.0
        self.testo_stato = "Trascina qui le immagini per iniziare"
        self.colore_stato = "mut"
        self.btn = {}
        try:
            self.icon_img = ImageTk.PhotoImage(icona_pil(64))
            self.root.iconphoto(True, self.icon_img)
        except Exception:
            self.icon_img = None

        self.root.update_idletasks()
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        W, H = self._px(W_WIN), self._px(H_WIN)
        self.root.geometry(f"{W}x{H}+{max(0, (sw - W) // 2)}+{max(0, (sh - H) // 3)}")
        self.root.update_idletasks()
        self.hwnd = hwnd_finestra(self.root)
        attiva_drop(self.hwnd, self._drop)

        cv = self.cv
        cv.bind("<ButtonPress-1>", self._press)
        cv.bind("<B1-Motion>", self._motion)
        cv.bind("<ButtonRelease-1>", self._release)
        cv.bind("<Motion>", self._hover_pv)
        self.root.bind("<MouseWheel>", self._rotella)
        self.root.bind("<Delete>", lambda _e: self._canc())
        self.root.bind("<Up>", lambda _e: self._frecce(-1))
        self.root.bind("<Down>", lambda _e: self._frecce(1))
        self.root.bind("<Return>", lambda _e: self._invio())
        self.root.bind("<Escape>", lambda _e: self._esc())
        self.root.bind("<Control-o>", lambda _e: self._scegli_file())
        self.root.after(30, self._pompa)
        self.root.after(300, self._controlla_richiamo)
        self._scena_principale()

    @property
    def c(self):
        return TEMI[self.tema]

    # ---- mattoncini di disegno (le misure sono "logiche": qui si moltiplicano per la scala) ----
    def _px(self, v) -> int:
        return int(round(v * self.k))

    def _foto(self, img):
        foto = self._ImageTk.PhotoImage(img)
        self._refs.append(foto)
        return foto

    def _font(self, f):
        if f not in self._font_cache:
            self._font_cache[f] = tkfont.Font(font=f)
        return self._font_cache[f]

    def _mis(self, testo, f) -> float:
        """Larghezza in pixel 'logici' di un testo."""
        return self._font(f).measure(testo) / self.k

    def _ellissi(self, testo, f, maxw) -> str:
        if self._mis(testo, f) <= maxw:
            return testo
        while len(testo) > 1 and self._mis(testo + "…", f) > maxw:
            testo = testo[:-1]
        return testo.rstrip() + "…"

    def _testo(self, x, y, s, font=FONT, fill=None, anchor="nw", width=None, justify="left", tags=()):
        return self.cv.create_text(self._px(x), self._px(y), text=s, font=font, fill=fill or self.c["fg"],
                                   anchor=anchor, width=self._px(width) if width else 0, justify=justify, tags=tags)

    def _rr(self, x0, y0, x1, y1, r, fill=None, outline=None):
        rr_su(self.base, (self._px(x0), self._px(y0), self._px(x1), self._px(y1)), self._px(r), fill, outline, 1)

    def _card(self, x0, y0, x1, y1, r=18):
        c = self.c
        self._rr(x0, y0, x1, y1, r, c["card"], c["card_bordo"])

    def _campo(self, x0, y0, x1, y1, r=14):
        c = self.c
        self._rr(x0, y0, x1, y1, r, _rgb(c["campo"]) + (255,), c["campo_bordo"])

    def _mani(self, oggetti, entra=None, esce=None, click=None):
        for o in oggetti:
            if entra:
                self.cv.tag_bind(o, "<Enter>", entra)
            if esce:
                self.cv.tag_bind(o, "<Leave>", esce)
            if click:
                self.cv.tag_bind(o, "<Button-1>", click)

    def _bottone(self, x, y, w, h, testo, cmd, primario=False, font=FONT_B):
        return Bottone(self, x, y, w, h, testo, cmd, primario, font)

    def _link(self, x, y, s, cmd, anchor="nw", font=FONT_S):
        c = self.c
        t = self._testo(x, y, s, font, c["mut"], anchor)
        self._mani((t,),
                   entra=lambda _e: (self.cv.itemconfig(t, fill=c["fg"]), self.cv.config(cursor="hand2")),
                   esce=lambda _e: (self.cv.itemconfig(t, fill=c["mut"]), self.cv.config(cursor="")),
                   click=lambda _e: cmd())
        return t

    def _pillole(self, x, y, opzioni, selezionata, cmd, w=72, h=30, gap=8):
        """Scelta a pulsanti (al posto del menu a tendina). Restituisce la funzione che cambia la selezione."""
        c = self.c
        W, H, R = self._px(w), self._px(h), self._px(h / 2)
        on = self._foto(img_bottone(W, H, R, _rgb(c["acc1"]), _rgb(c["acc2"])))
        off = self._foto(_capa_rr(W, H, R, c["vetro"], c["vetro_bordo"]))
        voci = {}
        for i, nome in enumerate(opzioni):
            px_ = x + i * (w + gap)
            it = self.cv.create_image(self._px(px_), self._px(y), image=off, anchor="nw")
            tx = self.cv.create_text(self._px(px_ + w / 2), self._px(y + h / 2), text=nome, font=FONT, fill=c["fg"])
            voci[nome] = (it, tx)
            self._mani((it, tx), click=lambda _e, n=nome: cmd(n),
                       entra=lambda _e: self.cv.config(cursor="hand2"), esce=lambda _e: self.cv.config(cursor=""))

        def seleziona(sel):
            for nome, (it, tx) in voci.items():
                attiva = nome == sel
                self.cv.itemconfig(it, image=on if attiva else off)
                self.cv.itemconfig(tx, fill=c["acc_fg"] if attiva else c["fg"], font=FONT_B if attiva else FONT)
        seleziona(selezionata)
        return seleziona

    # ---- scene ----
    def _scena(self, nome):
        """Svuota la finestra e prepara lo sfondo nuovo."""
        self.scena = nome
        self.anim.ferma_tutto()
        if self._after_prev:
            try:
                self.root.after_cancel(self._after_prev)
            except Exception:
                pass
            self._after_prev = None
        self.cv.delete("all")
        self._refs = []
        self.cursori = []
        self._drag = None
        self._pv_photo = None
        self._pv_geom = None
        self.btn = {}
        self._f_riga = None
        self._f_pulse = None
        try:
            self.root.unbind("<MouseWheel>")
            self.root.bind("<MouseWheel>", self._rotella)
        except Exception:
            pass
        self.cv.config(bg=self.c["grad"][0], cursor="")
        self.base = img_sfondo(self._px(W_WIN), self._px(H_WIN), self.c)
        colora_titolo(self.hwnd, _rgb(self.c["grad"][0]), _rgb(self.c["fg"]), self.tema == "scuro")

    def _sfondo_pronto(self):
        foto = self._foto(self.base)
        self.cv.create_image(0, 0, image=foto, anchor="nw")

    def _entra_scena(self):
        """Il contenuto sale di pochi pixel mentre compare."""
        dy0 = self._px(16)
        self.cv.move("all", 0, dy0)
        prec = [float(dy0)]

        def f(e):
            nuovo = dy0 * (1 - e)
            self.cv.move("all", 0, nuovo - prec[0])
            prec[0] = nuovo
        self.anim.avvia("scena", 260, f)

    def _intestazione(self, sotto, indietro=False):
        c = self.c
        logo = self._foto(icona_pil(self._px(40)))
        self.cv.create_image(self._px(28), self._px(16), image=logo, anchor="nw")
        self._testo(80, 26, APP, FONT_T, anchor="w")
        self._testo(80, 50, sotto, FONT_S, c["mut"], anchor="w")
        if indietro:
            self._link(W_WIN - 28, 28, "‹  Indietro", self._scena_principale, anchor="ne", font=FONT)
            return
        x = W_WIN - 28
        for testo, cmd in (("Segnala un problema", self._segnala), ("Impostazioni", self._scena_impostazioni),
                           ("Guida", self._scena_guida)):
            self._link(x, 28, testo, cmd, anchor="ne")
            x -= self._mis(testo, FONT_S) + 20

    # ---- notifiche (al posto dei popup) ----
    def _toast(self, testo, ok=True, ms=2800):
        if self.scena is None:
            return
        c = self.c
        for k in ("toast", "toast2", "toast3"):
            self.anim.ferma(k)
        self.cv.delete("toast")
        colore = _rgb(c["ok"] if ok else c["err"])
        testo = self._ellissi(testo, FONT_B, 500)
        w, h = int(self._mis(testo, FONT_B)) + 56, 40
        pil = _capa_rr(self._px(w), self._px(h), self._px(h / 2), _rgb(c["campo"]) + (250,), colore + (200,), 1)
        foto = self._foto(pil)
        x = (W_WIN - w) / 2
        y_fuori, y_dentro = -h - 6, 12
        it = self.cv.create_image(self._px(x), self._px(y_fuori), image=foto, anchor="nw", tags="toast")
        dot = self.cv.create_oval(self._px(x + 16), self._px(y_fuori + 15), self._px(x + 26), self._px(y_fuori + 25),
                                  fill=_hex(colore), outline="", tags="toast")
        tx = self.cv.create_text(self._px(x + 36), self._px(y_fuori + h / 2), text=testo, font=FONT_B,
                                 fill=c["fg"], anchor="w", tags="toast")

        def pos(y):
            self.cv.coords(it, self._px(x), self._px(y))
            self.cv.coords(dot, self._px(x + 16), self._px(y + 15), self._px(x + 26), self._px(y + 25))
            self.cv.coords(tx, self._px(x + 36), self._px(y + h / 2))

        def esce():
            self.anim.avvia("toast3", 220, lambda e: pos(y_dentro + (y_fuori - y_dentro) * e),
                            fine=lambda: self.cv.delete("toast"))

        def tieni():
            self.anim.avvia("toast2", ms, lambda e: None, fine=esce)
        self.anim.avvia("toast", 280, lambda e: pos(y_fuori + (y_dentro - y_fuori) * e), fine=tieni)

    def _errore_tk(self, exc, val, tb):
        _log.error("Errore nell'interfaccia", exc_info=(exc, val, tb))
        try:
            self._toast("Qualcosa è andato storto. Se si ripete usa «Segnala un problema».", ok=False)
        except Exception:
            pass

    # ---- thread in secondo piano ----
    def _giro_meta(self):
        """Legge dimensioni e miniatura delle immagini aggiunte, una alla volta, senza bloccare la finestra."""
        s = self._px(44)
        while True:
            v = self.meta.get()
            try:
                v.dim = dimensioni_orientate(v.percorso)
                with Image.open(v.percorso) as im:
                    try:
                        im.draft("RGB", (s * 2, s * 2))
                    except Exception:
                        pass
                    im.load()
                    mini = ImageOps.exif_transpose(im)
                    mini = a_rgb(mini.convert("RGBA")) if mini.mode != "RGB" else mini
                    mini = ImageOps.fit(mini, (s, s), Image.Resampling.LANCZOS).convert("RGBA")
                mask = _capa_rr(s, s, self._px(9), fill=(255, 255, 255, 255)).getchannel("A")
                mini.putalpha(mask)
                v.thumb = mini
            except Exception as e:
                v.err = v.err or messaggio_errore(e)
            self.q.put(("meta", v))

    def _pompa(self):
        try:
            while True:
                tipo, r = self.q.get_nowait()
                try:
                    getattr(self, "_ric_" + tipo)(r)
                except Exception:
                    _log.exception("Messaggio %s", tipo)
        except queue.Empty:
            pass
        if self._sporca:
            self._sporca = False
            if self.scena == "principale":
                self._ridisegna_lista()
        try:
            self.root.after(30, self._pompa)
        except Exception:
            pass

    def _ric_meta(self, v):
        if v in self.items:
            self._sporca = True

    # ---------------------------------------------------------------- scena principale
    def _scena_principale(self):
        self._scena("principale")
        c, p = self.c, self.cfg
        self._card(28, 84, 318, 560)
        self._card(334, 84, 912, 428)
        self._card(334, 440, 912, 560)
        self._campo(PVX, PVY, PVX + PVW, PVY + PVH)
        self._sfondo_pronto()
        self._intestazione("Riduci il peso, non la qualità")

        # elenco
        self._testo(46, 98, "Immagini", FONT_B)
        self.t_conta = self._testo(300, 99, "", FONT_S, c["mut"], anchor="ne")
        self.btn["aggiungi"] = self._bottone(40, 498, 92, 32, "Aggiungi…", self._scegli_file, font=FONT)
        self.btn["cartella_in"] = self._bottone(140, 498, 80, 32, "Cartella…", self._scegli_cartella, font=FONT)
        self.btn["svuota"] = self._bottone(228, 498, 74, 32, "Svuota", self._svuota, font=FONT)
        self._testo(173, 540, "Puoi anche trascinare le cartelle", FONT_S, c["mut"], anchor="n")

        # cursori e opzioni
        cq = Cursore(self, 352, 452, 292, QUAL_MIN, QUAL_MAX, p["qualita"], "Qualità", "", self._cambio_q, self._fine_param)
        cd = Cursore(self, 352, 506, 292, DIM_MIN, DIM_MAX, p["dimensione"], "Dimensione", "%", self._cambio_d, self._fine_param)
        self.cursori = [cq, cd]
        self._testo(676, 452, "Formato", FONT_B)
        self._sel_fmt = self._pillole(676, 476, list(FORMATI), p["formato"], self._cambia_formato, w=66, h=30)
        self.btn["ritaglia"] = self._bottone(676, 518, 104, 32, "Ritaglia", self._toggle_crop, font=FONT)
        self.btn["azzera"] = self._bottone(788, 518, 104, 32, "Azzera", self._azzera_crop, font=FONT)

        # barra in basso
        self.t_stato = self._testo(28, 576, self.testo_stato, FONT_S, c[self.colore_stato], width=296)
        self.f_traccia = self._foto(_capa_rr(self._px(290), self._px(8), self._px(4), tuple(c["vetro"][:3]) + (40,), c["vetro_bordo"]))
        self.cv.create_image(self._px(28), self._px(606), image=self.f_traccia, anchor="nw")
        self.it_prog = self.cv.create_image(self._px(28), self._px(606), anchor="nw")
        self.btn["salva"] = self._bottone(334, 576, 70, 36, "Salva", self._salva_click, font=FONT)
        self.btn["copia_file"] = self._bottone(412, 576, 94, 36, "Copia file", self._copia_file, font=FONT)
        self.btn["copia_img"] = self._bottone(514, 576, 118, 36, "Copia immagine", self._copia_img, font=FONT)
        self.btn["cartella"] = self._bottone(640, 576, 86, 36, "Cartella", self._apri_risultati, font=FONT)
        corre = self.gruppo is not None
        self.btn["inizia"] = self._bottone(762, 570, 150, 48, "Annulla" if corre else "▶  Inizia",
                                           self._annulla if corre else self._inizia, primario=not corre)

        self._ridisegna_lista()
        self._disegna_pv()
        self._aggiorna_crop_btn()
        self._aggiorna_azioni()
        self._disegna_prog(self.prog_vis)
        self._entra_scena()

    # ---------------------------------------------------------------- elenco immagini
    def _icona_drop(self, d):
        c = self.c
        a = _rgb(c["acc1"])
        s = d * SS
        im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        dr = ImageDraw.Draw(im)
        dr.ellipse([0, 0, s - 1, s - 1], fill=a + (46,), outline=a + (170,), width=SS * 2)
        cx = s / 2
        dr.rectangle([cx - s * .06, s * .24, cx + s * .06, s * .50], fill=a + (255,))
        dr.polygon([(cx - s * .17, s * .47), (cx + s * .17, s * .47), (cx, s * .66)], fill=a + (255,))
        dr.line([(s * .29, s * .76), (s * .71, s * .76)], fill=a + (255,), width=SS * 3)
        return im.resize((d, d), Image.Resampling.LANCZOS)

    def _icona_foto(self, d):
        c = self.c
        a = _rgb(c["mut"])
        s = d * SS
        im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        dr = ImageDraw.Draw(im)
        dr.rounded_rectangle([s * .08, s * .16, s * .92, s * .84], radius=s * .12, outline=a + (190,), width=SS * 3)
        dr.polygon([(s * .16, s * .76), (s * .40, s * .46), (s * .56, s * .64), (s * .68, s * .52), (s * .84, s * .76)],
                   fill=a + (150,))
        dr.ellipse([s * .62, s * .26, s * .76, s * .40], fill=a + (190,))
        return im.resize((d, d), Image.Resampling.LANCZOS)

    def _disegna_vuoto(self):
        """Zona 'trascina qui': il bordo tratteggiato e l'icona si muovono piano."""
        c = self.c
        cv = self.cv
        W, H, R = self._px(LW), self._px(RH * NVIS), self._px(16)
        a = _rgb(c["acc1"])
        fondo = _rgb(c["mut"])
        if self._f_pulse is None:
            self._f_pulse = [self._foto(_capa_tratteggio(W, H, R, _mix(fondo, a, i / 4) + (int(110 + 120 * i / 4),)))
                             for i in range(5)]
        self._pulse = self._f_pulse
        zona = cv.create_image(self._px(LX), self._px(LY), image=self._pulse[0], anchor="nw", tags="vuoto")
        d = self._px(64)
        self._f_drop = self._foto(self._icona_drop(d))
        cx = self._px(LX + LW / 2)
        y_ico = self._px(LY + 118)
        ico = cv.create_image(cx, y_ico, image=self._f_drop, anchor="center", tags="vuoto")
        t1 = self._testo(LX + LW / 2, LY + 176, "Trascina qui le immagini", FONT_G, anchor="n", tags="vuoto")
        t2 = self._testo(LX + LW / 2, LY + 204, "o intere cartelle, oppure clicca", FONT_S, self.c["mut"], anchor="n", tags="vuoto")
        self._mani((zona, ico, t1, t2), click=lambda _e: self._scegli_file(),
                   entra=lambda _e: cv.config(cursor="hand2"), esce=lambda _e: cv.config(cursor=""))
        stato = {"i": 0}

        def ciclo(t):
            i = int((math.sin(t * 2.4) + 1) / 2 * 4.999)
            if i != stato["i"]:
                stato["i"] = i
                cv.itemconfig(zona, image=self._pulse[i])
            cv.coords(ico, cx, y_ico + self._px(3) * math.sin(t * 2.4))
        self.anim.avvia("pulse", None, ciclo)

    def _ridisegna_lista(self):
        if self.scena != "principale":
            return
        c, cv = self.c, self.cv
        cv.delete("riga")
        cv.delete("vuoto")
        self.anim.ferma("pulse")
        n = len(self.items)
        cv.itemconfig(self.t_conta, text="" if not n else (f"{n} immagine" if n == 1 else f"{n} immagini"))
        if not n:
            self._disegna_vuoto()
            return
        self.scroll = clamp(self.scroll, 0, max(0, n - NVIS))
        ora = time.perf_counter()
        wmax_base = LW - 62 - 24
        for r in range(NVIS):
            i = self.scroll + r
            if i >= n:
                break
            v = self.items[i]
            dt = ora - v.t_in
            if dt < 0:
                continue
            dx = (1 - ease_out(clamp(dt / 0.28, 0, 1))) * 26
            x, y = LX + dx, LY + r * RH
            sel = v is self.sel
            f_n, f_h, f_s = self._foto_riga()
            rt = f"rg{r}"
            tg = ("riga", rt)
            bg = cv.create_image(self._px(x), self._px(y + 3), image=f_s if sel else f_n, anchor="nw", tags=tg)
            cv.create_image(self._px(x + 8), self._px(y + 8), image=self._thumb(v), anchor="nw", tags=tg)
            marche = []
            if v.crop:
                marche.append(("✂", c["acc1"]))
            if v.stato == "ok":
                marche.append(("✓", c["ok"]))
            elif v.stato == "err" or v.err:
                marche.append(("!", c["err"]))
            wmax = wmax_base - 20 * len(marche)
            self._testo(x + 62, y + 12, self._ellissi(v.nome, FONT_B, wmax), FONT_B, tags=tg)
            sub, col = self._sotto_riga(v)
            self._testo(x + 62, y + 33, self._ellissi(sub, FONT_S, LW - 62 - 24), FONT_S, c[col], tags=tg)
            for j, (sim, scol) in enumerate(marche):
                self._testo(x + LW - 32 - 18 * (len(marche) - 1 - j), y + RH / 2, sim, FONT_B, scol, anchor="e", tags=tg)
            if v.stato == "coda":
                cv.create_oval(self._px(x + 46), self._px(y + 6), self._px(x + 56), self._px(y + 16),
                               fill=c["acc1"], outline="", tags=tg)
            cancella = self._testo(x + LW - 18, y + RH / 2, "✕", FONT_S, c["mut"], anchor="e", tags="riga")
            if not sel:
                cv.tag_bind(rt, "<Enter>", lambda _e, b=bg, f=f_h: (cv.itemconfig(b, image=f), cv.config(cursor="hand2")))
                cv.tag_bind(rt, "<Leave>", lambda _e, b=bg, f=f_n: (cv.itemconfig(b, image=f), cv.config(cursor="")))
            else:
                cv.tag_bind(rt, "<Enter>", lambda _e: cv.config(cursor="hand2"))
                cv.tag_bind(rt, "<Leave>", lambda _e: cv.config(cursor=""))
            cv.tag_bind(rt, "<Button-1>", lambda _e, vv=v: self._seleziona(vv))
            self._mani((cancella,), entra=lambda _e, t=cancella: (cv.itemconfig(t, fill=c["err"]), cv.config(cursor="hand2")),
                       esce=lambda _e, t=cancella: (cv.itemconfig(t, fill=c["mut"]), cv.config(cursor="")),
                       click=lambda _e, vv=v: self._rimuovi(vv))
        if n > NVIS:
            tot = RH * NVIS
            h = max(24, tot * NVIS / n)
            y0 = LY + (tot - h) * self.scroll / max(1, n - NVIS)
            f = self._foto(_capa_rr(self._px(4), self._px(h), self._px(2), tuple(c["vetro_hover"][:3]) + (120,)))
            cv.create_image(self._px(LX + LW + 2), self._px(y0), image=f, anchor="nw", tags="riga")

    def _foto_riga(self):
        if self._f_riga is None:
            c = self.c
            W, H, R = self._px(LW - 4), self._px(RH - 6), self._px(12)
            self._f_riga = (self._foto(_capa_rr(W, H, R, (0, 0, 0, 0))),
                            self._foto(_capa_rr(W, H, R, c["vetro"], None)),
                            self._foto(_capa_rr(W, H, R, c["sel"], c["sel_bordo"], 2)))
        return self._f_riga

    def _thumb(self, v):
        if v.thumb is not None and v.thumb_tk is None:
            try:
                v.thumb_tk = self._ImageTk.PhotoImage(v.thumb)
            except Exception:
                v.thumb = None
        if v.thumb_tk is not None:
            return v.thumb_tk
        if not hasattr(self, "_f_thumb_vuota") or self._f_thumb_vuota_tema != self.tema:
            s = self._px(44)
            self._f_thumb_vuota = self._ImageTk.PhotoImage(_capa_rr(s, s, self._px(9), self.c["vetro"], self.c["vetro_bordo"]))
            self._f_thumb_vuota_tema = self.tema
        return self._f_thumb_vuota

    def _sotto_riga(self, v):
        if v.err:
            return v.err, "err"
        if v.salvato:
            pct = risparmio(v.peso, v.salvato["peso"])
            segno = f"−{pct}%" if pct > 0 else (f"+{-pct}%" if pct < 0 else "0%")
            return f"{fmt_peso(v.peso)} → {fmt_peso(v.salvato['peso'])}  {segno}", "ok" if pct >= 0 else "warn"
        if v.dim:
            return f"{fmt_peso(v.peso)} · {v.dim[0]}×{v.dim[1]}", "mut"
        return fmt_peso(v.peso), "mut"

    def _rotella(self, e):
        try:
            px_, py_ = self.cv.winfo_pointerxy()
            x, y = px_ - self.cv.winfo_rootx(), py_ - self.cv.winfo_rooty()
        except Exception:
            return
        if self.scena == "principale":
            if self._px(LX) <= x <= self._px(LX + LW + 8) and self._px(LY) <= y <= self._px(LY + RH * NVIS):
                self.scroll += -1 if e.delta > 0 else 1
                self._ridisegna_lista()
        elif self.scena == "guida":
            self._scorri_guida(-1 if e.delta > 0 else 1)

    def _frecce(self, d):
        if self.scena == "guida":
            self._scorri_guida(d)
            return
        if self.scena != "principale" or not self.items:
            return
        i = self.items.index(self.sel) if self.sel in self.items else -1
        j = clamp(i + d, 0, len(self.items) - 1)
        if j != i:
            self._seleziona(self.items[j])

    def _canc(self):
        if self.scena == "principale" and self.sel:
            self._rimuovi(self.sel)

    def _invio(self):
        if self.scena == "principale":
            (self._annulla if self.gruppo else self._inizia)()

    def _esc(self):
        if self.scena != "principale":
            self._scena_principale()
        elif self.modo_crop:
            self._toggle_crop()

    def _scegli_file(self):
        tipi = " ".join("*" + e for e in sorted(EST_OK))
        r = filedialog.askopenfilenames(parent=self.root, title="Scegli le immagini",
                                        filetypes=[("Immagini", tipi), ("Tutti i file", "*.*")])
        if r:
            self._aggiungi(list(r))

    def _scegli_cartella(self):
        d = filedialog.askdirectory(parent=self.root, title="Scegli una cartella di immagini")
        if d:
            self._aggiungi([d])

    def _drop(self, percorsi):
        def fai():
            if self.scena != "principale":
                self._scena_principale()
            self._aggiungi(percorsi)
        self.root.after(10, fai)

    def _aggiungi(self, percorsi):
        nuovi, troncato = espandi_percorsi(percorsi, [v.percorso for v in self.items])
        if not nuovi:
            self._toast("Nessuna immagine nuova (formati: JPG, PNG, BMP, GIF, WebP, TIFF)", ok=False)
            return
        ora = time.perf_counter()
        prima = len(self.items)
        for i, pth in enumerate(nuovi):
            v = Voce(pth)
            v.t_in = ora + min(i, 8) * 0.045
            self.items.append(v)
            self.meta.put(v)
        n = len(nuovi)
        msg = f"Aggiunta 1 immagine" if n == 1 else f"Aggiunte {n} immagini"
        if troncato:
            msg += f" (massimo {MAX_FILE} alla volta)"
        self._toast(msg)
        self.anim.avvia("lista_in", 900, lambda e: self._ridisegna_lista())
        if self.sel is None:
            self._seleziona(self.items[prima])
        else:
            self._ridisegna_lista()
        self._aggiorna_azioni()
        if self.gruppo is None:
            self._stato(f"{len(self.items)} immagini pronte" if len(self.items) != 1 else "1 immagine pronta")

    def _rimuovi(self, v):
        if self.gruppo is not None:
            self._toast("Aspetta la fine dell'elaborazione", ok=False)
            return
        if v not in self.items:
            return
        i = self.items.index(v)
        self.items.remove(v)
        if v is self.sel:
            if self.items:
                self._seleziona(self.items[min(i, len(self.items) - 1)])
            else:
                self._azzera_selezione()
        self._ridisegna_lista()
        self._aggiorna_azioni()
        if not self.items:
            self._stato("Trascina qui le immagini per iniziare")

    def _svuota(self):
        if self.gruppo is not None:
            self._toast("Aspetta la fine dell'elaborazione", ok=False)
            return
        if not self.items:
            return
        self.items = []
        self.scroll = 0
        self._azzera_selezione()
        self.prog_meta = 0.0
        self._imposta_prog(0.0)
        self._ridisegna_lista()
        self._aggiorna_azioni()
        self._stato("Trascina qui le immagini per iniziare")

    def _azzera_selezione(self):
        self.sel = None
        self.modo_crop = False
        self.pv_orig = self.pv_dopo = self.pv_full = None
        self.risultato = None
        self._cache_orig = (None, None)
        self._tok += 1
        self._disegna_pv()
        self._aggiorna_crop_btn()
        self._aggiorna_azioni()

    def _seleziona(self, v):
        self.sel = v
        self.modo_crop = False
        self.crop_tmp = None
        self.pv_orig = self.pv_dopo = self.pv_full = None
        self.risultato = None
        if v in self.items:
            i = self.items.index(v)
            if i < self.scroll:
                self.scroll = i
            elif i >= self.scroll + NVIS:
                self.scroll = i - NVIS + 1
        self._ridisegna_lista()
        self._disegna_pv()
        self._aggiorna_crop_btn()
        self._aggiorna_azioni()
        self._richiedi_prev()

    # ---------------------------------------------------------------- parametri
    def _params(self):
        p = self.cfg
        return {"qualita": p["qualita"], "dimensione": p["dimensione"], "formato": p["formato"], "extra": p["extra"]}

    def _cambio_q(self, v):
        self.cfg["qualita"] = v
        self._param_cambiato()

    def _cambio_d(self, v):
        self.cfg["dimensione"] = v
        self._param_cambiato()

    def _cambia_formato(self, nome):
        self.cfg["formato"] = nome
        self._sel_fmt(nome)
        salva_config(self.cfg)
        self._param_cambiato()

    def _fine_param(self):
        salva_config(self.cfg)

    def _param_cambiato(self):
        self._aggiorna_azioni()
        if self._after_prev:
            try:
                self.root.after_cancel(self._after_prev)
            except Exception:
                pass
        self._after_prev = self.root.after(160, self._richiedi_prev)

    # ---------------------------------------------------------------- anteprima
    @staticmethod
    def _adatta(im, pw, ph):
        s = min(pw / im.width, ph / im.height, 1.0)
        w, h = max(1, int(im.width * s)), max(1, int(im.height * s))
        if (w, h) == im.size:
            return im.copy()
        return im.resize((w, h), Image.Resampling.LANCZOS, reducing_gap=2.0)

    def _richiedi_prev(self):
        self._after_prev = None
        v = self.sel
        if v is None or self.scena != "principale":
            return
        self._tok += 1
        tok, p, crop = self._tok, self._params(), v.crop
        pw, ph = self._px(PVW), self._px(PVH)
        intero = self.modo_crop
        self.lav_prev.richiedi(lambda: self._lavoro_prev(v, tok, p, crop, pw, ph, intero))

    def _lavoro_prev(self, v, tok, p, crop, pw, ph, intero):
        """Gira in un thread: legge l'immagine (una volta sola), la comprime e prepara i pezzi dell'anteprima."""
        r = {"it": v, "tok": tok}
        try:
            if self._cache_orig[0] is not v:
                self._cache_orig = (None, None)
                self._cache_orig = (v, apri_immagine(v.percorso))
            im = self._cache_orig[1]
            v.dim = im.size
            if intero:
                r["pv_full"] = self._adatta(a_rgb(im), pw, ph)
                r["scala"] = r["pv_full"].width / im.width
                return r
            base = ritaglia(im, crop)
            r["dim0"] = base.size
            r["pv_orig"] = self._adatta(a_rgb(base), pw, ph)
            dati, dim = elabora(im, crop, p["dimensione"], p["qualita"], p["formato"], p["extra"])
            dec = Image.open(io.BytesIO(dati))
            dec.load()
            r["pv_dopo"] = a_rgb(dec).resize(r["pv_orig"].size, Image.Resampling.LANCZOS)
            r["bytes"], r["dim"] = dati, dim
            r["firma"] = (crop, p["dimensione"], p["qualita"], p["formato"], p["extra"])
        except Exception as e:
            _log.exception("Anteprima")
            r = {"it": v, "tok": tok, "err": messaggio_errore(e)}
        return r

    def _ric_prev(self, r):
        v = r.get("it")
        if v not in self.items:
            return
        if r.get("err"):
            v.err = r["err"]
            self._sporca = True
            if v is self.sel and r["tok"] == self._tok:
                self.pv_orig = self.pv_dopo = self.risultato = None
                self._disegna_pv()
                self._aggiorna_azioni()
            return
        if v.err:
            v.err = None
            self._sporca = True
        if r["tok"] != self._tok or v is not self.sel:
            return
        if "pv_full" in r:
            self.pv_full, self.pv_scala = r["pv_full"], r["scala"]
            if self.modo_crop:
                self._disegna_pv()
            return
        self.pv_orig, self.pv_dopo = r["pv_orig"], r["pv_dopo"]
        self.risultato = {"it": v, "firma": r["firma"], "bytes": r["bytes"], "dim": r["dim"], "dim0": r["dim0"]}
        self._sporca = True
        if not self.modo_crop:
            self._disegna_pv()
        self._aggiorna_azioni()

    def _pv_msg(self, t1, t2="", errore=False):
        c, cv = self.c, self.cv
        cx = self._px(PVX + PVW / 2)
        y0 = self._px(PVY + PVH / 2 - 34)
        f = self._foto(self._icona_foto(self._px(56)))
        ico = cv.create_image(cx, y0, image=f, anchor="center", tags="pv")
        t = self._testo(PVX + PVW / 2, PVY + PVH / 2 + 8, t1, FONT_G, c["err"] if errore else c["fg"], anchor="n", tags="pv")
        if t2:
            self._testo(PVX + PVW / 2, PVY + PVH / 2 + 34, t2, FONT_S, c["mut"], anchor="n", tags="pv")
        self.anim.avvia("float", None, lambda s: cv.coords(ico, cx, y0 + self._px(3) * math.sin(s * 2.2)))

    def _chip(self, x, y, testo, ancora="nw"):
        c, cv = self.c, self.cv
        w, h = int(self._mis(testo, FONT_SB)) + 20, 24
        f = self._foto(_capa_rr(self._px(w), self._px(h), self._px(h / 2), (0, 0, 0, 150), (255, 255, 255, 50)))
        cv.create_image(self._px(x), self._px(y), image=f, anchor=ancora, tags="pv")
        dx = w / 2 if ancora == "nw" else -w / 2
        cv.create_text(self._px(x + dx), self._px(y + h / 2), text=testo, font=FONT_SB, fill="#ffffff", tags="pv")

    def _disegna_pv(self):
        if self.scena != "principale":
            return
        cv = self.cv
        cv.delete("pv")
        self.anim.ferma("float")
        self._pv_photo, self._pv_geom = None, None
        v = self.sel
        if v is None:
            self._pv_msg("Nessuna immagine selezionata", "Aggiungine qualcuna per vedere l'anteprima")
            return
        if self.modo_crop:
            if self.pv_full is None:
                self._pv_msg("Carico l'immagine…")
            else:
                self._disegna_crop()
            return
        if self.pv_orig is None or self.pv_dopo is None:
            if v.err:
                self._pv_msg("Non riesco ad aprire questa immagine", v.err, errore=True)
            else:
                self._pv_msg("Calcolo l'anteprima…")
            return
        self._pv_immagine()
        self._didascalia()

    def _centra(self, im):
        iw, ih = im.size
        ox = self._px(PVX) + (self._px(PVW) - iw) // 2
        oy = self._px(PVY) + (self._px(PVH) - ih) // 2
        return ox, oy, iw, ih

    def _img_manopola(self):
        d = self._px(34)
        s = d * SS
        im = Image.new("RGBA", (s + 8 * SS, s + 8 * SS), (0, 0, 0, 0))
        dr = ImageDraw.Draw(im)
        o = 4 * SS
        dr.ellipse([o, o + SS, o + s, o + s + SS], fill=(0, 0, 0, 90))
        im = im.filter(ImageFilter.GaussianBlur(SS * 1.5))
        dr = ImageDraw.Draw(im)
        dr.ellipse([o, o, o + s - 1, o + s - 1], fill=(255, 255, 255, 255))
        cx, cy, a = o + s / 2, o + s / 2, s * .16
        scuro = _rgb(self.c["grad"][0]) + (255,)
        dr.polygon([(cx - a * .4, cy), (cx - a * 1.5, cy - a), (cx - a * 1.5, cy + a)], fill=scuro)
        dr.polygon([(cx + a * .4, cy), (cx + a * 1.5, cy - a), (cx + a * 1.5, cy + a)], fill=scuro)
        return im.resize((d + 8, d + 8), Image.Resampling.LANCZOS)

    def _compone(self):
        iw, ih = self.pv_orig.size
        sx = int(self.split * iw)
        comp = self.pv_orig.copy()
        if sx < iw:
            comp.paste(self.pv_dopo.crop((sx, 0, iw, ih)), (sx, 0))
        return comp

    def _pv_immagine(self):
        cv = self.cv
        ox, oy, iw, ih = self._centra(self.pv_orig)
        self._pv_geom = (ox, oy, iw, ih)
        self._pv_photo = self._ImageTk.PhotoImage(self._compone())
        self._pv_item = cv.create_image(ox, oy, image=self._pv_photo, anchor="nw", tags="pv")
        sx = ox + int(self.split * iw)
        self._pv_linea = cv.create_line(sx, oy, sx, oy + ih, fill="#ffffff", width=2, tags="pv")
        self._f_manopola = self._foto(self._img_manopola())
        self._pv_man = cv.create_image(sx, oy + ih // 2, image=self._f_manopola, anchor="center", tags="pv")
        k = self.k
        self._chip(ox / k + 10, oy / k + 10, "Originale", "nw")
        self._chip((ox + iw) / k - 10, oy / k + 10, "Compressa", "ne")

    def _aggiorna_split(self):
        if not self._pv_geom or self._pv_photo is None or self.pv_orig is None:
            return
        ox, oy, iw, ih = self._pv_geom
        self._pv_photo.paste(self._compone())
        sx = ox + int(self.split * iw)
        self.cv.coords(self._pv_linea, sx, oy, sx, oy + ih)
        self.cv.coords(self._pv_man, sx, oy + ih // 2)

    def _didascalia(self):
        c = self.c
        r, v = self.risultato, self.sel
        if not r or r["it"] is not v:
            return
        nuovo = len(r["bytes"])
        pct = risparmio(v.peso, nuovo)
        segno = f"−{pct}%" if pct > 0 else (f"+{-pct}%" if pct < 0 else "0%")
        self._testo(PVX + 12, 400, f"{fmt_peso(v.peso)}  →  {fmt_peso(nuovo)}", FONT_G, anchor="w", tags="pv")
        self._testo(PVX + PVW - 12, 400, segno, FONT_G, c["ok"] if pct >= 0 else c["warn"], anchor="e", tags="pv")
        w0, h0 = r["dim0"]
        w1, h1 = r["dim"]
        self._testo(PVX + 12, 418, f"{w0}×{h0}  →  {w1}×{h1} px", FONT_S, c["mut"], anchor="w", tags="pv")

    def _hover_pv(self, e):
        if self.scena != "principale" or self._drag:
            return
        g = self._pv_geom
        dentro = bool(g and g[0] <= e.x <= g[0] + g[2] and g[1] <= e.y <= g[1] + g[3])
        if dentro:
            self.cv.config(cursor="crosshair" if self.modo_crop else "sb_h_double_arrow")
            self._cur_pv = True
        elif getattr(self, "_cur_pv", False):
            self.cv.config(cursor="")
            self._cur_pv = False

    # ---------------------------------------------------------------- ritaglio
    def _aggiorna_crop_btn(self):
        if self.scena != "principale":
            return
        v = self.sel
        corre = self.gruppo is not None
        self.btn["ritaglia"].imposta("Fatto" if self.modo_crop else "Ritaglia",
                                     abilitato=bool(v) and not corre, primario=self.modo_crop)
        self.btn["azzera"].imposta(abilitato=bool(v and v.crop) and not corre)

    def _toggle_crop(self):
        v = self.sel
        if v is None or self.gruppo is not None:
            return
        self.modo_crop = not self.modo_crop
        self.crop_tmp = None
        self._aggiorna_crop_btn()
        if self.modo_crop:
            self._disegna_pv()
            if self.pv_full is None:
                self._richiedi_prev()
        else:
            self.pv_orig = self.pv_dopo = None
            self.risultato = None
            self._disegna_pv()
            self._richiedi_prev()
            self._ridisegna_lista()
        self._aggiorna_azioni()

    def _azzera_crop(self):
        v = self.sel
        if v is None or not v.crop:
            return
        v.crop = None
        self._aggiorna_crop_btn()
        self._ridisegna_lista()
        if self.modo_crop:
            self._disegna_pv()
        else:
            self._richiedi_prev()

    def _rett_crop(self):
        """Rettangolo di ritaglio in pixel dell'anteprima (relativi all'immagine mostrata)."""
        if self.crop_tmp:
            x0, y0, x1, y1 = self.crop_tmp
            return min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)
        v = self.sel
        if v and v.crop:
            s = self.pv_scala
            x0, y0, x1, y1 = v.crop
            return x0 * s, y0 * s, x1 * s, y1 * s
        return None

    def _compone_crop(self):
        base = self.pv_full
        rc = self._rett_crop()
        if not rc:
            return base
        x0, y0, x1, y1 = [int(round(t)) for t in rc]
        x0, y0 = clamp(x0, 0, base.width), clamp(y0, 0, base.height)
        x1, y1 = clamp(x1, 0, base.width), clamp(y1, 0, base.height)
        if x1 - x0 < 1 or y1 - y0 < 1:
            return base
        scuro = Image.blend(base, Image.new("RGB", base.size, (0, 0, 0)), .58)
        scuro.paste(base.crop((x0, y0, x1, y1)), (x0, y0))
        return scuro

    def _disegna_crop(self):
        cv = self.cv
        ox, oy, iw, ih = self._centra(self.pv_full)
        self._pv_geom = (ox, oy, iw, ih)
        self._pv_photo = self._ImageTk.PhotoImage(self._compone_crop())
        cv.create_image(ox, oy, image=self._pv_photo, anchor="nw", tags="pv")
        self._pv_rett = cv.create_rectangle(0, 0, 0, 0, outline="#ffffff", width=2, dash=(5, 3), tags="pv")
        self._chip(ox / self.k + 10, oy / self.k + 10, "Trascina per ritagliare", "nw")
        self._info_crop = self._testo(PVX + 12, 400, "", FONT_G, anchor="w", tags="pv")
        self._testo(PVX + 12, 418, "Premi «Fatto» quando hai finito. «Azzera» toglie il ritaglio.", FONT_S, self.c["mut"],
                    anchor="w", tags="pv")
        self._aggiorna_crop_vista()

    def _aggiorna_crop_vista(self):
        if not self._pv_geom or self._pv_photo is None or self.pv_full is None:
            return
        ox, oy, iw, ih = self._pv_geom
        self._pv_photo.paste(self._compone_crop())
        rc = self._rett_crop()
        if rc:
            self.cv.coords(self._pv_rett, ox + rc[0], oy + rc[1], ox + rc[2], oy + rc[3])
            s = self.pv_scala or 1
            self.cv.itemconfig(self._info_crop, text=f"Ritaglio: {int((rc[2] - rc[0]) / s)}×{int((rc[3] - rc[1]) / s)} px")
        else:
            self.cv.coords(self._pv_rett, 0, 0, 0, 0)
            self.cv.itemconfig(self._info_crop, text="Nessun ritaglio")

    # ---------------------------------------------------------------- mouse
    def _press(self, e):
        if self.scena != "principale":
            return
        for cur in self.cursori:
            if cur.colpisce(e.x, e.y):
                self._drag = ("cur", cur)
                cur.inizia(e.x)
                return
        g = self._pv_geom
        if g and g[0] <= e.x <= g[0] + g[2] and g[1] <= e.y <= g[1] + g[3]:
            if self.modo_crop:
                x, y = e.x - g[0], e.y - g[1]
                self.crop_tmp = [x, y, x, y]
                self._drag = ("crop",)
            else:
                self._drag = ("split",)
                self._set_split(e.x)

    def _set_split(self, ex):
        g = self._pv_geom
        if g:
            self.split = clamp((ex - g[0]) / max(1, g[2]), 0.0, 1.0)
            self._aggiorna_split()

    def _motion(self, e):
        d = self._drag
        if not d:
            return
        if d[0] == "cur":
            d[1].muovi(e.x)
        elif d[0] == "split":
            self._set_split(e.x)
        elif d[0] == "crop" and self._pv_geom:
            g = self._pv_geom
            self.crop_tmp[2] = clamp(e.x - g[0], 0, g[2])
            self.crop_tmp[3] = clamp(e.y - g[1], 0, g[3])
            self._aggiorna_crop_vista()

    def _release(self, e):
        d, self._drag = self._drag, None
        if not d:
            return
        if d[0] == "cur":
            d[1].fine()
        elif d[0] == "crop":
            rc = self._rett_crop()
            self.crop_tmp = None
            v = self.sel
            if rc and v and self.pv_full is not None and (rc[2] - rc[0]) >= self._px(12) and (rc[3] - rc[1]) >= self._px(12):
                s = self.pv_scala or 1
                im = self._cache_orig[1] if self._cache_orig[0] is v else None
                lim_w = im.width if im else int(self.pv_full.width / s)
                lim_h = im.height if im else int(self.pv_full.height / s)
                v.crop = (clamp(int(rc[0] / s), 0, lim_w - 1), clamp(int(rc[1] / s), 0, lim_h - 1),
                          clamp(int(round(rc[2] / s)), 1, lim_w), clamp(int(round(rc[3] / s)), 1, lim_h))
                self._aggiorna_crop_btn()
                self._ridisegna_lista()
            self._aggiorna_crop_vista()

    # ---------------------------------------------------------------- stato e progresso
    def _stato(self, testo, col="mut"):
        self.testo_stato, self.colore_stato = testo, col
        if self.scena == "principale":
            self.cv.itemconfig(self.t_stato, text=testo, fill=self.c[col])

    def _disegna_prog(self, v):
        self.prog_vis = v
        if self.scena != "principale":
            return
        if v <= 0.001:
            self.cv.itemconfig(self.it_prog, image="")
            return
        W, H = self._px(290), self._px(8)
        fw = max(H, int(W * clamp(v, 0, 1)))
        self._f_prog = self._ImageTk.PhotoImage(img_bottone(fw, H, H // 2, _rgb(self.c["acc1"]), _rgb(self.c["acc2"])))
        self.cv.itemconfig(self.it_prog, image=self._f_prog)

    def _imposta_prog(self, meta):
        inizio = self.prog_vis
        self.prog_meta = meta
        if abs(meta - inizio) < 0.001:
            self._disegna_prog(meta)
            return
        self.anim.avvia("prog", 260, lambda e: self._disegna_prog(inizio + (meta - inizio) * e))

    def _aggiorna_azioni(self):
        if self.scena != "principale":
            return
        v, corre = self.sel, self.gruppo is not None
        r = self.risultato
        pronto = bool(v and r and r["it"] is v and r["firma"] == firma_di(v, self._params()) and not self.modo_crop)
        for k in ("salva", "copia_file", "copia_img"):
            self.btn[k].imposta(abilitato=pronto and not corre)
        self.btn["cartella"].imposta(abilitato=True)
        if not corre:
            self.btn["inizia"].imposta(abilitato=bool(self.items))

    # ---------------------------------------------------------------- elaborazione in blocco
    def _cartella_uscita(self) -> Path:
        d = self.cfg.get("cartella_uscita")
        return Path(d) if d else cartella_uscita_default()

    def _crea_lavoro(self, v, out, p, crop):
        firma = (crop, p["dimensione"], p["qualita"], p["formato"], p["extra"])

        def lavoro(stop):
            if stop.is_set():
                return {"voce": v, "annullato": True}
            try:
                im = apri_immagine(v.percorso)
                dati, dim = elabora(im, crop, p["dimensione"], p["qualita"], p["formato"], p["extra"])
                scrivi_byte_atomico(out, dati)
                return {"voce": v, "out": out, "peso": len(dati), "dim": dim, "firma": firma}
            except Exception as e:
                _log.exception("Errore con %s", v.nome)
                return {"voce": v, "err": messaggio_errore(e)}
        return lavoro

    def _inizia(self):
        if self.gruppo is not None:
            return
        if not self.items:
            self._toast("Aggiungi prima qualche immagine", ok=False)
            return
        if self.modo_crop:
            self._toggle_crop()
        p = self._params()
        da = [v for v in self.items if not (v.salvato and v.salvato["firma"] == firma_di(v, p))]
        if not da:
            self._toast("È già tutto fatto con queste impostazioni")
            return
        dest = self._cartella_uscita()
        try:
            dest.mkdir(parents=True, exist_ok=True)
        except OSError:
            self._toast("Non riesco a creare la cartella dei risultati: scegline un'altra in Impostazioni", ok=False)
            return
        ext = FORMATI[p["formato"]][1]
        riservati, lavori = set(), []
        for v in da:
            v.err, v.stato = None, "coda"
            lavori.append(self._crea_lavoro(v, nome_uscita(dest, v.percorso.stem, ext, riservati), dict(p), v.crop))
        self.tot, self.fatti, self.ok_n, self.err_n = len(da), 0, 0, 0
        self.somma_in = self.somma_out = 0
        self.annullato = False
        self.dest_ultima = dest
        self.anim.ferma("conta")
        self.prog_vis = 0.0
        self._disegna_prog(0.0)
        self.gruppo = Gruppo(self.q, lavori, min(3, os.cpu_count() or 2))
        self.btn["inizia"].imposta("Annulla", self._annulla, primario=False, abilitato=True)
        self._aggiorna_crop_btn()
        self._aggiorna_azioni()
        self._ridisegna_lista()
        self._stato(f"Elaboro 0/{self.tot}…", "fg")

    def _annulla(self):
        if self.gruppo is None or self.annullato:
            return
        self.annullato = True
        self.gruppo.stop.set()
        self.btn["inizia"].imposta("Annullo…", abilitato=False)
        self._stato("Annullo…", "warn")

    def _ric_blocco(self, r):
        if self.gruppo is None:
            return
        v = r["voce"]
        self.fatti += 1
        if r.get("annullato"):
            v.stato = ""
        elif r.get("err"):
            v.err, v.stato = r["err"], "err"
            self.err_n += 1
        else:
            v.err, v.stato = None, "ok"
            v.salvato = {"firma": r["firma"], "percorso": r["out"], "peso": r["peso"], "dim": r["dim"]}
            self.ok_n += 1
            self.somma_in += v.peso
            self.somma_out += r["peso"]
        self._sporca = True
        self._imposta_prog(self.fatti / max(1, self.tot))
        if self.fatti >= self.tot:
            self._fine_blocco()
        elif not self.annullato:
            self._stato(f"Elaboro {self.fatti}/{self.tot}…", "fg")

    def _fine_blocco(self):
        self.gruppo = None
        if self.scena == "principale":
            self.btn["inizia"].imposta("▶  Inizia", self._inizia, primario=True, abilitato=bool(self.items))
        self._aggiorna_crop_btn()
        self._aggiorna_azioni()
        self._ridisegna_lista()
        ok, err, a, b = self.ok_n, self.err_n, self.somma_in, self.somma_out
        if not ok:
            self._stato("Annullato" if self.annullato else "Nessuna immagine salvata", "warn" if err or self.annullato else "mut")
            self._toast("Nessuna immagine salvata" if not err else f"Errore su {err} immagini: guarda l'elenco", ok=False)
            return
        pct = risparmio(a, b)
        col = "ok" if err == 0 and pct >= 0 else "warn"
        nome = "1 immagine" if ok == 1 else f"{ok} immagini"

        def conta(e):
            nuovo = int(a - (a - b) * e)
            p_ = risparmio(a, nuovo)
            segno = f"−{p_}%" if p_ > 0 else (f"+{-p_}%" if p_ < 0 else "0%")
            testo = f"{'Fermato dopo ' if self.annullato else ''}{nome} · {fmt_peso(a)} → {fmt_peso(nuovo)} · {segno}"
            self._stato(testo, col)
        self.anim.avvia("conta", 900, conta)
        if self.annullato:
            self._toast(f"Annullato: {nome} salvate", ok=True)
        elif err:
            self._toast(f"{nome} salvate, {err} con errori: guarda l'elenco", ok=False)
        else:
            risp = a - b
            self._toast(f"Fatto: {nome}, risparmiati {fmt_peso(risp)}" if risp > 0 else f"Fatto: {nome} salvate")
        if self.cfg.get("apri_a_fine") and not self.annullato:
            apri_cartella(self.dest_ultima)

    # ---------------------------------------------------------------- una sola immagine
    def _salva_corrente(self, silenzioso=False):
        v, r = self.sel, self.risultato
        if v is None or not r or r["it"] is not v or r["firma"] != firma_di(v, self._params()):
            self._toast("Aspetta un attimo: l'anteprima si sta aggiornando", ok=False)
            return None
        s = v.salvato
        if s and s["firma"] == r["firma"] and Path(s["percorso"]).exists():
            if not silenzioso:
                self._toast(f"Già salvata: {Path(s['percorso']).name}")
            return Path(s["percorso"])
        dest = self._cartella_uscita()
        try:
            dest.mkdir(parents=True, exist_ok=True)
            out = nome_uscita(dest, v.percorso.stem, FORMATI[self.cfg["formato"]][1], set())
            scrivi_byte_atomico(out, r["bytes"])
        except Exception as e:
            _log.exception("Salvataggio")
            self._toast(f"Non sono riuscito a salvare ({messaggio_errore(e)})", ok=False)
            return None
        v.salvato = {"firma": r["firma"], "percorso": out, "peso": len(r["bytes"]), "dim": r["dim"]}
        v.stato, v.err = "ok", None
        self._ridisegna_lista()
        if not silenzioso:
            self._toast(f"Salvata: {out.name}")
        return out

    def _salva_click(self):
        self._salva_corrente()

    def _copia_file(self):
        out = self._salva_corrente(silenzioso=True)
        if out is None:
            return
        if copia_file_negli_appunti([out], self.hwnd):
            self._toast("File copiato: ora puoi incollarlo dove vuoi")
        else:
            self._toast("Non sono riuscito a copiare negli appunti", ok=False)

    def _copia_img(self):
        r = self.risultato
        if not r or r["it"] is not self.sel:
            return
        try:
            with Image.open(io.BytesIO(r["bytes"])) as im:
                im.load()
                ok = copia_immagine_negli_appunti(im, self.hwnd)
        except Exception:
            _log.exception("Copia immagine")
            ok = False
        self._toast("Immagine copiata: incollala in un documento" if ok else "Non sono riuscito a copiare l'immagine",
                    ok=ok)

    def _apri_risultati(self):
        d = self._cartella_uscita()
        if d.is_dir():
            apri_cartella(d)
        else:
            self._toast("La cartella dei risultati si crea al primo salvataggio")

    # ---------------------------------------------------------------- impostazioni
    def _scena_impostazioni(self):
        self._scena("impostazioni")
        c, p = self.c, self.cfg
        self._card(200, 84, 740, 568)
        self._sfondo_pronto()
        self._intestazione("Impostazioni", indietro=True)

        self._testo(232, 108, "Cartella dei risultati", FONT_B)
        self.t_dest = self._testo(232, 132, self._ellissi(str(self._cartella_uscita()), FONT_S, 470), FONT_S, c["mut"])
        self._bottone(232, 160, 110, 32, "Cambia…", self._scegli_dest, font=FONT)
        self._bottone(350, 160, 90, 32, "Apri", self._apri_risultati, font=FONT)
        self._testo(232, 198, "Si crea da sola la prima volta che salvi un'immagine.", FONT_S, c["mut"])

        self._testo(232, 238, "Tema", FONT_B)
        self._pillole(232, 262, ["Scuro", "Chiaro"], self.tema.capitalize(), self._imposta_tema, w=96)

        self._testo(232, 314, "Compressione extra", FONT_B)
        self._pillole(232, 338, ["Sì", "No"], "Sì" if p["extra"] else "No", lambda n: self._imposta_bool("extra", n), w=96)
        self._testo(232, 376, "Più lenta, ma i file escono più piccoli (JPG progressivo, WebP al massimo).",
                    FONT_S, c["mut"], width=480)

        self._testo(232, 420, "Apri la cartella quando finisce", FONT_B)
        self._pillole(232, 444, ["Sì", "No"], "Sì" if p["apri_a_fine"] else "No",
                      lambda n: self._imposta_bool("apri_a_fine", n), w=96)

        self.cv.create_line(self._px(232), self._px(496), self._px(708), self._px(496), fill=c["linea"])
        self._testo(232, 500, "Le impostazioni sono salvate in %APPDATA%\\ComprimiImmaginiRapido\\config.json", FONT_S,
                    c["mut"], width=476)
        self._bottone(232, 528, 150, 30, "Ripristina valori", self._ripristina, font=FONT_S)
        self._entra_scena()

    def _imposta_tema(self, nome):
        self.tema = nome.lower()
        self.cfg["tema"] = self.tema
        salva_config(self.cfg)
        self._scena_impostazioni()

    def _imposta_bool(self, chiave, nome):
        self.cfg[chiave] = nome == "Sì"
        salva_config(self.cfg)
        self._scena_impostazioni()

    def _scegli_dest(self):
        ini = self._cartella_uscita()
        while not ini.exists() and ini.parent != ini:
            ini = ini.parent
        d = filedialog.askdirectory(parent=self.root, title="Scegli la cartella dei risultati", mustexist=False,
                                    initialdir=str(ini))
        if d:
            self.cfg["cartella_uscita"] = str(Path(d))
            salva_config(self.cfg)
            self.cv.itemconfig(self.t_dest, text=self._ellissi(str(Path(d)), FONT_S, 470))

    def _ripristina(self):
        self.cfg.update(CONFIG_DEFAULT)
        self.tema = self.cfg["tema"]
        salva_config(self.cfg)
        self._scena_impostazioni()
        self._toast("Impostazioni ripristinate")

    # ---------------------------------------------------------------- guida
    def _scena_guida(self):
        self._scena("guida")
        c = self.c
        vy0, vy1 = 100, 548
        self._card(150, 84, 790, 560)
        self._sfondo_pronto()
        y = vy0 + 4
        for riga in GUIDA.strip().splitlines():
            if not riga.strip():
                y += 6
                continue
            if riga == riga.upper() and len(riga) > 3 and not riga.startswith("•"):
                y += 12
                self._testo(178, y, riga, FONT_B, c["acc1"], tags="gscroll")
                y += 24
            else:
                it = self._testo(178, y, riga, FONT, c["fg"], width=552, tags="gscroll")
                bb = self.cv.bbox(it)
                y = bb[3] / self.k + 5
        self._g_vis = vy1 - vy0
        self._g_max = max(0, int(y - vy0 - self._g_vis + 12))
        self._g_off = 0
        top = self._foto(self.base.crop((0, 0, self._px(W_WIN), self._px(vy0))))
        bot = self._foto(self.base.crop((0, self._px(vy1), self._px(W_WIN), self._px(H_WIN))))
        self.cv.create_image(0, 0, image=top, anchor="nw")
        self.cv.create_image(0, self._px(vy1), image=bot, anchor="nw")
        self._intestazione("Guida", indietro=True)
        self._bottone(370, 574, 200, 44, "Ho capito", self._scena_principale, primario=True)
        self._g_vy = (vy0, vy1)
        self._f_gbar = None
        self._g_barra = self.cv.create_image(self._px(772), self._px(vy0), anchor="nw")
        self._aggiorna_barra_guida()
        self._entra_scena()

    def _scorri_guida(self, d):
        nuovo = clamp(self._g_off + d * 56, 0, self._g_max)
        if nuovo != self._g_off:
            self.cv.move("gscroll", 0, -self._px(nuovo - self._g_off))
            self._g_off = nuovo
            self._aggiorna_barra_guida()

    def _aggiorna_barra_guida(self):
        if self._g_max <= 0:
            return
        vy0, vy1 = self._g_vy
        tot = self._g_vis
        h = max(30, tot * tot / (tot + self._g_max))
        y0 = vy0 + (tot - h) * self._g_off / self._g_max
        self._f_gbar = self._ImageTk.PhotoImage(_capa_rr(self._px(4), self._px(h), self._px(2),
                                                         tuple(self.c["vetro_hover"][:3]) + (140,)))
        self.cv.itemconfig(self._g_barra, image=self._f_gbar)
        self.cv.coords(self._g_barra, self._px(772), self._px(y0))

    # ---------------------------------------------------------------- segnalazioni, chiusura, avvio
    def _segnala(self):
        try:
            sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
            percorso = crea_report(self.cfg, f"{sw}x{sh} (scala {self.k:.2f})")
        except Exception as e:
            _log.exception("Impossibile creare il report")
            self._toast(f"Non sono riuscito a creare il report ({e}). Scrivimi lo stesso a {EMAIL}", ok=False)
            return
        mostra_file(percorso)
        oggetto = urllib.parse.quote(f"Segnalazione {APP} {VERSION}")
        corpo = urllib.parse.quote("Cosa stavo facendo e cosa è andato storto:\n\n(allega il file di report)")
        try:
            webbrowser.open(f"mailto:{EMAIL}?subject={oggetto}&body={corpo}")
        except Exception:
            pass
        self._toast(f"Report creato: {percorso.name}. Allegalo alla mail (nessuna immagine dentro)")

    def _chiudi(self):
        if self.gruppo is not None:
            self.gruppo.stop.set()
        try:
            self.root.destroy()
        except Exception:
            pass

    def _controlla_richiamo(self):
        """Una seconda copia del programma ci chiede di mostrarci: riportiamo la finestra davanti."""
        try:
            if _EVENTO and ctypes.windll.kernel32.WaitForSingleObject(ctypes.c_void_p(_EVENTO), 0) == 0:
                self._porta_in_primo_piano()
        except Exception:
            pass
        try:
            self.root.after(300, self._controlla_richiamo)
        except Exception:
            pass

    def _porta_in_primo_piano(self):
        r = self.root
        try:
            r.deiconify()
            r.lift()
            r.attributes("-topmost", True)
            r.focus_force()
            r.after(400, lambda: r.attributes("-topmost", False))
        except Exception:
            pass

    def run(self):
        self.root.mainloop()


def main():
    if not _istanza_unica():
        return
    avvia_log()
    togli_tmp(CONFIG_DIR)
    pulisci_temporanei()
    try:
        cfg = carica_config()
        dest = Path(cfg["cartella_uscita"]) if cfg["cartella_uscita"] else cartella_uscita_default()
        if dest.is_dir():
            togli_tmp(dest)
        App().run()
    except Exception as e:
        _log.exception("Errore all'avvio")
        if IS_WIN:
            ctypes.windll.user32.MessageBoxW(0, f"{APP} non è riuscito ad avviarsi:\n{e}", APP, 0x10)
        else:
            raise


if __name__ == "__main__":
    main()
