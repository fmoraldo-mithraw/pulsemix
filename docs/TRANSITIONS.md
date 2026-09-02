# Les transitions dans PulseMix — état des lieux

Ce document décrit **toutes** les transitions sonores de l'application :
qui les déclenche, comment elles sont réalisées techniquement, dans quels
cas elles s'appliquent, et où sont leurs limites. Il est écrit à partir du
code (`player/PlayerCore.kt`, `player/DjMixer.kt`, `player/PlaybackService.kt`,
`player/AlarmClock.kt`, `mix/MixEngine.kt`) et sert de référence : quand le
comportement change, ce fichier doit changer avec.

Les sections finales listent les anomalies relevées par la première
relecture (§7) et les améliorations qui en ont découlé (§8).

---

## 1. Vue d'ensemble

PulseMix a **deux moteurs de lecture**, donc deux familles de transitions :

| Mode | Moteur | Transitions | Réglage utilisateur |
|---|---|---|---|
| **NORMAL** (lecture classique, playlists, file) | ExoPlayer (media3) | fondu croisé « deux platines » entre morceaux entiers, avec échange de basses | *Fondu croisé* on/off + durée 3-15 s |
| **DOUCE** (sélection du plus doux au moins doux) | ExoPlayer | identiques à NORMAL | idem |
| **MIX** (plan de mix : phases → morceaux entiers) | ExoPlayer | identiques à NORMAL, plus l'enchaînement en fondu d'un mix au suivant | idem |
| **DJ** (set : phases → *passages forts* d'une minute) | `DjMixer` (AudioTrack + décodage maison) | jonctions battues, comptées en mesures : calage de tempo partagé, filtres, échange de basses, drop-swap… | *Transitions pro* on/off |

À côté de ces deux familles, trois mécanismes « transversaux » modifient le
son au moment d'un changement : le **micro-fondu de pause/reprise**, la
**minuterie de sommeil** (fondu de 30 s), et le **réveil** (canal alarme,
rampe de volume). Ils sont traités au §5.

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
- porte **son propre égaliseur**, créé au pré-armement (loin de l'instant
  de la bascule, où l'insertion d'un AudioEffect pouvait cliquer) : copie
  du réglage de l'utilisateur, et support de l'échange de basses (§2.5).

Les deux lecteurs sont construits avec un **tampon de sortie de ~2 s** (au
lieu des 250-750 ms par défaut de media3) : c'est le temps dont dispose le
décodeur quand une autre application accapare le CPU. Leur **vitesse passe
par l'AudioTrack** (`setPlaybackParams`) et non par le ré-échantillonneur
logiciel : appliquée au son déjà en tampon, elle prend effet en quelques
dizaines de millisecondes — indispensable à l'alignement (§2.4). La queue
court toujours à la vitesse du principal (cran de vitesse compris).

### 2.2 Quand le fondu de fin de morceau se déclenche

Fenêtre de déclenchement, calculée sur la **fin musicale** `end` du morceau
et le réglage `CROSSFADE_MS` (3-15 s, 10 s par défaut) :

- `end` = la fin **audible** mesurée à l'analyse (`musicEndMs` : dernier bloc
  au-dessus de 3 % du niveau musical) quand elle est plausible — seconde
  moitié du morceau, à moins de 20 s de la fin réelle —, sinon la durée du
  fichier. Un titre qui se termine par trois secondes de silence encodé ne
  fond plus « dans le vide » ;
- point de départ du fondu : `fadeAt = end − CROSSFADE_MS − CROSSFADE_LEAD_MS`
  (`CROSSFADE_LEAD_MS` = 2 s de marge) ;
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
   à moins de 12 s de la fin, plan greffé en fin de mix), le rappel est
   tiré immédiatement.
2. **Ticker de 500 ms** (Handler) : filet de sécurité, mêmes gardes. Il
   peut être retardé de plusieurs secondes écran éteint — d'où les messages.

