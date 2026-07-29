# PulseMix

Lecteur audio Android (Kotlin / Jetpack Compose / Media3) avec analyse musicale
embarquée : BPM, tonalité, énergie, « meilleure minute » — et deux modes
d'enchaînement façon soirée : **Mix** et **DJ**.

## Fonctionnalités

- **Lecture classique** : mp3, m4a/aac, flac, ogg/vorbis, opus, wav (tout ce
  que décode Android). Sélection d'un dossier (Storage Access Framework, aucun
  accès global au stockage), play/pause, précédent/suivant, shuffle, barre de
  progression.
- **Bluetooth / casque / notification** : MediaSession Media3, donc les
  commandes AVRCP (autoradio, enceinte, montre, écouteurs) fonctionnent
  partout. En Mix et DJ, *next/previous* changent de **phase**.
- **Analyse au premier scan** (stockée dans `library.json`, une seule fois par
  fichier) :
  - **BPM** : enveloppe d'attaques par flux spectral (FFT 2048, hop 1024) +
    autocorrélation avec repli d'octaves (60–190 BPM).
  - **Tonalité** : chromagramme 12 classes + profils de Krumhansl-Kessler
    (24 tonalités) → clé (ex. `Am`) et code **Camelot** (ex. `8A`) pour le
    mixage harmonique.
  - **Énergie** : RMS moyen/pic, centroïde spectral (brillance), densité
    d'attaques.
  - **Meilleure minute** : fenêtre de 60 s la plus énergique, recalée sur la
    vallée d'énergie qui la précède (début de section), avec une **ancre de
    premier beat** pour le calage DJ.
- **Musique douce** : seuil de BPM réglable (60–120) + tri par « douceur »
  (énergie, brillance, densité d'attaques basses par rapport au reste de ta
  bibliothèque).
- **Mode Mix** : l'app propose plusieurs mix selon ce que contient la
  bibliothèque — *Soirée complète* (chauffe → montée → peak → pause →
  relance), *Montée progressive*, *Chill*, *Peak time*, *Vagues*, *Flow
  continu*. Les morceaux de chaque phase s'enchaînent par proximité de BPM et
  compatibilité Camelot. Morceaux entiers, bouton **next = phase suivante**.
- **Mode DJ** : même structure de mix, mais chaque morceau n'est joué que sur
  sa meilleure minute. Moteur audio dédié (2 decks → AudioTrack) :
  - le morceau entrant est **resamplé** pour caler son BPM sur le BPM effectif
    du morceau en cours (±8 %, façon pitch fader, gestion double/moitié de
    tempo) ;
  - son premier beat est **aligné à l'échantillon près** sur la grille de
    beats du deck actif ;
  - **crossfade equal-power** de 8 s (4 s sur un saut de phase).
  Pendant le mode DJ, ExoPlayer boucle une piste silencieuse à volume nul pour
  conserver le focus audio et la session active : les boutons Bluetooth
  continuent de piloter le moteur DJ.

## Compiler

1. Ouvrir le dossier dans **Android Studio** (Koala ou plus récent, JDK 17).
2. Laisser Gradle synchroniser (les dépendances se téléchargent).
3. Run ▶ sur un appareil **Android 8.0+** (API 26).

Aucune clé ni configuration nécessaire. Si le wrapper Gradle manque ou pose
problème : `File > Sync Project`, ou régénérer avec `gradle wrapper`.

## Architecture

```
analysis/  Fft, AudioDecoder (MediaExtractor+MediaCodec → PCM float),
           AudioAnalyzer (BPM, clé, énergie, meilleure minute, ancre de beat)
data/      Track + TrackStore (persistance JSON)
library/   LibraryScanner (parcours SAF + métadonnées + orchestration analyse)
mix/       MixEngine (bandes de BPM, enchaînement BPM+Camelot, propositions)
player/    PlayerCore (routage des modes), DjMixer (moteur 2 decks),
           PlaybackService (MediaSession, Bluetooth)
ui/        PlayerScreen, LibraryScreen (Compose Material 3)
```

## Limites connues (v1)

- Le calage de beat suppose un **tempo stable** sur la meilleure minute
  (électro, pop, hip-hop : très bien ; rubato, live, jazz : approximatif).
- Le resampling DJ est linéaire : la hauteur bouge avec le tempo (±8 % max,
  comportement « vinyle », peu audible).
- L'alignement se fait sur le beat, pas sur le premier temps de la mesure : la
  transition peut être décalée d'un ou deux temps musicalement.
- L'analyse prend quelques secondes par morceau au premier scan (une seule
  fois, en tâche de fond avec barre de progression).
- En mode DJ, la position affichée dans la notification n'est pas
  significative (la piste système est silencieuse) ; titre et commandes
  restent corrects dans l'app.
- Fichiers DRM non supportés.

## Pistes d'évolution

- Grille de beats complète (alignement sur le downbeat), EQ kill sur les
  transitions, affichage de la waveform, réglage de la durée des fondus,
  détection de structure (couplet/refrain) pour la « meilleure minute ».
