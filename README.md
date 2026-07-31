# PulseMix

Lecteur audio Android (Kotlin / Jetpack Compose / Media3) avec analyse musicale
embarquée : BPM, tonalité, énergie, « meilleure minute » — et deux modes
d'enchaînement façon soirée : **Mix** et **DJ**.

## Fonctionnalités

- **Lecture classique** : mp3, m4a/aac, flac, ogg/vorbis, opus, wav (tout ce
  que décode Android). Sélection d'un dossier (Storage Access Framework, aucun
  accès global au stockage), play/pause, précédent/suivant, shuffle, barre de
  progression.
- **Bluetooth / casque / voiture / notification** : MediaSession Media3, donc
  les commandes AVRCP (autoradio, enceinte, montre, écouteurs) fonctionnent
  partout, et la notification média affiche titre + play/pause/suivant/
  précédent — y compris en mode DJ (le morceau réellement joué est recopié
  dans les métadonnées). « Play » depuis la voiture relance la dernière file
  restaurée. En Mix et DJ, *next/previous* changent de **phase**.
- **Jaquettes** : pochettes embarquées affichées sur l'écran lecteur et en
  vignettes dans la bibliothèque (cache mémoire).
- **Lecteur audio par défaut** : PulseMix s'ouvre sur les fichiers audio
  (gestionnaire de fichiers, navigateur…) et les lit directement.
- **Analyse en tâche de fond** : l'analyse tourne dans un service en
  avant-plan avec notification de progression et wake lock — elle continue
  écran éteint ou appli quittée.
- **Analyse au premier scan** (stockée dans `library.json`, une seule fois par
  fichier) :
  - **BPM** : enveloppe d'attaques par flux spectral (FFT 2048, hop 1024) +
    autocorrélation avec repli d'octaves (60–190 BPM).
  - **Tonalité** : chromagramme 12 classes + profils de Krumhansl-Kessler
    (24 tonalités) → clé (ex. `Am`) et code **Camelot** (ex. `8A`) pour le
    mixage harmonique.
  - **Énergie** : RMS moyen/pic, centroïde spectral (brillance), densité
    d'attaques.
  - **Meilleur passage** : départ calé sur le **drop** (la montée d'énergie
    soutenue la plus franche autour de la fenêtre la plus énergique), fin à
    la première **retombée durable** d'énergie (40-90 s, arrondie aux
    phrases de 16 temps), plafonnée à 60 % de la durée sur les morceaux
    courts, avec une **ancre de premier beat** pour le calage DJ.
  - **Contrôles** : l'analyse peut être stoppée proprement, reprise là où
    elle en était, ou relancée de zéro depuis la bibliothèque.
- **Reprise de session** : l'état de lecture (mode, file, mix, phase, morceau
  et position) est sauvegardé en continu et restauré au démarrage — après une
  fermeture, une mise en veille ou un plantage, l'appli repart au même
  endroit (en pause). En cas de crash, la trace est écrite dans
  `Android/data/com.pulsemix.app/files/crash_log.txt`.
- **Bibliothèque durable** : les analyses sont aussi sauvegardées dans le
  dossier de musique (`PulseMix.library.json`). Après une désinstallation ou
  sur un autre appareil, re-choisir le dossier restaure tout sans réanalyser.
  À la désinstallation, Android propose en plus de conserver les données de
  l'app (`hasFragileUserData`).
- **Mises à jour d'APK sans conflit** : tous les builds (debug et release)
  sont signés avec la clé partagée `keystore/pulsemix.jks` committée dans le
  dépôt — un nouvel APK met à jour l'app installée au lieu d'être refusé.
  (Clé publique par construction : ne pas publier sur un store avec.)
  Si une version installée avait été signée autrement, il faut désinstaller
  une dernière fois.
- **Types de musique déduits** : quand un fichier n'a pas de tag genre, le
  type est déduit des mots-clés du titre/artiste (techno, rap, rock, lo-fi…)
  ou, à défaut, de la **signature acoustique** de l'analyse (BPM, énergie,
  brillance, attaques → familles « ≈ électro/danse », « ≈ calme/ambient »,
  etc.) — utilisable dans les mix par genre comme un vrai tag.
