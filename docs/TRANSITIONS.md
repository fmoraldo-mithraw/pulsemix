# Les transitions dans PulseMix — état des lieux

Ce document décrit **toutes** les transitions sonores de l'application :
qui les déclenche, comment elles sont réalisées techniquement, dans quels
cas elles s'appliquent, et où sont leurs limites. Il est écrit à partir du
code (`player/PlayerCore.kt`, `player/DjMixer.kt`, `player/PlaybackService.kt`,
`player/AlarmClock.kt`, `mix/MixEngine.kt`) et sert de référence : quand le
comportement change, ce fichier doit changer avec.

Une section finale (« Anomalies relevées ») liste ce que cette relecture a
fait ressortir, et ce qui a été corrigé dans la foulée.

---

## 1. Vue d'ensemble

PulseMix a **deux moteurs de lecture**, donc deux familles de transitions :

| Mode | Moteur | Transitions | Réglage utilisateur |
|---|---|---|---|
| **NORMAL** (lecture classique, playlists, file) | ExoPlayer (media3) | fondu croisé « deux platines » entre morceaux entiers | *Fondu croisé* on/off + durée 3-15 s |
| **DOUCE** (sélection du plus doux au moins doux) | ExoPlayer | identiques à NORMAL | idem |
| **MIX** (plan de mix : phases → morceaux entiers) | ExoPlayer | identiques à NORMAL, plus l'enchaînement automatique d'un mix au suivant | idem |
| **DJ** (set : phases → *passages forts* d'une minute) | `DjMixer` (AudioTrack + décodage maison) | jonctions battues : calage de tempo, filtres, échange de basses, drop-swap… | *Transitions pro* on/off |

À côté de ces deux familles, trois mécanismes « transversaux » modifient le
son au moment d'un changement : le **micro-fondu de pause/reprise**, la
**minuterie de sommeil** (fondu de 30 s), et le **réveil** (rampe du volume
système). Ils sont traités au §5.

Deux principes valent partout :

- **Deux sources sonnent réellement ensemble.** Un fondu croisé n'est jamais
  un fondu vers le silence suivi d'une montée : le morceau sortant continue
  pendant que l'entrant arrive.
- **Un geste prime toujours sur l'automatisme.** Suivant, précédent,
  déplacement dans le morceau, nouveau lancement : ce qui attendait est
  appliqué ou jeté, mais jamais rejoué *par-dessus* le geste.

---

## 2. Modes ExoPlayer (NORMAL, DOUCE, MIX) : le fondu croisé « deux platines »

### 2.1 Principe

Un ExoPlayer ne peut pas se superposer à lui-même. Le fondu croisé confie
donc **la fin du morceau en cours à un second lecteur** (la *queue*,
`exoTail`), qui la prolonge en s'effaçant, pendant que le lecteur principal
(`exo`) part au morceau suivant et monte depuis le silence. Le second
lecteur :

- n'a **pas le focus audio** (il est déjà tenu par le principal — le
  redemander couperait le son) ;
- n'a ni file ni session média : il ne fait que tenir la queue ;
- reçoit une **copie de l'égaliseur** du principal (basses/aigus boostés,
  filtre) pour que le timbre ne saute pas à la bascule — sauf si tout est à
  plat, auquel cas on n'insère rien dans la chaîne audio (l'insertion
  elle-même pouvait faire cliquer le mixeur).

Les deux lecteurs sont construits avec un **tampon de sortie de ~2 s** (au
lieu des 250-750 ms par défaut de media3) : c'est le temps dont dispose le
décodeur quand une autre application accapare le CPU. Leur **vitesse passe
par l'AudioTrack** (`setPlaybackParams`) et non par le ré-échantillonneur
logiciel : appliquée au son déjà en tampon, elle prend effet en quelques
dizaines de millisecondes — indispensable à l'alignement (§2.4).

### 2.2 Quand le fondu de fin de morceau se déclenche

Fenêtre de déclenchement, calculée sur la durée `d` du morceau et le réglage
`CROSSFADE_MS` (3-15 s, 10 s par défaut) :