Plancher « trop tard c'est trop tard » : sous
`MIN_AUTO_CROSSFADE_REMAIN_MS` (4 s, ou moins aux petits réglages pour
garder une fenêtre d'au moins 3 s), on ne lance plus de fondu ; ExoPlayer
enchaîne alors sans blanc (gapless).

### 2.3 Le pré-armement, et l'alignement qui va avec

Une queue est **ouverte** sur le morceau en cours (`preparedTail`) dès que
la fin approche : le ticker le fait dès que le reste du morceau passe sous
`CROSSFADE + LEAD + 13 s` (≈ 25 s avant la fin), et le message de timeline
au point `prearmAt` (6,5 s avant le fondu) sert de filet si le ticker a
été retardé écran éteint.

**Elle est alors lancée muette et alignée sur le direct** (`alignPrepared`) :
seek compensé de la latence d'amorçage mesurée sur l'appareil
(`tailStartupLagWarmMs`), attente du premier son, résidu médian, puis
**glissements de vitesse** — jusqu'à six, sans budget serré, personne
n'attend. À l'arrivée, la queue *tourne*, muette, alignée à quelques
millisecondes ; les deux lecteurs partagent la même horloge audio à la
même vitesse, elle le reste. Journal : `queue pré-alignée : résidu A -> B ms
(N glissement(s))`.

À l'heure du fondu, la queue pré-alignée est prélevée telle quelle (pas de
seek — il la désalignerait) : **vérification** rapide (0,8 s, deux
glissements au plus) puis bascule. Le fondu garde ainsi **toute sa
fenêtre** ; avant, l'alignement se faisait à l'heure du fondu avec 3 s de
budget pris sur le fondu lui-même. Un geste (suivant/précédent/seek) peut
réutiliser la queue pré-alignée s'il vise le même morceau.

Une queue pré-armée pour un *autre* morceau, ou congédiée par une pause ou
un geste, est libérée proprement (jamais d'instance ExoPlayer orpheline) ;
le pré-armement se refait de lui-même si la position est encore dans la
fenêtre.

### 2.4 La bascule et l'alignement (« raccord »)

Quand aucune queue pré-alignée n'est disponible (première transition après
une reprise, geste sur un autre morceau…), la procédure complète se
déroule à l'heure du fondu, avec budget :

1. la queue, muette, est lancée à la position du principal **plus** la
   latence d'amorçage (`tailStartupLagMs` à froid, `…WarmMs` à chaud — deux
   estimateurs persistés, affinés à chaque fondu) ;
2. on attend qu'elle **avance vraiment** (1,5 s maximum, sinon bascule
   sèche) ;
3. **résidu** direct − queue (médiane de 5 lectures espacées de 10 ms :
   positif = un bout rejouerait, négatif = un bout serait élidé) ;
4. au-delà de 350 ms : un seek différentiel ; en deçà, glissements de
   vitesse ±8 % **relatifs à la vitesse du direct**, chacun dimensionné à
   ~80 % du résidu (amorti), 80 ms de stabilisation, jusqu'à 4 passes
   (2 sur un geste). Une divergence (accroc de charge) est tolérée une
   fois ; deux = oscillation, on arrête ;
5. cible : `SEAM_TOLERANCE_MS` = 12 ms. Budget dur : 3 s ;
6. résidu final > `MAX_TAIL_DRIFT_MS` (150 ms) : **bascule sèche** ;
7. sinon **pont de bascule** en tuilage equal-power, **proportionnel au
   résidu** : 20 ms + 3 × |résidu|, borné à 60 ms — un raccord à 0 ms n'a
   pas besoin du floutage de phase qu'exige un accroc de 20 ms.

Le journal trace chaque raccord : `raccord aligné : résidu A -> B ms
(N glissement(s)[, pré-alignée][, geste])`.

### 2.5 Les deux fondus et l'échange de basses

