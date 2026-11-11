# Comprimi Immagini Rapido

Programma per Windows che **riduce il peso delle foto** senza perdere la qualità che ti serve:
trascini le immagini (o intere cartelle), regoli qualità e dimensione, controlli il confronto
*prima / dopo* e premi **Inizia**. Gli originali non vengono mai toccati.

- Anteprima con linea divisoria **Originale / Compressa**, peso e dimensioni prima → dopo
- **Qualità** e **Dimensione %** con cursori, formato di uscita **JPG** o **WebP**
- **Ritaglio** per singola immagine, direttamente sull'anteprima
- Elaborazione in blocco con barra di avanzamento e pulsante Annulla
- Per una sola immagine: **Salva**, **Copia file**, **Copia immagine** (negli appunti)
- Tema scuro o chiaro, animazioni, guida integrata e «Segnala un problema»
- Accetta JPG, PNG, BMP, GIF, WebP e TIFF; applica l'orientamento delle foto del telefono

## Niente file in giro

- Un solo file (`ComprimiImmaginiRapido.exe`), una sola finestra, nessun popup di Windows.
- Le impostazioni stanno in `%APPDATA%\ComprimiImmaginiRapido\config.json`.
- I risultati vanno nella cartella che scegli (di base `Immagini\Comprimi Immagini Rapido`),
  creata solo al primo salvataggio.
- Nessun file temporaneo: si scrive con un nome provvisorio che diventa definitivo a lavoro finito;
  eventuali resti di una chiusura anomala vengono tolti al prossimo avvio.
- Non sovrascrive mai: un nome già esistente diventa `nome_2.jpg`, `nome_3.jpg`…

## Usarlo da Python

```
pip install -r requirements.txt
python comprimi.py
```

## Creare l'exe

Il workflow `.github/workflows/build.yml` crea l'exe a ogni push (e lo pubblica in *Releases* con un tag `v*`).
In locale:

```
pip install -r requirements.txt pyinstaller
python crea_icona.py icon.png icon.ico
pyinstaller --onefile --windowed --name ComprimiImmaginiRapido --icon icon.ico ^
  --add-data "icon.png;." --add-data "marchio_comprimi.txt;." comprimi.py
```

## Struttura

| File | A cosa serve |
|---|---|
| `comprimi.py` | tutto il programma |
| `icon.png`, `crea_icona.py` | icona dell'app e dell'exe |
| `marchio_comprimi.txt` | riconosce le cartelle temporanee `_MEI` lasciate dall'exe |
| `docs/GUIDA.md` | la stessa guida che trovi dentro al programma |
| `.github/workflows/build.yml` | build automatica dell'exe |

Licenza MIT. Segnalazioni e idee: domenico.castaldod29@gmail.com
