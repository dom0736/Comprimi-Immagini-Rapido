# Guida - Comprimi Immagini Rapido

## Primo avvio

Apri il programma e trascina le immagini nella finestra (anche intere cartelle), oppure usa «Aggiungi…» e «Cartella…». Non serve installare niente.  
Il programma non crea cartelle accanto a sé: le impostazioni stanno in %APPDATA%\ComprimiImmaginiRapido e i risultati nella cartella che scegli in Impostazioni. Quella dei risultati viene creata solo quando salvi la prima immagine.  


## Comprimere le immagini

1. Aggiungi le immagini: trascinale, oppure «Aggiungi…» / «Cartella…».  
2. Clicca un'immagine nell'elenco: vedi subito l'anteprima.  
3. Regola i due cursori:  
- Qualità (30–95): più alta = foto migliore e file più pesante. 75 è un buon compromesso.
- Dimensione % (5–100): riduce larghezza e altezza. Al 20% una foto da 4000 px diventa 800 px.
4. Scegli il formato: JPG va bene ovunque, WebP è più leggero e mantiene la trasparenza.  
5. Premi «Inizia»: tutte le immagini vengono salvate nella cartella dei risultati. Puoi fermarti con «Annulla».  
I valori che scegli restano salvati per la prossima volta.  


## Confrontare prima e dopo

Clicca o trascina sull'anteprima: la linea divide «Originale» (a sinistra) e «Compressa» (a destra). Sotto vedi peso e dimensioni prima → dopo e la percentuale risparmiata. Se il file diventasse più grande, la percentuale compare in giallo.  


## Ritagliare

1. Premi «Ritaglia».  
2. Trascina un rettangolo sull'immagine (puoi rifarlo quante volte vuoi).  
3. Premi «Fatto»: l'anteprima mostra solo la parte scelta.  
«Azzera» toglie il ritaglio. Il ritaglio è per singola immagine e vale anche con «Inizia»; nell'elenco le immagini ritagliate hanno una ✂.  


## Una sola immagine

Con un'immagine selezionata puoi usare, in basso:  
- Salva: salva la versione compressa nella cartella dei risultati.
- Copia file: copia il file negli appunti; poi incolli dove vuoi (mail, chat, cartella).
- Copia immagine: copia i pixel, per incollarla in un documento.
- Cartella: apre la cartella dei risultati.


## Scorciatoie

- Ctrl+O: aggiungi immagini.   • Invio: Inizia.   • Canc: toglie dall'elenco l'immagine selezionata (il file non viene toccato).
- Frecce su / giù: cambia immagine.   • Esc: chiude ritaglio, guida o impostazioni.
- Rotellina sull'elenco: scorre.


## Impostazioni

Cartella dei risultati, tema scuro o chiaro, compressione extra (più lenta, file più piccoli) e se aprire la cartella alla fine.  


## Domande frequenti

- Che formati accetta? JPG, PNG, BMP, GIF, WebP e TIFF. In uscita: JPG o WebP.
- Le immagini PNG con trasparenza? Il JPG non supporta la trasparenza: lo sfondo diventa bianco. Con WebP la trasparenza resta.
- Due file con lo stesso nome? Il programma non sovrascrive mai: il secondo diventa nome_2.jpg, e così via. Lo stesso se rielabori un'immagine con impostazioni diverse.
- Vengono modificati gli originali? No, mai.
- Le foto ruotate dal telefono? L'orientamento viene applicato. I dati EXIF (posizione, fotocamera) non vengono copiati nel file compresso.
- Le GIF animate? Viene usato solo il primo fotogramma.


## Niente file in giro

Nessun file temporaneo: i file si scrivono con un nome provvisorio che diventa definitivo solo a lavoro finito, e quelli rimasti da una chiusura anomala vengono tolti al prossimo avvio (compresa la cartella _MEI dell'exe dentro Temp, se più vecchia di un giorno). Il log è limitato a poche centinaia di KB e i vecchi report di «Segnala un problema» sul Desktop spariscono dopo 14 giorni.  
Il programma si apre una volta sola: se lo riavvii, riporta in primo piano quello già aperto.  


## Open source e segnalazioni

Comprimi Immagini Rapido 2.0 è open source (licenza MIT). Se qualcosa non funziona o hai un'idea per migliorarlo, scrivimi a domenico.castaldod29@gmail.com.  
Con «Segnala un problema» il programma crea un file di report con i dati tecnici utili per sistemare l'errore (versione, Windows, impostazioni ed errori registrati). Non contiene immagini né percorsi personali. Leggilo prima di inviarlo e allegalo alla mail, insieme a due righe su cosa stavi facendo.  