- point de départ du fondu : `fadeAt = d − CROSSFADE_MS − CROSSFADE_LEAD_MS`
  (`CROSSFADE_LEAD_MS` = 2 s de marge pour l'ouverture de la queue) ;
- pré-armement de la queue : `prearmAt = fadeAt − PREARM_AHEAD_MS / 2`
  (6,5 s avant le fondu) ;
- conditions : fondu activé, lecture en cours, **un morceau suivant existe**,
  aucune queue déjà en vol, ce morceau n'a pas déjà eu son fondu
  (`crossfadedFrom`), et le morceau est **plus long que la fenêtre + 3 s**
  (un titre de 12 s ne part pas en fondu dès sa première seconde).

Deux déclencheurs coexistent :

1. **Messages de timeline** (`PlayerMessage`, ancrés sur la position de
   lecture) : livrés par le thread de lecture quand la lecture *atteint*
   la position, écran allumé ou non. C'est le déclencheur principal. Ils
   sont reprogrammés à chaque changement de morceau, quand la durée devient
   connue (READY / timeline), après un seek, et à la reprise après pause.
   Un **jeton de génération** invalide toute livraison périmée. Si la
   position courante a **déjà dépassé** un point (geste qui pose la lecture
   à moins de 12 s de la fin), le rappel est tiré immédiatement.
2. **Ticker de 500 ms** (Handler) : filet de sécurité, mêmes gardes. Il
   peut être retardé de plusieurs secondes écran éteint — d'où les messages.

Plancher « trop tard c'est trop tard » : sous
`MIN_AUTO_CROSSFADE_REMAIN_MS` (4 s, ou moins aux petits réglages pour
garder une fenêtre d'au moins 3 s), on ne lance plus de fondu ; ExoPlayer
enchaîne alors sans blanc (gapless).

### 2.3 Le pré-armement

Une queue est **ouverte, mise en tampon et laissée à l'arrêt** sur le
morceau en cours (`preparedTail`) dès que la fin approche : le ticker le
fait dès que le reste du morceau passe sous `CROSSFADE + LEAD + 13 s`
(≈ 25 s avant la fin), et le message de timeline au point `prearmAt`
(6,5 s avant le fondu) sert de filet si le ticker a été retardé écran
éteint. À l'heure du fondu, elle est prélevée et recalée : la bascule est
immédiate au lieu d'attendre l'ouverture du fichier. Une queue pré-armée pour un *autre* morceau est
libérée proprement (jamais d'instance ExoPlayer orpheline). Un geste
(suivant/précédent/seek) peut réutiliser la queue pré-armée si c'est le
même morceau — c'est un départ « à chaud », avec son propre estimateur de
latence.

### 2.4 La bascule et l'alignement (« raccord »)

La queue, muette, est lancée sur le morceau **à la position du principal
plus une avance** égale à la latence d'amorçage mesurée sur l'appareil
(`tailStartupLagMs` à froid, `tailStartupLagWarmMs` à chaud — deux
estimateurs persistés, affinés à chaque fondu par moyenne glissante). Puis :

1. on attend qu'elle **avance vraiment** (preuve qu'elle sort du son ;
   1,5 s maximum, sinon bascule sèche) ;
2. on mesure le **résidu** direct − queue (médiane de 5 lectures espacées
   de 10 ms : positif = un bout rejouerait, négatif = un bout serait élidé) ;
3. au-delà de 350 ms : un seek différentiel ; en deçà, **glissements de
   vitesse** ±8 % sur la queue muette, chacun dimensionné à ~80 % du résidu
   (amorti : viser 100 % faisait rebondir), 80 ms de stabilisation, jusqu'à
   4 passes (2 sur un geste). Une divergence (le résidu grossit : accroc de
   charge) est tolérée une fois ; deux = oscillation, on arrête ;
4. cible : `SEAM_TOLERANCE_MS` = 12 ms. Budget dur : 3 s, au-delà on part en
   fondu avec le résidu qu'on a (l'alignement mange la fenêtre du fondu, et
   un fondu écrasé à 1-2 s faisait *bondir* l'entrant à plein volume) ;
5. résidu final > `MAX_TAIL_DRIFT_MS` (150 ms) : **bascule sèche** (arrivée
   franche, pas de fondu) ;
6. sinon **pont de bascule** : 60 ms de tuilage equal-power (queue monte,
   principal descend) calé sur l'horloge réelle. Il noie l'accroc de phase
   d'un échange sec entre deux flux jamais alignés à l'échantillon près.

Le journal (`Réglages > Diagnostic`) trace chaque raccord :
`raccord aligné : résidu A -> B ms (N glissement(s)[, geste])`.

### 2.5 Les deux fondus

- **Sortie de la queue** : cosinus (equal-power) sur `eff` ms, où `eff`
  est le réglage plafonné par **ce qu'il reste de fichier moins 750 ms**
  (la fin encodée précède souvent la durée annoncée). Progression calée sur
  l'horloge réelle : un thread principal en retard saute des pas, la durée
  totale ne bouge pas. La queue est ensuite mise au silence, arrêtée, et
  **détruite 200 ms plus tard** (démonter la chaîne audio pile à la fin du
  fondu pouvait faire un micro-glitch).
- **Entrée du principal** : sinus symétrique sur la même durée.

Le volume final du principal = normalisation × facteur de sommeil × gain de
fondu (`applyVolume`). Les fondus s'appliquent **sur la sortie** (volume du
lecteur), pas dans le tampon : aucune latence ajoutée par les 2 s de tampon.

### 2.6 Les gestes : suivant, précédent, déplacement

Tous passent par le **même mécanisme** de queue (`handOffTail`, drapeau
`fromGesture`) avec une fenêtre plus courte (`GESTURE_WATCHDOG_MS` = 2,5 s :
l'utilisateur ne doit pas attendre) :

| Geste | Comportement |
|---|---|
| **Suivant** | fondu croisé vers l'index suivant (cible **absolue** arrêtée à l'appui — la file a pu enchaîner toute seule entre-temps). En bout de file sans répétition : message « Fin de la file d'attente ». En répétition du morceau : avance quand même. |
| **Précédent** | > 3 s dans le morceau : retour à son début (fondu croisé avec lui-même) ; sinon morceau précédent. |
| **Barre de progression** | à la lecture : fondu croisé « on se déplace en musique » (la queue tient le passage quitté pendant que le principal se replace et remonte) ; **à l'arrêt : seek direct silencieux** (lancer la queue ferait sonner l'appareil en pause). |
| **Choix dans la file** (`playQueueItem`) | saut direct, sans fondu ; purge tout ce qui attendait. |
| **Deux gestes rapprochés** | le premier est **appliqué** avant le second (deux « suivant » = deux morceaux). Une bascule *automatique* en attente est au contraire **jetée** par un geste (la rejouer sautait un morceau de trop). |
| **Fin naturelle pendant qu'une bascule attend** | bascule automatique : jetée (l'enchaînement qui vient d'avoir lieu *est* son intention) ; bascule de geste : appliquée tout de suite. |