- **v1.3** : mix/DJ **par genre** (chips de genres + genres au profil proche),
  **enregistrement du set DJ** en M4A (bouton ⏺, fichiers dans
  Android/data/…/files/Mixes), **répétition des transitions** (seules les
  jonctions sont jouées), **limiteur doux** en sortie DJ, bouton
  « transition ratée » (paires évitées ensuite), jamais deux morceaux du même
  artiste d'affilée, **meilleur passage définissable à la main** (verrouillé),
  **playlists nommées** (depuis la file, export M3U), filtre « non analysés »,
  réglages et historique inclus dans la sauvegarde du dossier, cache disque
  des jaquettes.
- **Écran Réglages** : saut des intros parlées, normalisation du volume
  (activée par défaut, appliquée aussi aux decks DJ), égaliseur
  graves/médiums/aigus (lecture classique et moteur DJ).
- **Bibliothèque enrichie** : recherche (titre/artiste), tri (titre, BPM,
  énergie, tonalité), filtre « compatibles avec le morceau en cours »
  (BPM ±8 % double/moitié compris + roue Camelot), **multi-dossiers**,
  statistiques (répartition des tempos et tonalités), et un menu par morceau :
  pré-écoute du meilleur passage, mix/DJ « comme ce morceau » (similarité de
  style et d'énergie), favori, exclusion des mix, correction manuelle du BPM
  (tap tempo, verrouillée contre la réanalyse), suppression du fichier.
- **Mix évolués** : plan « Auto-DJ » (toute la bibliothèque enchaînée),
  durée cible (30 min / 1 h / 2 h), propositions régénérables, plans
  éditables avant lancement (retirer des morceaux), **file d'attente
  éditable pendant la lecture** (retirer, sauter à un morceau), et
  anti-répétition sur 48 h entre les sessions.
- **Minuterie de sommeil** : 15/30/60/90 min, fondu sur les 30 dernières
  secondes puis pause.
- **Analyse parallèle** : deux fichiers analysés à la fois.
- **Sauter les intros parlées** (option, désactivée par défaut) : quand un
  sketch ou un préambule parlé précède le morceau, la lecture démarre au
  début détecté de la musique (première fenêtre d'énergie soutenue au
  niveau musical du morceau ; nécessite une réanalyse pour les morceaux
  déjà analysés).
- **Musique douce** : un curseur de douceur (très doux par défaut) filtre
  strictement — seuls les morceaux sous le seuil entrent, selon un score en
  rangs percentiles de ta bibliothèque (énergie 45 %, brillance 20 %,
  densité d'attaques 15 %, BPM 20 %), joués du plus doux au moins doux.
- **Hasard contrôlé** : Douce, Mix et DJ varient à chaque lancement (tirage
  parmi les meilleurs candidats à chaque enchaînement, sélections de phases
  piochées dans une fenêtre un peu plus large) tout en respectant les
  critères ; seul le mode Normal est déterministe (ordre alphabétique,
  Shuffle en option).
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
  - **crossfade equal-power adaptatif** : 14 s quand tempos calés et
    tonalités compatibles, 8 s quand seuls les tempos sont calés, coupe
    courte de 3,5 s quand le calage est impossible (écart > ±8 %), 4 s sur
    un saut de phase — et départ de la jonction calé sur une fin de mesure.
  Les segments sont en outre modulés par la phase du mix (plus longs au
  peak, plus courts dans les phases calmes).
  Après chaque transition, le tempo **revient doucement vers le naturel**
  (~0,4 %/s, comme un pitch ramené à zéro) : les ralentissements de calage
  ne s'accumulent plus de morceau en morceau. Et sur les passages forts où
  les basses manquent, un **renfort dynamique des basses** (< 120 Hz,
  jusqu'à ~+3,5 dB, lissé) les remonte discrètement.
  Les jonctions elles-mêmes sont travaillées comme un vrai passage de mains :
  - **bass swap** : les basses du morceau entrant sont coupées pendant le
    blend, puis échangées avec celles du sortant à ~65 % du fade — une seule
    ligne de basse à la fois, fini la bouillie ;
  - **entrée en S** : l'entrant reste discret sur le premier tiers puis monte
    franchement ;
  - **verrouillage actif** : les kicks des deux decks sont suivis pendant le
    fade et le rate de l'entrant est micro-corrigé (±0,4 %) pour rester calé ;
  - côté analyse, l'ancre de premier beat est affinée à l'échantillon près et
    le BPM est interpolé en continu (parabole sur le pic d'autocorrélation).
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