- **Sortie de la queue** : cosinus (equal-power) sur `eff` ms, où `eff`
  est le réglage plafonné par **ce qu'il reste de fichier moins 750 ms**.
  Progression calée sur l'horloge réelle. La queue est ensuite mise au
  silence, arrêtée, et **détruite 200 ms plus tard**.
- **Entrée du principal** : sinus symétrique sur la même durée.
- **Échange de basses** (enchaînement automatique seulement) : deux lignes
  de basse qui cohabitent dix secondes, c'est ce qui rend un mix boueux.
  Le sortant perd ses graves (−15 dB, bandes < 250 Hz) entre 25 % et 55 %
  du fondu, l'entrant les reçoit entre 35 % et 65 % — une seule ligne de
  basse à la fois, comme un DJ qui bascule son EQ. Réalisé avec les
  égaliseurs système des deux lecteurs, par-dessus le réglage de
  l'utilisateur ; les graves de l'entrant sont rendus quoi qu'il arrive,
  même si le fondu est interrompu.

Le volume final du principal = normalisation × facteur de sommeil × gain de
fondu (`applyVolume`). Les fondus s'appliquent **sur la sortie**, pas dans
le tampon : aucune latence ajoutée par les 2 s de tampon.

### 2.6 Les gestes : suivant, précédent, déplacement

Tous passent par le **même mécanisme** de queue (`handOffTail`, drapeau
`fromGesture`), avec des **durées courtes** — sur « suivant », l'utilisateur
attend le morceau demandé ; sur un déplacement, deux passages du même
morceau superposés dix secondes sonnaient faux :

| Geste | Fondu | Comportement |
|---|---|---|
| **Suivant** | 2,5 s au plus (borné par le réglage) | vers l'index suivant (cible **absolue** arrêtée à l'appui). En bout de file sans répétition : « Fin de la file d'attente ». |
| **Précédent** | 2,5 s au plus | > 3 s dans le morceau : retour à son début ; sinon morceau précédent. |
| **Barre de progression** | 1,5 s au plus | à la lecture : « on se déplace en musique » ; **à l'arrêt : seek direct silencieux**. |
| **Choix dans la file** | — | saut direct, sans fondu ; purge tout ce qui attendait. |
| **Deux gestes rapprochés** | | le premier est **appliqué** avant le second (deux « suivant » = deux morceaux). Une bascule *automatique* en attente est au contraire **jetée** par un geste. |
| **Fin naturelle pendant qu'une bascule attend** | | bascule automatique : jetée ; bascule de geste : appliquée tout de suite. |

Une queue congédiée par un geste **s'efface en 150 ms** avant d'être
arrêtée (elle était coupée net auparavant). Sans queue possible (fichier
trop lent, erreur, fondu désactivé, lecture à l'arrêt) : **`quickSwitch`** —
saut immédiat et remontée en 250 ms depuis le silence. Fenêtre d'attente
sur un geste : `GESTURE_WATCHDOG_MS` = 2,5 s.

### 2.7 Micro-fondu de pause / reprise

Pause : le gain descend en ~120 ms avant `pause()` — couper net fait un
clic. Reprise : montée en 150 ms. En DJ : rampe du volume de l'AudioTrack,
80 ms avant la pause, 120 ms après la reprise (§3.11).

### 2.8 Cas particuliers

- **Répétition du morceau** (`REPEAT_MODE_ONE`) : la fin du morceau fond
  *dans son propre début*.
- **Sauter les intros parlées** : items rognés (`ClippingConfiguration`) ;
  la queue est construite **sur le même item rogné**, et la fin musicale
  est exprimée dans le même repère.
- **Pré-écoute** (« meilleur passage ») : un seul item rogné, sans suivant :
  aucun fondu.
- **Normalisation du volume** : gain par morceau appliqué au principal ; la
  queue hérite du volume du principal au moment de la bascule.
- **Aléatoire** : les cibles de « suivant » suivent l'ordre de lecture réel.

---

## 3. Mode DJ : le moteur `DjMixer`

### 3.1 Architecture

Le mode DJ ne joue **pas** par ExoPlayer. `DjMixer` décode lui-même
(`AudioDecoder`, un thread par deck), mélange en float sur un thread audio
temps réel (blocs de 2 048 frames à 44,1 kHz) et écrit dans un
**AudioTrack** de ~2,3 s de tampon. ExoPlayer boucle pendant ce temps sur
une **piste silencieuse** à volume nul : il garde le focus audio et la
session média (Bluetooth, notification, voiture), et ses
play/pause/suivant/précédent sont routés vers le moteur.

Un set = une liste de **passages** (`Segment` : morceau + index de phase),
uniquement les morceaux **analysés** (BPM connu). Un deck (`Deck`) est un
passage ouvert : décodeur, file de ~4 s de son décodé d'avance, position,
tempo courant, filtres, capture de boucle, section de sortie.

### 3.2 Le passage joué

Pour chaque morceau, l'analyse a retenu son **passage fort** (`bestStartMs`,
`segmentMs` : la fenêtre de 60 s la plus énergique). Le deck :