Sans queue possible (fichier trop lent, erreur, fondu désactivé, lecture à
l'arrêt) : **`quickSwitch`** — saut immédiat et remontée en 250 ms depuis le
silence (assez pour couvrir la mise en tampon, qui s'entendait comme un
claquement suivi d'un blanc).

### 2.7 Micro-fondu de pause / reprise

Pause : le gain descend en ~120 ms (6 pas de 20 ms) avant `pause()` —
couper net fait un clic. Reprise : montée en 150 ms. **Mode DJ : voir §3.11
et l'anomalie A1.**

### 2.8 Cas particuliers

- **Répétition du morceau** (`REPEAT_MODE_ONE`) : la fin du morceau fond
  *dans son propre début* (le « suivant » d'ExoPlayer est lui-même) ; la
  marque `crossfadedFrom` est levée à chaque tour pour que chaque fin
  fonde encore.
- **Sauter les intros parlées** : les items sont rognés
  (`ClippingConfiguration`) ; la queue est construite **sur le même item
  rogné**, sinon elle rejouait un passage décalé de toute l'intro.
- **Pré-écoute** (« meilleur passage ») : un seul item rogné, sans suivant :
  aucun fondu, fin naturelle.
- **Normalisation du volume** : gain par morceau (mesuré à l'analyse, sinon
  formule sur l'énergie), appliqué au principal ; la queue hérite du volume
  du principal au moment de la bascule (`v0`).
- **Aléatoire** : les cibles de « suivant » suivent l'ordre de lecture réel
  (`nextMediaItemIndex`) ; « jouer ensuite » reconstruit un ordre qui part
  du morceau courant.

---

## 3. Mode DJ : le moteur `DjMixer`

### 3.1 Architecture

Le mode DJ ne joue **pas** par ExoPlayer. `DjMixer` décode lui-même
(`AudioDecoder`, un thread par deck), mélange en float sur un thread audio
temps réel (`THREAD_PRIORITY_URGENT_AUDIO`, blocs de 2 048 frames à
44,1 kHz) et écrit dans un **AudioTrack** de ~2,3 s de tampon. ExoPlayer
boucle pendant ce temps sur une **piste silencieuse** à volume nul : il
garde le focus audio et la session média (Bluetooth, notification,
voiture), et ses play/pause/suivant/précédent sont routés vers le moteur.

Un set = une liste de **passages** (`Segment` : morceau + index de phase),
uniquement les morceaux **analysés** (BPM connu). Un deck (`Deck`) est un
passage ouvert : décodeur, file de ~4 s de son décodé d'avance, position,
tempo courant, filtres, capture de boucle.

### 3.2 Le passage joué

Pour chaque morceau, l'analyse a retenu son **passage fort** (`bestStartMs`,
`segmentMs` : la fenêtre de 60 s la plus énergique, ou 60 % d'un morceau
court). Le deck :

- démarre sur l'**ancre** = premier temps détecté dans le passage
  (`firstBeatMs`), sinon `bestStartMs` ;
- joue `segmentMs × facteur de phase` (0,85 à 1,15 : phases énergiques plus
  longues), **jamais moins de 60 s** (`MIN_SEGMENT_MS`), arrondi aux phrases
  de 16 temps ;
- **cale sa fin sur la structure** détectée (`snapEndToStructure`) : la
  frontière de section la plus proche à ± une phrase, de préférence la fin
  d'un temps fort — sans jamais descendre sous 20 s de lecture ;
- **premier morceau du set** : commence au début du fichier (ou au début de
  la musique si « sauter les intros ») et va jusqu'à la fin de son passage
  fort ; **dernier morceau** : lu jusqu'à la vraie fin, sans boucle de
  sortie.

À la fin du passage, une **boucle de sortie** (les 8 derniers temps, couture
fondue) tient le deck sous le fondu si nécessaire ; chaque répétition
au-delà de la première s'atténue (×0,82, plancher 0,35) — une boucle
constante sonne comme un disque rayé. Le *plan* ne compte que sur **une**
répétition ; `LOOP_MAX_OUT` (30 s) n'est qu'un garde-fou d'exécution
(fader manuel tenu, deck lent).

### 3.3 Ouverture du deck entrant

Le deck suivant est ouvert **8 s avant** l'heure du fondu
(`PREOPEN_LEAD_S`), sur un thread dédié (`DjOpen`) — jamais sur le thread
audio (ouvrir un MediaCodec prend parfois > 1 s). Il n'est confié au
mixeur qu'avec **~2 s de son décodé d'avance** (`prebuffer`, 3 s d'attente
maximum) : déclaré prêt au premier chunk, comme avant, il abordait le
fondu sans réserve et le moindre à-coup du décodeur creusait un trou dans
le morceau qui arrive. Son décodeur se bloque tout seul dès sa file pleine,
ouvrir tôt ne coûte rien.

Échec d'ouverture : le morceau est **sauté** (le suivant est essayé) ; si
c'était le dernier, le set se termine.

### 3.4 Calage de tempo

`computeRate` : l'entrant est lu à `rate = BPM sortant effectif / BPM
entrant` (ou ×2, ÷2 pour double/moitié de tempo), **seulement si ce
rapport tient dans ±4 %** (`MIN_LOCK_RATE`..`MAX_LOCK_RATE`). Sinon le
morceau se joue **à son tempo naturel** et la jonction sera une coupe
courte — étirer de 8 % sans que les temps se calent ne faisait que
désaccorder le morceau (0,92 ≈ un demi-ton), le journal en était plein sur
des morceaux chantés.

Pendant le fondu, un **verrou de kicks** (`OnsetTracker` sur l'enveloppe
des basses de chaque deck) micro-corrige le tempo de l'entrant (±0,02 % par
bloc, cumul borné ±0,4 %) pour que les attaques restent superposées. Après
le fondu, le nouveau deck **revient à son tempo naturel** : 4 s de répit,
puis ~1 %/s (imperceptible). Le cran de vitesse manuel (±8 % par cran)
s'applique lui à ~11 %/s.

### 3.5 Choix de la technique et de la durée

`fadeSpec` (mode standard) choisit **par paire de morceaux**, avec un tirage
stable qui ne répète jamais la technique précédente :

| Situation | Technique | Durée nominale |
|---|---|---|
| saut manuel (suivant/précédent) | `KIND_EQ` neutre | 5 s |
| tempos **non calables** | `KIND_CUT` coupe + echo-out | 6 s |
| tempos calés **et** tonalités compatibles (Camelot ≥ 0,8) | `KIND_HARMONIC` long blend + mid swap | 18 s |
| deux morceaux tenus (nappes) | harmonique / `KIND_DARK` / normal | 18-20 s |
| deux morceaux percussifs et énergiques | coupe / normal / `KIND_EQ` | 7-14 s |
| entrant calme | `KIND_DARK` (le sortant s'étouffe) | 14-20 s |
| sortant brillant | plutôt `KIND_DARK` | 12-17 s |
| sinon | sweep clair/sombre/bass swap | 12-17 s |

`fadeSpecPro` (toggle **Transitions pro**) ajoute le **drop-swap**
(`KIND_DROP`) quand : tempos calés, `dropStreak` < 2 (jamais trois
d'affilée), les **deux** morceaux énergiques (≥ 0,12), et l'entrant a une
section **DROP** détectée à ± une mesure de son ancre. Sinon délégation à
`fadeSpec`.

**Plafond** (`clampFadeS`) : quelle que soit la technique, la jonction ne
dépasse jamais **15 % du passage sortant ni 8 s** (≈ 4 mesures à 128 BPM),
plancher 2 s. Les durées nominales ci-dessus sont calibrées pour des
morceaux entiers de 3-5 min ; sur des passages d'une minute, elles faisaient
jouer deux morceaux ensemble pendant un tiers du set, chant sur chant. Le
plafond est appliqué **avant** l'ouverture du deck, pour que le pré-roll
(§3.6) soit calculé dessus.

Durée réelle : arrondie à la **mesure** la plus proche, et bornée par la
matière du sortant (reste du passage + une répétition de boucle).

### 3.6 Pré-roll et calage sur la grille

L'entrant d'une transition **automatique** démarre **un fondu avant son
ancre** (`preRollMs`, arrondi à la mesure) : sous le fondu on entend sa
montée, et son drop tombe quand le sortant s'efface. Si la structure
montre une montée (BUILD) adjacente à l'ancre, le pré-roll entre au début
de cette montée, pas avant. Pas de pré-roll sur un saut manuel ni un seek
(l'utilisateur attend le passage fort tout de suite).

Le départ du fondu est **quantisé sur la grille de beats du sortant** :
phrase de 16 temps de préférence (si le reste du passage + une boucle
tiennent encore le fondu entier), sinon mesure, sinon temps. Un saut manuel
part dès la prochaine mesure.

### 3.7 Ce qui se passe pendant la jonction

Principe commun : **une seule source par bande à chaque instant**. Formes
(`SHAPE_*`) : fenêtre où le sortant perd ses basses, fenêtre où l'entrant
s'ouvre au-delà des basses, instant jusqu'où le sortant reste plein, instant
où l'entrant atteint son plein volume.

- **Entrant** (toutes techniques sauf coupe) : passe-bas 2 pôles dont la
  coupure monte de 140 Hz à 16 kHz — il n'apporte d'abord *que* ses basses,
  puis s'ouvre pendant que le sortant s'efface. Ses basses sont **coupées
  avant le « 1 » du swap** et libérées d'un geste après.
- **Sortant**, selon la technique : passe-haut balayé 60 Hz → 6 kHz
  (`NORMAL`) ; passe-bas 6 kHz → 150 Hz qui reste fermé (`DARK`) ;
  échange de basses **net sur le « 1 »** de la dernière mesure avant la
  fin du fondu, rampe anti-clic d'un temps (`EQ`, `HARMONIC`), puis *mid
  swap* bande par bande pour l'harmonique.
- **Coupe** (`CUT`) : sortie raide, entrée franche, **echo-out** (ligne à
  retard d'un temps, feedback 0,55) nourrie sur le premier tiers du fondu.
- **Drop-swap** (`DROP`) : le sortant reste à 0,95 pendant que l'entrant
  monte **passe-haut** plafonné à 0,5 ; sur le « 1 » de mesure le plus
  proche de la fin du fondu, coupe nette du sortant (un seizième de temps,
  queue d'écho d'un temps) et entrant à plein spectre et plein volume avec
  une rampe anti-clic d'un demi-temps. Le limiteur de sortie travaille
  pendant la montée (les deux sources s'additionnent) — le journal le
  mesure.
- **Limiteur doux** de sortie (attaque immédiate, relâche ~30 ms), renfort
  dynamique des basses gelé pendant les fondus, écrêtage dur ±1 en dernier
  recours.

Fin de fondu : le deck sortant est fermé, l'entrant devient le deck actif,
annonce du morceau à l'interface **différée de la latence de sortie**
(~2,3 s) pour que le titre change quand on l'entend. La bascule **attend**
tant que le crossfader manuel est tenu.

### 3.8 Gestes en mode DJ

| Geste | Comportement |
|---|---|
| **Suivant** (`nextTrack`) | vraie transition battue vers le morceau d'après le deck actif — ou d'après la transition en vol (deux appuis = deux morceaux) ; fondu 5 s neutre, départ à la prochaine mesure. Rien après : la transition en cours va à son terme. |
| **Précédent** (`prevTrack`) | transition vers le passage précédent ; au premier, repart de son début. |
| **Barre de progression** (`requestSeek`) | pas de saut : un second deck est ouvert **sur le même morceau** à la position visée, et on y fait une vraie transition (8 s, échange de basses). **Ignoré si une transition est déjà en vol** (§6, L4). |
| **Mixer maintenant** | = suivant. |
| **Crossfader manuel** | pendant une transition : la position remplace la progression temporelle du fondu (mêmes courbes equal-power) ; hors transition : n'atténue que le deck actif (plein jusqu'à mi-course), ne déclenche rien. Saisie et retour « Auto » en rampe de 250 ms. |
| **Kill basses A/B** | coupe pleine par le même chemin que le swap automatique, qu'il supplante. |
| **Boucle de sortie manuelle** (4/8 temps) | boucle calée sur la mesure, flux en slip dessous ; coupée d'office à la fin de la transition. |
| **Nudge tempo** | ±0,4 % cumulés sur l'entrant pendant une transition, sur le deck actif sinon. |
| **Boucle live** (bouton maintenu) | 4 ou 8 temps, dernier passage complet au relâchement. |
| **Effets live** | filtre maître balayé, écho ½ temps, auto-pan, gate rythmique : rendus par le moteur, indépendants des jonctions. |

### 3.9 Répétition des transitions

`rehearseTransitions` : chaque deck est **rouvert à 15 s de sa fin**
(mécanique du seek) juste après son annonce ; on n'entend que les
jonctions. Le journal de set n'est pas alimenté en répétition.

### 3.10 Fin de set et enchaînement

12 s avant la fin du dernier passage (`SET_ENDING_LEAD_S`), le moteur
prévient (`onSetEnding`) et PlayerCore lance le **décompte
d'enchaînement** pendant que le son tourne encore (§4). Un saut en arrière
dans le plan annule l'alerte (−1). Le dernier passage se termine par un
fondu de fin de 0,5 s.

### 3.11 Pause, reprise, arrêt

Pause : le thread audio met l'AudioTrack en pause et dort par tranches de
200 ms. Reprise : `play()`. Une reprise après plus d'une minute est
journalisée. Arrêt (`stop`) : pause + flush pour débloquer une écriture en
cours, jeton de génération pour qu'un thread traînard ne reprenne jamais
le set suivant.

**Volumes en DJ** : la normalisation est un gain **par deck** figé à
l'ouverture ; le `master` du moteur ne sert qu'au fondu de fin de set.

---

## 4. Enchaînement automatique d'un mix / set au suivant

Quand un mix (MIX) ou un set (DJ) arrive au bout, un **nouveau mix du même
type** est régénéré (`MixSpec` : identifiant du plan, DJ ou non, durée,
genre, morceau-graine pour « comme ce morceau ») et lancé après un
**décompte à l'écran**.

- Le décompte démarre **pendant les dernières secondes** (12 s en MIX via le
  ticker, `onSetEnding` en DJ) : la lecture tourne encore, le service reste
  au premier plan et le processus vivant — c'est ce qui le fait marcher
  écran éteint. L'arrêt final (`STATE_ENDED`, `onStopped(natural)`) reste
  un filet. Un **wakelock borné** couvre le décompte.
- Le plan suivant se construit **pendant** le décompte ; au moment de
  lancer, on vérifie que la fin est toujours d'actualité (pas de seek en
  arrière ni de pause entre-temps).
- Aucun plan constructible : message, arrêt. Annulation par l'utilisateur :
  ne se redéclenche pas pour *cette* fin ; tout nouveau lancement réarme.
- Il n'y a **pas de fondu entre le mix qui finit et celui qui commence** :
  le premier va à sa fin naturelle, le second démarre franchement (§6, L7).

---

## 5. Mécanismes transversaux

### 5.1 Minuterie de sommeil

Fondu du volume sur les **30 dernières secondes** (`applyVolume` :
`v ×= reste / 30 s`), puis pause. **Modes ExoPlayer seulement** : en DJ,
`applyVolume` ne fait rien, la pause tombe donc sans fondu (anomalie A2,
corrigée).

### 5.2 Réveil musical

`AlarmClock.launchNow` : rampe du **volume média système** d'un huitième du
maximum jusqu'au maximum sur *N* minutes (abandonnée si l'utilisateur
touche au volume), puis lancement en Douce, en aléatoire, ou en **set DJ**
(plan « réveil » ; `MixSpec` posé pour que le set enchaîne). Sonnerie de
secours si la bibliothèque est vide. Les transitions du mode choisi
s'appliquent ensuite normalement.

### 5.3 Reprise après fermeture

L'état est restauré (file, index, position) **à l'arrêt** ; en DJ, le
moteur repartira **au début de la phase** au prochain « lecture » (la
position d'un set n'est pas persistée).

### 5.4 Diagnostic

`Réglages > Diagnostic > exporter le journal` concatène `service_log.txt`
(PlayerCore, PlaybackService, `[DJ]`), `dj_log.txt` (pannes du moteur) et
`crash_log.txt`. Lignes utiles :

- `fondu déclenché (message|ticker filet), reste X ms` ;
- `raccord aligné : résidu A -> B ms (N glissement(s))` /
  `bascule sèche : …` ;
- `sous-alimentation audio (principal|queue) : tampon X ms, Y ms sans
  données` (vraie saccade, avec sa source) ;
- `[DJ] transition « A » → « B » : technique, fondu X ms, calée sur
  phrase/mesure/temps, pré-roll Y ms, tempo entrant ×r[, geste]` ;
- `[DJ] jonction terminée avec : famine sortant/entrant, boucle de sortie,
  sous-alimentations, limiteur` (seulement quand quelque chose a manqué) ;
- `[DJ] set démarré / reprise après N min de pause / le sortant s'est tari
  avant l'heure / ouverture impossible / set terminé`.

---

## 6. Limites connues (par conception)

- **L1** — En mode DJ, seuls les morceaux **analysés** (BPM) sont joués ;
  les autres sont silencieusement absents du set (un plan sans aucun
  morceau jouable est refusé avec message).
- **L2** — Le mode DJ **ignore** le réglage de durée du fondu croisé (3-15 s)
  : ses jonctions ont leurs propres durées (§3.5).
- **L3** — Un geste **pendant** un fondu croisé ExoPlayer coupe la queue
  sortante **net** (elle est libérée) ; seul l'entrant remonte en 250 ms.
- **L4** — En DJ, un déplacement sur la barre **pendant une transition** est
  abandonné (la fraction visait le deck sortant, qui n'existera plus). La
  barre retombe sur la position réelle au bout de 20 s.
- **L5** — La normalisation DJ est figée **à l'ouverture du deck** : basculer
  le réglage en plein set ne s'applique qu'au morceau suivant.
- **L6** — La position d'un set DJ n'est **pas persistée** : reprise au
  début de la phase.
- **L7** — Pas de fondu **entre deux mix enchaînés** (fin naturelle puis
  départ franc du suivant).
- **L8** — Le pré-roll et le calage sur la structure supposent une analyse
  récente (structure v3) ; les anciennes analyses retombent sur le
  comportement historique (ancre = passage fort, pas de pré-roll).
- **L9** — Sur un morceau de moins de ~15 s (fenêtre + 3 s), aucun fondu de
  fin : enchaînement gapless d'ExoPlayer.
- **L10** — Le calage de tempo est limité à ±4 % : deux morceaux plus
  éloignés ne sont jamais battus ensemble, ils s'enchaînent par une coupe.
- **L11** — En **répétition des transitions**, chaque deck est rouvert à
  15 s de sa fin *sur le fil audio* : ~0,5 s de silence par morceau (voir
  A6).

---

## 7. Anomalies relevées par cette relecture

| # | Constat | Gravité | Traitement |
|---|---|---|---|
| **A1** | **Pause/reprise en DJ sans micro-fondu** : `AudioTrack.pause()` tombe net sur le son en cours (clic possible), alors qu'ExoPlayer a un micro-fondu de 120/150 ms précisément pour ça. | moyenne | **Corrigé** : rampe du volume de l'AudioTrack (appliquée à la sortie, pas au tampon) ~80 ms avant la pause, ~120 ms après la reprise. |
| **A2** | **Minuterie de sommeil sans fondu en DJ** : `applyVolume` ne s'applique pas au moteur, la pause arrive sans les 30 s de descente promises par le réglage. | moyenne | **Corrigé** : volume maître du moteur (`setMasterVolume`, appliqué sur l'AudioTrack), piloté par `applyVolume` en DJ comme dans les autres modes. |
| **A3** | **Barre de progression DJ sourde 8 s avant chaque transition** : depuis l'avance d'ouverture à 8 s, un seek reçu pendant que le deck suivant s'ouvre (`opening`) était **jeté** — avant, cette fenêtre ne durait que 3 s. Régression introduite par le correctif des sauts. | haute | **Corrigé** : le seek prend la main sur une ouverture en cours (jeton de génération d'ouverture ; le deck ouvert pour rien est refermé, jamais fuité). |
| **A4** | **Deck de seek / de saut sans réserve décodée** : la mise en réserve de 2 s ne couvrait que les transitions automatiques ; un seek ou un « suivant » manuel abordait sa transition avec un seul chunk décodé — le cas exact des trous corrigés pour les transitions automatiques. | moyenne | **Corrigé** : même mise en réserve, avec une attente maximale courte (750 ms) pour ne pas faire attendre le geste. |
| **A5** | **Deux chemins de seek dans l'API** (`seekToFraction` direct, jamais appelé par l'interface, et `seekToFractionSmooth`) : le premier contournait le fondu et la purge des bascules en attente. | faible | **Corrigé** : chemin mort supprimé (ViewModel et PlayerCore), un seul point d'entrée. |
| **A6** | **Répétition des transitions : réouverture du deck sur le fil audio** (`rehearsalSkip` attend `open()` jusqu'à 4 s sur le thread de mixage) : le tampon de sortie se vide, ~0,5 s de silence par morceau. Mode répétition seulement, où l'on n'écoute que les jonctions. | faible | Documenté (L11) ; une réouverture asynchrone comme celle des seeks est possible si le mode répétition devient un usage courant. |
| L3, L4, L7 | Voir §6 : comportements par conception, à discuter avant d'être changés (chacun a une raison musicale ou de robustesse). | — | Documentés. |