- démarre sur l'**ancre** (`anchorFor`) : le **début de la section DROP**
  la plus proche du passage fort (à ± une phrase) quand la structure est
  connue — le « 1 » du drop, recalé sur le saut de basses à l'analyse —,
  sinon le premier beat détecté dans le passage, sinon son début ;
- joue `segmentMs × facteur de phase` (0,85 à 1,15), **jamais moins de
  60 s**, arrondi aux phrases de 16 temps ;
- **cale sa fin sur la structure** (`snapEndToStructure`) : frontière de
  section la plus proche à ± une phrase, de préférence la fin d'un temps
  fort — jamais sous 20 s de lecture ; la section qui contient cette fin
  est sa **section de sortie** (§3.5) ;
- **premier morceau du set** : commence au début du fichier (ou au début de
  la musique si « sauter les intros ») ; **dernier morceau** : lu jusqu'à
  la vraie fin — sauf si un set suivant vient s'y greffer (§4).

À la fin du passage, une **boucle de sortie** (les 8 derniers temps, couture
fondue) tient le deck sous le fondu si nécessaire ; chaque répétition
au-delà de la première s'atténue (×0,82, plancher 0,35). Depuis que la
phrase prime sur la durée du fondu (§3.6), elle ne sert plus qu'aux
imprévus (deck lent, fader manuel tenu).

### 3.3 Ouverture du deck entrant

Le deck suivant est ouvert **8 s avant** l'heure du fondu
(`PREOPEN_LEAD_S`), sur un thread dédié (`DjOpen`). Il n'est confié au
mixeur qu'avec **~2 s de son décodé d'avance** (`prebuffer`, 3 s d'attente
maximum ; 750 ms sur un geste). Un seek reçu pendant cette ouverture la
**supplante** (jeton de génération) : le deck ouvert pour rien est refermé.
Échec d'ouverture : le morceau est sauté.

### 3.4 Calage de tempo — partagé

`splitRates` : chaque deck fait **la moitié du chemin** (en log : tempo
cible = moyenne géométrique des tempos naturels, avec double/moitié de
tempo admis pour l'entrant). Le sortant glisse vers sa part à ~2 %/s
pendant l'avance d'ouverture (`pretargetRate`), l'entrant ouvre directement
à la sienne. Chaque deck reste dans **±4 %** (`MIN_LOCK_RATE`..`MAX_LOCK_RATE`)
— au-delà, l'étirement s'entend comme un désaccordage sur tout ce qui est
chanté ou acoustique —, ce qui rend calables des morceaux jusqu'à **8 %**
d'écart. Hors plage : chacun à son tempo naturel, et la jonction sera une
coupe courte. Sur un saut manuel, pas le temps de glisser : l'entrant prend
tout (`computeRate`).

Pendant le fondu, un **verrou de kicks** (`OnsetTracker`) micro-corrige le
tempo de l'entrant (±0,02 % par bloc, cumul borné ±0,4 %). Après le fondu,
le nouveau deck **revient à son tempo naturel** : 4 s de répit, puis ~1 %/s.
Le cran de vitesse manuel (±8 % par cran) se multiplie à tout cela.

### 3.5 Choix de la technique et de la durée

`fadeSpec` (mode standard) choisit **par paire de morceaux** — tempos
calables ou non, tonalités (Camelot), caractère (énergie, son tenu ou
percussif, brillance) — avec un tirage stable qui ne répète jamais la
technique précédente, puis la **section locale** de la jonction corrige la
palette quand la structure est connue :

| Situation | Technique |
|---|---|
| saut manuel | `KIND_EQ` neutre |
| tempos **non calables** | `KIND_CUT` coupe + echo-out |
| tempos calés **et** tonalités compatibles | `KIND_HARMONIC` long blend + mid swap |
| deux morceaux tenus / entrant calme / sortant brillant | plutôt `KIND_DARK` (le sortant s'étouffe) ou normal |
| deux morceaux percussifs et énergiques | coupe / normal / `KIND_EQ` |
| **on sort d'un DROP** | geste franc (coupe, échange de basses), **jamais** de sweep grave |
| **on sort d'un BREAK / d'une OUTRO** | blend, **jamais** de coupe |
| **on entre directement dans un DROP** | pas de sweep grave |

`fadeSpecPro` (toggle **Transitions pro**) ajoute le **drop-swap**
(`KIND_DROP`) quand : tempos calés, `dropStreak` < 2, les **deux** morceaux
énergiques (≥ 0,12), et l'entrant a une section DROP à ± une mesure de son
ancre.

**Durée en mesures** (`fadeBars`) : coupe 2 mesures, blend harmonique 4
(6 sur un passage d'au moins 48 mesures), le reste 4 ; jamais plus d'**un
cinquième du passage sortant**, jamais moins d'une mesure. Un DJ compte des
mesures, pas des secondes (8 s à 90 BPM font trois mesures, une jonction
hors de toute structure). Sans tempo connu : plafond en secondes
(`clampFadeS`, 15 % du passage, 8 s). La durée est arrêtée **avant**
l'ouverture du deck pour que le pré-roll (§3.6) soit calculé dessus.

### 3.6 Pré-roll et calage sur la grille — la phrase d'abord

L'entrant d'une transition **automatique** démarre **un fondu avant son
ancre** (`preRollMs`, arrondi à la mesure) *seulement s'il y entre par une
montée détectée* (BUILD adjacente à l'ancre) : sous le fondu on entend sa
montée, et son drop tombe quand le sortant s'efface. Structure connue
sans montée : **pas de pré-roll**, le deck part sur son ancre (avant, il
partait N mesures plus tôt, n'importe où dans un couplet). Pas de
pré-roll non plus sur un saut manuel ni un seek.

Le départ du fondu est **quantisé sur la grille du sortant, la phrase de
16 temps d'abord** — et le fondu s'y plie : si la phrase suivante ne laisse
plus la place au fondu, on part à la phrase **précédente** avec un fondu
allongé (au plus deux mesures de plus) si elle est encore devant nous,
sinon à la phrase suivante avec un fondu **raccourci** (jamais sous une
mesure). Partir hors phrase est précisément ce qui fait amateur. Mesure et
temps ne restent que pour les sauts manuels (réponse rapide) et les cas
sans matière. Journal : `calée sur phrase [(fondu allongé|raccourci)]`.

### 3.7 Ce qui se passe pendant la jonction

Principe commun : **une seule source par bande à chaque instant**.

- **Entrant** (toutes techniques sauf coupe) : passe-bas 2 pôles dont la
  coupure monte de 140 Hz à 16 kHz ; ses basses sont **coupées avant le
  « 1 » du swap** et libérées d'un geste après.
- **Sortant**, selon la technique : passe-haut balayé (`NORMAL`) ; passe-bas
  qui reste fermé (`DARK`) ; échange de basses **net sur le « 1 »** de la
  dernière mesure avant la fin du fondu (`EQ`, `HARMONIC`), puis *mid swap*
  pour l'harmonique.
- **Coupe** (`CUT`) : sortie raide, entrée franche, **echo-out** d'un temps.
- **Drop-swap** (`DROP`) : le sortant reste à **0,8** pendant que l'entrant
  monte passe-haut plafonné à 0,5 ; sur le « 1 » de mesure le plus proche
  de la fin du fondu, coupe nette du sortant (queue d'écho d'un temps) et
  entrant à plein spectre et plein volume. (À 0,95 le limiteur rabotait
  ~2 dB pendant la montée : le niveau baissait quand la tension devait
  grimper.)
- **Limiteur doux** de sortie, renfort dynamique des basses gelé pendant les
  fondus, écrêtage dur ±1 en dernier recours.

Fin de fondu : le deck sortant est fermé, l'entrant devient le deck actif,
annonce du morceau à l'interface **différée de la latence de sortie**
(~2,3 s). La bascule **attend** tant que le crossfader manuel est tenu.

### 3.8 Gestes en mode DJ

| Geste | Comportement |
|---|---|
| **Suivant** | vraie transition battue vers le morceau d'après le deck actif — ou d'après la transition en vol ; 2 mesures, départ à la prochaine mesure. |
| **Précédent** | transition vers le passage précédent ; au premier, repart de son début. |
| **Barre de progression** | un second deck est ouvert **sur le même morceau** à la position visée, et on y fait une transition de **deux mesures**. Prend la main sur une ouverture en cours ; ignoré si une transition est déjà en vol (L4). |
| **Mixer maintenant** | = suivant. |
| **Crossfader manuel** | pendant une transition : la position remplace la progression du fondu ; hors transition : n'atténue que le deck actif. Saisie et retour « Auto » en rampe de 250 ms. |
| **Kill basses A/B**, **boucle de sortie manuelle**, **nudge tempo**, **boucle live**, **effets live** | inchangés (voir code) ; rendus par le moteur, donc **entendus après la latence de sortie (~2,3 s)** — voir §6, L12. |

### 3.9 Répétition des transitions

Chaque deck est **rouvert à 15 s de sa fin** juste après son annonce ; on
n'entend que les jonctions (~0,5 s de silence par morceau, L11).

### 3.10 Fin de set et enchaînement

12 s avant la fin du dernier passage, le moteur prévient (`onSetEnding`) ;
si un set suivant est prêt, il est **greffé** (§4) et l'alerte est levée.
Sinon le dernier passage se termine par un fondu de fin de 0,5 s.

### 3.11 Pause, reprise, arrêt, volumes

Pause : rampe du volume de l'AudioTrack (80 ms) puis pause ; reprise :
`play()` puis rampe de 120 ms. Arrêt (`stop`) : pause + flush, jeton de
génération. **Volume maître** (`setMasterVolume`, sur l'AudioTrack) : porte
le fondu de la minuterie de sommeil ; la normalisation reste un gain
**par deck**, figé à l'ouverture.

---

## 4. Enchaînement d'un mix / set au suivant — en fondu

Quand un mix (MIX) ou un set (DJ) arrive au bout, un **nouveau mix du même
type** est régénéré (`MixSpec`) pendant les dernières secondes, avec un
décompte à l'écran. Dès que le plan est prêt et que la lecture tourne
encore, il est **greffé sur la lecture en cours** (`chainNow`) :

- **mix** : ses morceaux rejoignent la file ExoPlayer (ceux déjà dans la
  file sont ignorés), les déclencheurs sont reprogrammés et, la fin étant
  proche, le fondu croisé habituel part tout de suite ;
- **DJ** : ses passages rejoignent le set (`DjMixer.appendPlan`, indices de
  phase continus) ; le moteur enchaîne le dernier deck sur le premier
  nouveau par une jonction ordinaire.

Le plan affiché devient le nouveau, ses phases s'ajoutent aux anciennes.
Si rien ne peut être greffé (fin déjà passée, lecture arrêtée, aucun
morceau nouveau), l'ancien chemin — décompte puis lancement franc — reste
le filet. Un **wakelock borné** couvre le décompte ; l'annulation par
l'utilisateur ne se redéclenche pas pour cette fin.

---

## 5. Mécanismes transversaux

### 5.1 Minuterie de sommeil

Fondu du volume sur les **30 dernières secondes**, puis pause — dans tous
les modes (en DJ via le volume maître du moteur).

### 5.2 Réveil musical

`AlarmClock.launchNow` : la musique du réveil sort sur le **canal ALARME**
(`USAGE_ALARM`, volume « alarme ») — lecteur principal, queue et moteur DJ.
Il ne dépend pas du volume média, souvent à zéro au coucher, et traverse
« ne pas déranger » / l'heure du coucher, qui peuvent couper le média. Rampe
de ce volume d'un quart du maximum (jamais sous 2 crans) jusqu'au maximum
sur *N* minutes (abandonnée si l'utilisateur touche au volume), puis
lancement en Douce, en aléatoire, ou en set DJ. Retour au canal média quand
le réveil est arrêté ou répété, et dès que l'utilisateur lance autre chose.

**Filet sonore** : bibliothèque pas lue en 30 s, exception au lancement, ou
rien qui ne joue 20 s après le lancement → sonnerie de secours du système.
Chaque étape est journalisée (`[Réveil]`).

### 5.3 Reprise après fermeture

L'état est restauré (file, index, position) **à l'arrêt** ; en DJ, le
moteur repartira **au début de la phase** au prochain « lecture ».

### 5.4 Diagnostic

`Réglages > Diagnostic > exporter le journal` concatène `service_log.txt`
(PlayerCore, PlaybackService, `[DJ]`, `[Réveil]`), `dj_log.txt` et
`crash_log.txt`. Lignes utiles :

- `fondu déclenché (message|ticker filet), reste X ms [(fin musicale, N ms
  de silence évités)]` ;
- `queue pré-alignée : résidu A -> B ms (N glissement(s))` ;
- `raccord aligné : résidu A -> B ms (N glissement(s)[, pré-alignée][, geste])`
  / `bascule sèche : …` ;
- `sous-alimentation audio (principal|queue) : tampon X ms, Y ms sans
  données` ;
- `enchaînement en fondu (mix|DJ) : N morceau(x)/passage(s) ajoutés` ;
- `[DJ] transition « A » → « B » : technique, fondu X ms, calée sur
  phrase/mesure/temps, pré-roll Y ms, tempo entrant ×r[, geste]` ;
- `[DJ] jonction terminée avec : famine sortant/entrant, boucle de sortie,
  sous-alimentations, limiteur` (seulement quand quelque chose a manqué) ;
- `[Réveil] sonnerie / bibliothèque chargée / volume alarme / lancement /
  lecture en cours 20 s après / sonnerie de secours (raison)`.

---

## 6. Limites connues (par conception)

- **L1** — En DJ, seuls les morceaux **analysés** (BPM) sont joués.
- **L2** — Le mode DJ **ignore** le réglage de durée du fondu croisé : ses
  jonctions se comptent en mesures (§3.5).
- **L4** — En DJ, un déplacement **pendant une transition** est abandonné
  (la fraction visait le deck sortant). La barre retombe au bout de 20 s.
- **L5** — La normalisation DJ est figée **à l'ouverture du deck**.
- **L6** — La position d'un set DJ n'est **pas persistée**.
- **L8** — Ancre sur le drop, pré-roll dans la montée, technique par section
  et fin musicale supposent une analyse récente (structure v3, fin
  musicale v4) ; les anciennes analyses retombent sur le comportement
  historique jusqu'au prochain scan automatique.
- **L9** — Sur un morceau de moins de ~15 s, aucun fondu de fin.
- **L10** — Calage de tempo limité à ±4 % par deck (8 % entre morceaux) :
  au-delà, coupe.
- **L11** — En **répétition des transitions**, ~0,5 s de silence par morceau
  (réouverture sur le fil audio).
- **L12** — Les contrôles du panneau **Performance** (fader, kills, nudge)
  et les effets live sont entendus **~2,3 s après le geste** : c'est le prix
  du tampon anti-saccades. Arbitrage assumé (robustesse contre réactivité),
  non modifié à la demande de l'auteur ; le panneau est un outil de
  préparation plus que de jeu live.

(L3 et L7 de la première version sont levées : queue effacée en douceur sur
un geste, enchaînement des mix en fondu.)

---

## 7. Anomalies relevées par la première relecture

| # | Constat | Traitement |
|---|---|---|
| A1 | Pause/reprise en DJ sans micro-fondu (clic) | corrigé (rampe AudioTrack 80/120 ms) |
| A2 | Minuterie de sommeil sans fondu en DJ | corrigé (volume maître) |
| A3 | Barre de progression DJ sourde pendant l'ouverture du deck suivant | corrigé (jeton d'ouverture, le seek prend la main) |
| A4 | Decks de seek / de saut sans réserve décodée | corrigé (même réserve, attente courte) |
| A5 | Chemin de seek direct mort qui contournait le fondu | supprimé |
| A6 | Répétition : réouverture sur le fil audio | documenté (L11) |

## 8. Améliorations issues de la seconde relecture (« fonctionnements pas optimaux »)

| # | Constat | Traitement |
|---|---|---|
| 1 | L'alignement à l'heure du fondu mangeait la fenêtre (7 à 10 s pour un réglage de 10 s) | **alignement au pré-armement** (§2.3), vérification seule à l'heure du fondu |
| 2 | Deux morceaux à pleine bande dix secondes (mix boueux) | **échange de basses** par égaliseurs (§2.5) |
| 3 | Fondu aveugle au contenu (silence encodé de fin) | **fin musicale** mesurée à l'analyse, v4 (§2.2) |
| 4 | Gestes aussi longs que l'automatique | 2,5 s / 1,5 s (§2.6) |
| 5 | Pas de fondu entre deux mix | **greffe du plan suivant** sur la lecture (§4) |
| 6 | Queue coupée net sur un geste ; EQ créé à la bascule ; pont fixe | effacement 150 ms ; EQ au pré-armement ; pont proportionnel au résidu |
| 7 | Durées en secondes, techniques aplaties par le plafond | **mesures** par technique (§3.5) |
| 8 | Phrase sacrifiée quand le fondu ne rentre pas | **la phrase d'abord**, fondu adapté (§3.6) |
| 9 | Tout l'étirement sur l'entrant | **calage partagé** (§3.4) |
| 10 | Coût de plan linéaire, aveugle à la falaise blend/coupe | marche à 8 % (`MixEngine.cost`) |
| 11 | Technique choisie sur le caractère moyen, ancre sur le premier beat | **section locale** + **ancre sur le drop** (§3.2, §3.5) |
| 12 | Pré-roll sans montée détectée | pré-roll seulement dans une montée (§3.6) |
| 13 | Drop-swap écrasé par le limiteur pendant la montée | sortant tenu à 0,8 (§3.7) |
| 14 | Boucle de sortie comme rustine de planification | résolu par 8 |
| 15 | Panneau Performance retardé de 2,3 s | **non modifié** à la demande de l'auteur (L12) |
| 16 | Seek DJ de 8 s | deux mesures (§3.8) |
| — | Réveil muet (rapporté) | canal alarme, plancher audible, filet sonore, journal (§5.2) |
