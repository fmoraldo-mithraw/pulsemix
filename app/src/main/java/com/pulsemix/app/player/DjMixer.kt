package com.pulsemix.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.net.Uri
import com.pulsemix.app.analysis.AudioDecoder
import com.pulsemix.app.analysis.StructureDetector
import com.pulsemix.app.data.Track
import com.pulsemix.app.mix.MixEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Moteur du mode DJ.
 *
 * Chaque morceau n'est joué que sur sa « meilleure minute ». Deux decks
 * décodent en parallèle ; le morceau entrant est resamplé pour caler son BPM
 * sur le morceau en cours (façon pitch fader, ±8 %), son premier beat est
 * aligné à l'échantillon près sur la grille de beats du deck actif, puis un
 * crossfade equal-power fait la transition. Les boutons next/previous
 * passent au morceau suivant/précédent (transition immédiate, fondu court).
 */
class DjMixer(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onTrackChanged(track: Track, phaseIndex: Int)
        fun onProgress(progress: Float)
        /** @param natural true si le set est allé à son terme. */
        fun onStopped(natural: Boolean)
        /** Session audio du moteur DJ (pour y attacher l'égaliseur). */
        fun onSessionReady(sessionId: Int) {}
        /** Une transition (deux decks en vol) démarre ou se termine —
         *  pilote l'activation du crossfader du panneau « Performance ». */
        fun onTransitionChanged(active: Boolean) {}
        /** Le DERNIER passage du set entre dans ses ultimes secondes :
         *  c'est le moment de préparer l'enchaînement automatique, PENDANT
         *  que la lecture tourne encore (le processus est vivant et le
         *  service au premier plan — après l'arrêt, plus rien ne garantit
         *  qu'un décompte survive en arrière-plan). */
        fun onSetEnding(remainingMs: Long) {}
    }

    private data class Segment(val track: Track, val phaseIndex: Int)

    companion object {
        const val OUT_SR = 44100
        const val BLOCK_FRAMES = 2048
        // Fondus longs (plusieurs mesures) pour « sentir arriver » le
        // morceau entrant : il pose d'abord ses basses seules (filtré),
        // et ne s'ouvre en pleine bande qu'au moment où le sortant
        // s'efface — jamais deux morceaux entiers en même temps.
        const val FADE_NORMAL_S = 14.0
        const val FADE_JUMP_S = 5.0
        // Jonctions adaptatives : long blend quand tempos calés et tonalités
        // compatibles, coupe franche quand le calage est impossible.
        const val FADE_LOCKED_HARMONIC_S = 18.0
        const val FADE_CUT_S = 6.0
        // Déplacement dans le morceau : vraie transition vers le même
        // morceau repris ailleurs, de la longueur d'une jonction entre deux
        // morceaux — on ne saute pas, on se déplace en musique.
        const val SEEK_FADE_S = 8.0
        const val TAIL_MS = 16_000L
        // Durée minimale d'un passage en mode DJ : en dessous, on n'a pas
        // le temps d'apprécier le morceau entre deux transitions.
        const val MIN_SEGMENT_MS = 60_000L
        // Avance de l'annonce de fin de set (onSetEnding) : assez pour un
        // décompte visible ET pour régénérer le mix suivant pendant que la
        // lecture tourne encore.
        const val SET_ENDING_LEAD_S = 12L
        // Calage sur la structure : la frontière de section retenue ne
        // raccourcit jamais le passage sous ce plancher (déjà les fondus
        // mangent ~30 s à eux deux).
        const val SNAP_MIN_PLAY_MS = 20_000L
        const val HALF_PI = (Math.PI / 2).toFloat()
        // One-pole ~120 Hz à 44,1 kHz pour l'extraction des basses
        const val BASS_ALPHA = 0.017f
        // Bass swap : atténuation des basses du deck « en retrait » pendant
        // le crossfade (une seule ligne de basse à la fois)
        const val BASS_SWAP_CUT = 0.95f
        // Boucle de sortie : durée maximale rejouée en boucle (garde-fou)
        const val LOOP_MAX_OUT: Long = 30L * 44_100L
        // Retour au tempo naturel après une transition : le morceau
        // s'installe pendant 4 s, puis remonte à ~1 %/s (un calage de 8 %
        // se résorbe en ~7 s, sous le seuil où l'oreille suit la dérive).
        const val SETTLE_FRAMES: Long = 4L * 44_100L
        const val NATURAL_STEP = 0.0005f
        // Marge de sortie supplémentaire contre les saccades : 0,3 s de
        // PCM float stéréo (8 octets par frame)
        const val OUT_EXTRA_FRAMES = 13_230 // 0,3 s à 44,1 kHz
        const val OUT_EXTRA_BYTES = OUT_EXTRA_FRAMES * 8
        // One-pole ~2,5 kHz : extraction des médiums (mid swap)
        const val MID_ALPHA = 0.30f
        // Types de transition : une technique de DJ par situation
        const val KIND_NORMAL = 0   // filter sweep passe-haut (le sortant s'amincit)
        const val KIND_CUT = 1      // coupe courte + echo-out
        const val KIND_HARMONIC = 2 // long blend + mid swap
        const val KIND_DARK = 3     // filter sweep passe-bas (le sortant s'étouffe)
        const val KIND_EQ = 4       // échange de basses classique, sans filtre
        const val ECHO_FEEDBACK = 0.55f


        // Forme de la jonction, par technique. Principe commun : une seule
        // source par bande à chaque instant. Le sortant cède ses basses
        // très tôt, l'entrant n'apporte D'ABORD que les siennes (passe-bas
        // raide), puis s'ouvre vers le haut pendant que le sortant s'efface.
        // [bassOutStart, bassOutEnd, openStart, openEnd, holdA, riseB]
        //  - bassOut* : fenêtre où le sortant perd ses basses
        //  - open*    : fenêtre où l'entrant s'ouvre au-delà des basses
        //  - holdA    : le sortant reste à plein volume jusque-là
        //  - riseB    : l'entrant atteint son plein volume à cet instant
        val SHAPE_NORMAL = floatArrayOf(0.04f, 0.18f, 0.30f, 0.72f, 0.52f, 0.74f)
        val SHAPE_DARK = floatArrayOf(0.08f, 0.26f, 0.42f, 0.86f, 0.58f, 0.82f)
        val SHAPE_EQ = floatArrayOf(0.04f, 0.16f, 0.24f, 0.64f, 0.46f, 0.68f)
        val SHAPE_HARMONIC = floatArrayOf(0.28f, 0.44f, 0.30f, 0.80f, 0.45f, 0.80f)
        // Coupure du passe-bas de l'entrant : basses seules -> bande pleine
        const val OPEN_FC_LOW = 140f
        const val OPEN_FC_HIGH = 16_000f

        /**
         * Cale le BPM du morceau entrant sur le BPM effectif du deck actif
         * (±8 %). Fonction PURE (companion, internal) : c'est elle qui
         * décide si deux morceaux peuvent être battus ensemble, et une
         * régression s'entendrait sans se voir — d'où les tests JVM.
         * @param effBpm BPM effectif du deck actif (bpm × rate courant).
         */
        internal fun computeRate(effBpm: Float, nextBpm: Float): Float {
            if (effBpm <= 0f || nextBpm <= 0f) return 1f
            val base = effBpm / nextBpm
            val candidates = floatArrayOf(base, base * 2f, base / 2f)
            var best = base.coerceIn(0.92f, 1.08f)
            var bestDist = Float.MAX_VALUE
            for (c in candidates) {
                if (c in 0.92f..1.08f) {
                    val d = kotlin.math.abs(c - 1f)
                    if (d < bestDist) {
                        bestDist = d
                        best = c
                    }
                }
            }
            return best
        }

        /**
         * Durée ET technique de la jonction, choisies par transition comme
         * un DJ choisit son geste :
         *  - tempos calés ET tonalités compatibles : long blend (18 s) avec
         *    bass swap puis mid swap (KIND_HARMONIC) ;
         *  - calage impossible (écart > ±8 %, hors double/moitié) : coupe
         *    courte (6 s) avec echo-out (KIND_CUT) — étirer deux tempos non
         *    synchrones ferait battre les beats ;
         *  - tempos calés, tonalités moyennes (le cas le plus fréquent) : la
         *    technique est choisie selon le caractère des deux morceaux —
         *    entrant calme → sweep sombre et long ; deux morceaux
         *    énergiques → palette punchy (sweep, coupe écho, bass swap) ;
         *    sortant brillant → plutôt sweep sombre ; sinon sweep
         *    clair/sombre/bass swap. Un tirage déterministe par paire varie
         *    le choix, sans jamais répéter la technique précédente quand
         *    plusieurs conviennent ;
         *  - saut de phase manuel : fondu court (5 s), bass swap neutre.
         *
         * Fonction PURE (companion, internal, testée en JVM) : l'état
         * qu'elle lisait sur le Deck est passé en paramètres ([curRate],
         * [lastKind]).
         */
        internal fun fadeSpec(
            current: Track,
            curRate: Float,
            next: Track,
            rate: Float,
            jumping: Boolean,
            lastKind: Int
        ): Pair<Double, Int> {
            if (jumping) return FADE_JUMP_S to KIND_EQ
            val effA = current.bpm * curRate
            val effB = next.bpm * rate
            if (effA <= 0f || effB <= 0f) return FADE_NORMAL_S to KIND_EQ
            val ratio = effA / effB
            val lockErr = minOf(
                abs(ratio - 1f),
                abs(ratio - 2f) / 2f,
                abs(ratio - 0.5f) * 2f
            )
            if (lockErr > 0.005f) return FADE_CUT_S to KIND_CUT
            val harmonic = MixEngine.camelotScore(current.camelot, next.camelot)
            if (harmonic >= 0.8f) return FADE_LOCKED_HARMONIC_S to KIND_HARMONIC

            val eOut = current.energyMean
            val eIn = next.energyMean
            val bright = current.centroid
            // Caractère des deux morceaux, quand l'analyse récente l'a
            // mesuré : un morceau TENU (nappes, chœurs, cuivres) supporte
            // mal une coupe sèche — le son s'interrompt en pleine tenue —
            // et se marie aux longues transitions ; un morceau PERCUSSIF
            // fait l'inverse. Les anciennes analyses laissent ces champs à
            // zéro : les règles historiques s'appliquent alors, inchangées.
            val sustained = current.sustainRatio > 0.65f &&
                next.sustainRatio > 0.55f
            val percussive = current.sustainRatio in 0.01f..0.45f &&
                next.sustainRatio in 0.01f..0.45f
            val pool: List<Pair<Double, Int>> = when {
                // Deux morceaux tenus : fondus longs et filtres doux, jamais
                // de coupe — c'est ici que les CUT écorchaient l'oreille
                sustained -> listOf(
                    FADE_LOCKED_HARMONIC_S to KIND_HARMONIC,
                    20.0 to KIND_DARK,
                    FADE_NORMAL_S to KIND_NORMAL
                )
                // Deux morceaux percussifs : gestes francs, la coupe écho
                // tombe sur des attaques et sonne voulue
                percussive && eOut > 0.14f && eIn > 0.14f -> listOf(
                    7.0 to KIND_CUT,
                    FADE_NORMAL_S to KIND_NORMAL,
                    11.0 to KIND_EQ
                )
                // Entrant calme : arrivée en douceur, sortant qui s'étouffe
                eIn > 0f && eIn < 0.10f -> listOf(
                    20.0 to KIND_DARK,
                    FADE_NORMAL_S to KIND_NORMAL,
                    14.0 to KIND_EQ
                )
                // Deux morceaux énergiques : gestes francs, coupe écho permise
                eOut > 0.17f && eIn > 0.17f -> listOf(
                    FADE_NORMAL_S to KIND_NORMAL,
                    7.0 to KIND_CUT,
                    11.0 to KIND_EQ
                )
                // Sortant brillant : l'étouffer (passe-bas) sonne naturel
                bright > 2_600f -> listOf(
                    17.0 to KIND_DARK,
                    FADE_NORMAL_S to KIND_NORMAL,
                    12.0 to KIND_EQ
                )
                else -> listOf(
                    FADE_NORMAL_S to KIND_NORMAL,
                    17.0 to KIND_DARK,
                    12.0 to KIND_EQ
                )
            }
            // Tirage stable par paire de morceaux, sans répéter la technique
            // de la transition précédente
            var idx = ((current.uri.hashCode() * 31 + next.uri.hashCode())
                ushr 1) % pool.size
            if (pool[idx].second == lastKind) idx = (idx + 1) % pool.size
            return pool[idx]
        }

        /**
         * Cale la fin de passage sur une frontière de section quand le
         * morceau a une structure détectée : un DJ sort sur une fin de
         * phrase — idéalement la fin d'un temps fort — pas sur un point
         * arbitraire au milieu d'une section. La frontière est cherchée à
         * ± une phrase autour de la fin calculée ; dans cette fenêtre, une
         * fin de temps fort (DROP) l'emporte toujours sur toute autre
         * frontière, même plus proche. Sans structure (ancienne analyse),
         * sans BPM, ou si aucune frontière ne convient, la fin reste telle
         * quelle : le comportement historique du moteur ne bouge pas.
         *
         * Fonction PURE (companion, internal, testée en JVM) comme
         * fadeSpec — appelée à l'ouverture d'un deck, jamais dans la
         * boucle audio par bloc.
         *
         * @param endMs fin de passage calculée (ancre + passage arrondi).
         * @param anchorMs début du passage fort : la frontière retenue ne
         *   doit pas raccourcir le passage sous [SNAP_MIN_PLAY_MS].
         */
        internal fun snapEndToStructure(
            endMs: Long,
            anchorMs: Long,
            bpm: Float,
            durationMs: Long,
            sections: List<StructureDetector.Section>
        ): Long {
            if (sections.isEmpty() || bpm <= 0f) return endMs
            val phraseMs = Math.round(StructureDetector.phraseMs(bpm, durationMs))
            if (phraseMs <= 0L) return endMs
            val floorMs = anchorMs + SNAP_MIN_PLAY_MS
            var best = endMs
            var bestScore = Long.MAX_VALUE
            for (s in sections) {
                val b = s.endMs
                if (b < floorMs || b > durationMs) continue
                val d = abs(b - endMs)
                if (d > phraseMs) continue
                // d <= phrase pour tous les candidats : « d + phrase + 1 »
                // classe toute frontière ordinaire derrière n'importe
                // quelle fin de temps fort de la fenêtre.
                val score = if (s.kind == StructureDetector.SectionKind.DROP) d
                else d + phraseMs + 1
                if (score < bestScore) {
                    bestScore = score
                    best = b
                }
            }
            return best
        }

        // Crossfader manuel (panneau « Performance ») : durée de la rampe
        // qui ramène les gains manuels vers la courbe automatique après
        // « Auto » (et symétriquement à la saisie) — un basculement sec
        // ferait sauter le volume.
        const val MANUAL_RESUME_FRAMES = 11_025 // 250 ms à 44,1 kHz

        /**
         * Gains du crossfader manuel PENDANT une transition : mêmes
         * courbes equal-power que le long blend automatique — la position
         * du fader (0 = deck A plein, 1 = deck B plein) remplace la
         * progression temporelle du fondu. Deux fonctions scalaires
         * plutôt qu'une paire : la boucle audio les lit par bloc et ne
         * doit rien allouer (un Pair boxerait deux Float).
         * Fonctions PURES (companion, internal, testées en JVM).
         */
        internal fun fadeGainA(pos: Float): Float =
            cos(pos.coerceIn(0f, 1f) * HALF_PI)

        internal fun fadeGainB(pos: Float): Float =
            sin(pos.coerceIn(0f, 1f) * HALF_PI)

        /**
         * Fader manuel HORS transition : un seul deck joue — pousser le
         * fader vers B ne déclenche RIEN (pas de deck fantôme), il
         * n'atténue que le deck actif : plein volume jusqu'à mi-course,
         * puis le même quart de cosinus que l'equal-power vers le silence.
         * Fonction PURE (companion, internal, testée en JVM).
         */
        internal fun soloGain(pos: Float): Float {
            val p = pos.coerceIn(0f, 1f)
            return if (p <= 0.5f) 1f else cos((p - 0.5f) * 2f * HALF_PI)
        }

        /**
         * Mélange des gains automatiques vers les gains du fader :
         * blend 1 = tout manuel, 0 = courbe du moteur. C'est cette
         * interpolation que parcourt la rampe de saisie/reprise
         * ([MANUAL_RESUME_FRAMES]) — jamais de saut, dans aucun sens.
         * Fonction PURE (companion, internal, testée en JVM).
         */
        internal fun blendGain(auto: Float, manual: Float, blend: Float): Float =
            auto + (manual - auto) * blend.coerceIn(0f, 1f)
    }

    /** Détecteur d'attaques (kicks) sur l'enveloppe de basses d'un deck. */
    private class OnsetTracker {
        private var mean = 0f
        var lastOnsetFrame = -1L
            private set
        private var refractoryUntil = 0L

        fun feed(energy: Float, frame: Long, refractoryFrames: Long) {
            if (mean <= 0f) mean = energy
            if (energy > 1.7f * mean && energy > 1e-4f && frame >= refractoryUntil) {
                lastOnsetFrame = frame
                refractoryUntil = frame + refractoryFrames
            }
            mean += 0.05f * (energy - mean)
        }
    }

    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    /**
     * Génération du run : chaque start() l'incrémente et le thread de mix
     * capture sa valeur. Un thread dont le join() a expiré au stop() ne
     * peut plus ni reprendre quand un nouveau start() remet `running` à
     * vrai (deux boucles, deux AudioTrack), ni faire aboutir ses
     * callbacks différés sur le set suivant.
     */
    @Volatile private var runGeneration = 0
    @Volatile private var paused = false
    @Volatile private var pendingJump = -1
    // Position visée par la barre de progression (-1 = aucune)
    @Volatile private var pendingSeek = -1f
    @Volatile private var currentPhaseIndex = 0
    // Segment joué par le deck actif — lu par le bouton « précédent »
    // depuis le thread UI, écrit par la boucle de mix.
    @Volatile private var currentSegIndex = 0
    @Volatile private var startSegIndex = 0
    @Volatile private var rehearsal = false
    @Volatile private var recorder: MixRecorder? = null
    /**
     * AudioTrack du run en cours, posé par runMix (où il reste une variable
     * locale, créée et libérée dans son finally) : stop() en a besoin pour
     * débloquer une écriture en cours depuis le thread appelant.
     */
    @Volatile private var liveAudioTrack: AudioTrack? = null

    // ---- Pilotage manuel (panneau « Performance ») ----
    // Écrits par le thread UI, lus une fois par bloc par la boucle audio :
    // des primitives @Volatile suffisent (pas de synchronized dans le
    // chemin chaud), et le sentinel -1 évite de boxer un Float?.
    @Volatile private var manualFade = -1f      // <0 = auto ; 0..1 = fader A↔B
    @Volatile private var manualBassKillA = false
    @Volatile private var manualBassKillB = false
    @Volatile private var manualLoopBeats = 0   // 0 = off ; 4 ou 8 temps
    @Volatile private var pendingManualNudge = 0f
    @Volatile private var transitionActive = false

    /** Active/coupe l'enregistrement du set (fichier M4A). */
    fun setRecorder(r: MixRecorder?) {
        val old = recorder
        recorder = r
        old?.stop()
    }

    private var segments: List<Segment> = emptyList()
    private var plan: MixEngine.MixPlan? = null
    private var mixThread: Thread? = null
    private var phaseLengthFactor = FloatArray(0)
    // Dernière technique utilisée : évite deux fois de suite la même quand
    // plusieurs conviennent (variété façon DJ)
    private var lastFadeKind = -1
    // Décalage entre ce qui est calculé et ce qui sort des haut-parleurs
    @Volatile private var outLatencyMs = 0L
    // Suivi du cran de vitesse manuel : un changement demandé s'applique
    // vite, le retour au tempo naturel se fait en douceur
    private var lastSpeedLevel = 0
    private var manualRamp = false

    // ------------------------------------------------------------------ API

    /**
     * @param startPhase phase de départ (reprise après fermeture/plantage).
     * @param rehearsalMode true = répétition des transitions : chaque morceau
     * est avancé jusqu'à ses ~15 dernières secondes, on n'entend que les
     * jonctions.
     */
    fun start(plan: MixEngine.MixPlan, startPhase: Int = 0, rehearsalMode: Boolean = false) {
        stop()
        rehearsal = rehearsalMode
        this.plan = plan
        segments = plan.phases.flatMapIndexed { pi, phase ->
            phase.tracks.filter { it.analyzed && it.bpm > 0f }.map { Segment(it, pi) }
        }
        if (segments.isEmpty()) {
            ui.post { listener.onStopped(false) }
            return
        }
        // Modulation par phase : segments un peu plus longs dans les phases
        // énergiques (peak), un peu plus courts dans les phases calmes.
        val phaseEnergy = plan.phases.map { ph ->
            val analyzed = ph.tracks.filter { it.analyzed }
            if (analyzed.isEmpty()) 0f
            else analyzed.map { it.energyPeak }.sum() / analyzed.size
        }
        val eMin = phaseEnergy.minOrNull() ?: 0f
        val eMax = phaseEnergy.maxOrNull() ?: 0f
        phaseLengthFactor = FloatArray(plan.phases.size) { i ->
            val t = if (eMax > eMin) (phaseEnergy[i] - eMin) / (eMax - eMin) else 0.5f
            0.85f + 0.30f * t
        }
        running = true
        paused = false
        pendingJump = -1
        resetManualControls()
        startSegIndex = segments.indexOfFirst { it.phaseIndex >= startPhase }
            .let { if (it < 0) 0 else it }
        currentPhaseIndex = segments[startSegIndex].phaseIndex
        runGeneration++
        val gen = runGeneration
        mixThread = thread(name = "DjMixer", priority = Thread.MAX_PRIORITY) { runMix(gen) }
    }

    fun stop() {
        running = false
        paused = false
        resetManualControls()
        // Le thread audio peut être bloqué ~1 s dans audioTrack.write
        // (WRITE_BLOCKING, tampon volontairement large) : pause + flush le
        // débloquent tout de suite, au lieu de faire attendre le thread
        // appelant (UI) toute la durée de l'écriture.
        try {
            liveAudioTrack?.pause()
            liveAudioTrack?.flush()
        } catch (_: Exception) {
        }
        // Join court : le jeton de génération (runGeneration) rend de toute
        // façon inoffensif un thread qui traînerait — il ne peut ni
        // reprendre la boucle ni livrer ses callbacks sur le set suivant.
        mixThread?.join(500)
        mixThread = null
    }

    fun setPaused(p: Boolean) {
        paused = p
    }

    val isRunning: Boolean get() = running

    /**
     * Transition immédiate vers le morceau suivant (bouton « suivant »).
     * -2 se résout dans la boucle de mix : morceau d'après le deck actif —
     * ou d'après la transition déjà en vol, pour que deux appuis
     * rapprochés avancent bien de deux morceaux.
     */
    fun nextTrack() {
        pendingJump = -2
    }

    /**
     * Transition vers le morceau précédent (bouton « précédent »). Au tout
     * premier morceau du set, il repart de son début.
     */
    fun prevTrack() {
        pendingJump = max(0, currentSegIndex - 1)
    }

    /**
     * Déplacement dans le morceau en cours (barre de progression). Il n'y a
     * pas de « saut » en DJ : on ouvre un second deck sur le même morceau à
     * l'endroit visé et on y fait une vraie transition, comme entre deux
     * morceaux. @param fraction position visée dans le passage joué.
     */
    fun requestSeek(fraction: Float) {
        pendingSeek = fraction.coerceIn(0f, 1f)
    }

    // ------------------------------------------ pilotage manuel (performance)

    /**
     * Crossfader manuel A↔B. Non-null : la position remplace la
     * progression temporelle du fondu (mêmes courbes equal-power) tant
     * qu'une transition est en vol ; hors transition elle n'atténue que
     * le deck actif — elle ne déclenche RIEN (pas de deck fantôme).
     * Tant que le fader est tenu, la bascule de fin de fondu attend (le
     * deck sortant tient sous sa boucle de sortie). null = « Auto » :
     * reprise du pilotage automatique en rampe courte (~250 ms) depuis
     * la position du fader, pas de saut de volume.
     */
    fun setManualFade(pos: Float?) {
        manualFade = pos?.coerceIn(0f, 1f) ?: -1f
    }

    /** Kill des basses d'un deck (A = actif/sortant, B = entrant). Force
     *  la coupe pleine par le même chemin que le bass swap automatique —
     *  qui s'incline : le kill manuel l'emporte tant qu'il est enclenché. */
    fun setBassKill(deckA: Boolean, on: Boolean) {
        if (deckA) manualBassKillA = on else manualBassKillB = on
    }

    /** Boucle de sortie manuelle : les [beats] derniers temps du deck
     *  actif tournent en boucle, calés sur la mesure (0 = reprise du flux,
     *  avec slip). Tient le morceau en attendant de lancer la transition —
     *  et si le passage s'épuise pendant la boucle, la boucle de sortie
     *  automatique garde le deck en vie. */
    fun setManualLoop(beats: Int) {
        manualLoopBeats = if (beats == 4 || beats == 8) beats else 0
    }

    /** Nudge tempo (petite retouche momentanée) : délégué au syncNudge du
     *  deck qu'un DJ recale — l'ENTRANT pendant une transition (le sortant
     *  sert de référence, comme pour le beatlock automatique), le deck
     *  actif sinon. Même borne cumulée que l'existant (±0,4 %). Posé ici,
     *  consommé par la boucle audio au bloc suivant : le rate d'un deck
     *  n'est jamais touché que par le thread de mix. */
    fun nudgeTempo(deltaPct: Float) {
        pendingManualNudge = deltaPct.coerceIn(-0.004f, 0.004f)
    }

    /** Une transition (deux decks) est-elle en vol ? Miroir de
     *  [Listener.onTransitionChanged], pour lecture directe. */
    val isTransitionActive: Boolean get() = transitionActive

    /** Remise à zéro des commandes manuelles : elles ne survivent ni à un
     *  stop() ni au start() d'un nouveau set — un fader ou un kill hérités
     *  d'un run précédent rendraient le suivant muet. */
    private fun resetManualControls() {
        manualFade = -1f
        manualBassKillA = false
        manualBassKillB = false
        manualLoopBeats = 0
        pendingManualNudge = 0f
        transitionActive = false
    }

    // ------------------------------------------------------------------ deck

    private inner class Deck(
        val segIndex: Int,
        val segment: Segment,
        val rate: Float,
        lengthFactor: Float = 1f,
        // Dernier morceau du set : pas de morceau après, on le TERMINE
        // (lecture jusqu'à la vraie fin, pas de boucle de sortie).
        val playToEnd: Boolean = false,
        // Premier morceau du set : il commence au DÉBUT du fichier (avec
        // l'option « sauter les intros parlées » si active), et déroule
        // jusqu'à la fin de son passage fort où la transition a lieu.
        val playFromStart: Boolean = false,
        // Déplacement manuel dans le morceau : le deck reprend là plutôt
        // qu'au début du passage fort (la fin, elle, ne bouge pas).
        val seekFromMs: Long? = null
    ) {
        val track: Track = segment.track

        // Normalisation du volume : atténue/renforce vers un niveau commun.
        // Gain MESURÉ à l'analyse (gainDb != 0) de préférence, formule
        // historique par energyMean sinon — mêmes bornes DJ dans les deux cas.
        val gain: Float = when {
            !PlayerCore.normalizeVolume.value -> 1f
            track.gainDb != 0f ->
                com.pulsemix.app.analysis.AudioAnalyzer.gainFactor(track.gainDb)
                    .coerceIn(0.6f, 1.6f)
            track.energyMean > 0.01f ->
                (0.18f / track.energyMean).coerceIn(0.6f, 1.6f)
            else -> 1f
        }

        @Volatile var closed = false
        @Volatile var decoderDone = false
        @Volatile var srcSr = 0
        // ~4 s de son décodé d'avance : quand on change d'appli, le
        // chargement de l'autre appli accapare le CPU et les décodeurs
        // prennent du retard — cette réserve absorbe le pic sans saccade.
        val queue = ArrayBlockingQueue<FloatArray>(192)
        private val openLatch = CountDownLatch(1)

        val startMs: Long
        val logicalEndMs: Long
        private val decodeEndMs: Long

        /**
         * Part du passage déjà écoulée quand ce deck démarre. Nulle en
         * temps normal ; après un déplacement manuel, le deck ne joue que
         * la fin du passage et sa progression doit malgré tout se lire sur
         * le passage entier — sinon la barre repartirait de zéro.
         */
        var progressFrom = 0f
            private set

        var startedAtFrame = 0L
        var framesOut = 0L
            private set
        var finished = false
            private set

        // Filtres de basses du deck (bass swap + détection de kick pour le
        // verrouillage pendant le crossfade)
        var lpL = 0f
        var lpR = 0f
        // Passe-bas ~2,5 kHz : bande médiums = midLp - lp (mid swap)
        var midLpL = 0f
        var midLpR = 0f
        // Passe-bas balayé (filter sweep du sortant ; second étage pour
        // l'ouverture progressive de l'entrant : 2 pôles = 12 dB/octave,
        // assez raide pour n'entendre QUE les basses au début)
        var sweepLpL = 0f
        var sweepLpR = 0f
        var sweep2L = 0f
        var sweep2R = 0f
        val onsets = OnsetTracker()

        /** Micro-correction de synchro pendant le fade (cumul borné à ±0,4 %,
         *  relatif au rate courant : compatible avec le speed boost). */
        private var syncAccum = 0f
        fun syncNudge(delta: Float) {
            val d = delta.coerceIn(-0.004f - syncAccum, 0.004f - syncAccum)
            syncAccum += d
            curRate += d
        }

        // Retour progressif au tempo naturel après un calage (pitch ridé par
        // un DJ) : curRate glisse vers 1.0 une fois le crossfade terminé.
        @Volatile var curRate: Float = rate
            private set
        private var rampStarted = false
        private var beatPhase = 0.0 // en battements, tenue à jour dès la rampe

        /** Phase de battement à un instant donné (exacte avant toute rampe). */
        fun beatPhaseAt(frame: Long): Double =
            if (!rampStarted) (frame - startedAtFrame) / beatPeriodFrames
            else beatPhase

        /** Avance la phase d'un bloc (à appeler quand la rampe est active). */
        fun advancePhase(frames: Int) {
            if (rampStarted) beatPhase += frames / beatPeriodFrames
        }

        /** Rapproche le tempo de la cible d'un petit pas (hors crossfade).
         *  Cible 1.0 = tempo naturel ; > 1 = speed boost. */
        fun nudgeTowardNatural(step: Float, frameNow: Long, target: Float = 1f) {
            if (curRate == target) return
            if (!rampStarted) {
                beatPhase = (frameNow - startedAtFrame) / beatPeriodFrames
                rampStarted = true
            }
            curRate = if (curRate > target) max(target, curRate - step)
            else min(target, curRate + step)
        }

        private var ratio = 1.0
        private var srcPos = 1.0
        private var prevL = 0f; private var prevR = 0f
        private var nextL = 0f; private var nextR = 0f
        private var curChunk: FloatArray? = null
        private var curFrames = 0
        private var curPos = 0

        // Boucle de sortie (« loop out ») : capture circulaire des derniers
        // battements produits ; à la fin du passage fort, les 8 derniers
        // battements sont rejoués en boucle sous le fondu de sortie.
        // Dimensionnée pour le rate le plus lent possible (crans de vitesse
        // négatifs : jusqu'à -24 %) + l'amorce de couture (~50 ms)
        private val loopCapacity: Int =
            if (track.bpm > 0f) (8.0 * 60.0 / track.bpm * OUT_SR / 0.74).toInt() + 2
            else OUT_SR * 4
        private val loopCapture = FloatArray(loopCapacity * 2)
        private var loopWritePos = 0
        private var loopFilled = 0
        private var loopData: FloatArray? = null
        private var loopPos = 0
        private var loopLen = 0
        private var loopXfade = 0
        private var loopedOut = 0L

        /** Active la boucle de sortie. @return false si trop peu de matière. */
        private fun startLoop(): Boolean {
            if (playToEnd) return false // dernier morceau : vraie fin, pas de boucle
            if (loopData != null) return true
            val period = beatPeriodFrames
            if (period <= 0.0 || period.isNaN()) return false
            var len = (8.0 * period).toInt()
            if (len > loopFilled) len = loopFilled
            if (len < OUT_SR / 2) return false
            // Amorce de couture : ~50 ms de signal d'avant-boucle, fondues
            // sur la fin de chaque passage pour un raccord sans claquement.
            var xf = min(2_205, len / 8)
            if (len + xf > loopFilled) xf = (loopFilled - len).coerceAtLeast(0)
            val total = len + xf
            val data = FloatArray(total * 2)
            var src = (loopWritePos - total + loopCapacity) % loopCapacity
            for (k in 0 until total) {
                data[k * 2] = loopCapture[src * 2]
                data[k * 2 + 1] = loopCapture[src * 2 + 1]
                src = (src + 1) % loopCapacity
            }
            loopData = data
            loopLen = len
            loopXfade = xf
            loopPos = xf // reprise 8 temps en arrière, juste après l'amorce
            loopedOut = 0
            return true
        }

        // Boucle live (bouton maintenu) : les derniers temps (4, ou 8 après
        // un tap) tournent en boucle pendant que la lecture continue « en
        // dessous » (slip). Au relâchement, la boucle repart de son début
        // pour UN dernier passage complet, puis le morceau reprend là où il
        // serait arrivé.
        private var liveData: FloatArray? = null
        private var liveLen = 0
        private var liveXf = 0
        private var livePos = 0
        private var liveFinishing = false
        private var liveRemain = 0
        // Quantisation : la boucle ne part pas à l'instant de l'appui (elle
        // coupait la phrase en plein milieu) mais à la prochaine fin de
        // mesure — elle contient alors des mesures entières calées sur la
        // grille de beats, donc un passage musicalement cohérent.
        private var liveArmFrame = -1L
        private var liveArmBeats = 4

        fun startLiveLoop(beats: Int) {
            if (liveData != null) {
                // Ré-appui pendant le dernier passage : on reboucle
                liveFinishing = false
                return
            }
            if (liveArmFrame >= 0) return // déjà armé, en attente de la mesure
            val period = beatPeriodFrames
            if (period <= 0.0 || period.isNaN()) return
            val phase = framesOut / period
            val nextBar = ceil(phase / 4.0) * 4.0
            liveArmFrame = framesOut + ((nextBar - phase) * period).toLong()
            liveArmBeats = beats
        }

        /** Capture et active la boucle (appelé pile sur la fin de mesure). */
        private fun activateLiveLoop(beats: Int) {
            val period = beatPeriodFrames
            if (period <= 0.0 || period.isNaN()) return
            val len = (beats.toDouble() * period).toInt()
            if (len < OUT_SR / 4 || len + 512 > loopFilled) return
            var xf = min(2_205, len / 8)
            if (len + xf > loopFilled) xf = loopFilled - len
            val total = len + xf
            val data = FloatArray(total * 2)
            var src = (loopWritePos - total + loopCapacity) % loopCapacity
            for (k in 0 until total) {
                data[k * 2] = loopCapture[src * 2]
                data[k * 2 + 1] = loopCapture[src * 2 + 1]
                src = (src + 1) % loopCapacity
            }
            liveLen = len
            liveXf = xf
            livePos = xf
            liveFinishing = false
            liveData = data
        }

        fun stopLiveLoop() {
            // Relâché avant la fin de mesure : annuler l'armement
            if (liveData == null) {
                liveArmFrame = -1L
                return
            }
            if (liveFinishing) return
            // Dernier passage : repartir du début de la boucle une fois
            liveFinishing = true
            livePos = liveXf
            liveRemain = liveLen
        }

        init {
            val best = track.bestStartMs.coerceIn(0L, max(0L, track.durationMs - 15_000L))
            val beat = track.firstBeatMs
            // Ancre sur le premier beat du passage fort
            val anchor = if (beat in best..(best + track.segmentMs)) beat else best
            // Modulation par phase, puis ré-arrondi aux phrases de 16 temps
            // pour que la fin reste sur une frontière musicale.
            var segMs = (track.segmentMs * lengthFactor).toLong()
            // Plancher : un passage plus court ne laisse pas le temps
            // d'apprécier le morceau (les fondus mangent déjà ~30 s à eux
            // deux). Borné par ce qu'il reste de morceau après l'ancre.
            segMs = max(segMs, min(MIN_SEGMENT_MS, track.durationMs - anchor))
            if (track.bpm > 0f) {
                val phraseMs = 16.0 * 60_000.0 / track.bpm
                var phrases = floor(segMs / phraseMs).toLong()
                // L'arrondi ne doit pas faire repasser sous le plancher
                if (phrases * phraseMs < segMs - 1) {
                    phrases = ceil(segMs / phraseMs).toLong()
                }
                if (phrases >= 2) segMs = (phrases * phraseMs).toLong()
            }
            // Le deck démarre pile sur le passage fort et déroule le morceau
            // naturellement sous le fondu d'entrée (pas de boucle de début :
            // essayée, jugée décevante). À l'autre bout, la boucle de sortie
            // (8 derniers battements) prend le relais sous le fondu de sortie.
            // Quand le morceau a une structure détectée, la fin de passage
            // est calée sur la frontière de section la plus proche (fin d'un
            // temps fort de préférence) : la transition part d'une vraie fin
            // de phrase. Structure vide (ancienne analyse) : rien ne change.
            val end = if (playToEnd && track.durationMs > anchor)
                track.durationMs
            else snapEndToStructure(
                min(anchor + segMs, track.durationMs), anchor, track.bpm,
                track.durationMs,
                if (track.structure.isEmpty()) emptyList()
                else StructureDetector.decode(track.structure)
            )
            startMs = when {
                // Déplacement manuel : au moins 10 s à jouer après le point
                // visé, sinon le deck n'aurait pas de quoi tenir le fondu
                seekFromMs != null ->
                    seekFromMs.coerceIn(0L, max(0L, end - 10_000L))
                playFromStart ->
                    if (PlayerCore.skipIntros.value && track.musicStartMs > 1_500L)
                        track.musicStartMs
                    else 0L
                else -> anchor
            }
            logicalEndMs = end
            progressFrom = if (seekFromMs != null && end > anchor)
                ((startMs - anchor).toFloat() / (end - anchor))
                    .coerceIn(0f, 0.98f)
            else 0f
            decodeEndMs = min(logicalEndMs + 2_000, track.durationMs)

            thread(name = "DjDeck-${track.title.take(12)}") {
                // Les décodeurs nourrissent les decks : sous-priorisés, la
                // famine fait saccader le son quand l'écran se verrouille.
                try {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_AUDIO
                    )
                } catch (_: Exception) {
                }
                AudioDecoder().decode(
                    context, Uri.parse(track.uri),
                    startUs = startMs * 1000,
                    maxDurationUs = max(1L, (decodeEndMs - startMs)) * 1000
                ) { pcm, frames, sr, ch ->
                    if (srcSr == 0) srcSr = sr
                    val stereo = FloatArray(frames * 2)
                    when (ch) {
                        1 -> for (f in 0 until frames) {
                            val v = pcm[f]; stereo[2 * f] = v; stereo[2 * f + 1] = v
                        }
                        2 -> System.arraycopy(pcm, 0, stereo, 0, frames * 2)
                        else -> for (f in 0 until frames) {
                            stereo[2 * f] = pcm[f * ch]
                            stereo[2 * f + 1] = pcm[f * ch + 1]
                        }
                    }
                    var offered = false
                    while (!closed && running && !offered) {
                        offered = queue.offer(stereo, 100, TimeUnit.MILLISECONDS)
                    }
                    if (offered) openLatch.countDown()
                    !closed && running
                }
                decoderDone = true
                openLatch.countDown()
            }
        }

        /** Attend le premier chunk décodé. @return true si le deck est exploitable. */
        fun open(): Boolean {
            openLatch.await(4, TimeUnit.SECONDS)
            if (srcSr == 0) return false
            ratio = srcSr.toDouble() * curRate / OUT_SR
            pullSrcFrame()
            pullSrcFrame()
            srcPos = 0.0
            return true
        }

        val totalOutFrames: Long
            get() = ((logicalEndMs - startMs) / 1000.0 * OUT_SR / rate).toLong()

        val tailOutFrames: Long
            get() = ((decodeEndMs - logicalEndMs) / 1000.0 * OUT_SR / rate).toLong()

        val remainingOut: Long get() = max(0L, totalOutFrames - framesOut)

        /** Période de beat en frames de sortie, au rate courant. */
        val beatPeriodFrames: Double
            get() = OUT_SR * 60.0 / (track.bpm.toDouble() * curRate)

        private fun pullSrcFrame(): Boolean {
            while (true) {
                val c = curChunk
                if (c != null && curPos < curFrames) {
                    prevL = nextL; prevR = nextR
                    nextL = c[curPos * 2]; nextR = c[curPos * 2 + 1]
                    curPos++
                    return true
                }
                curChunk = null
                val polled = queue.poll(250, TimeUnit.MILLISECONDS)
                if (polled == null) {
                    if (decoderDone && queue.isEmpty()) return false
                    // Famine du décodeur : décroître doucement vers le silence
                    // (tenir une valeur fixe produisait un bourdonnement type
                    // bégaiement sous forte charge CPU)
                    prevL = nextL; prevR = nextR
                    nextL *= 0.98f; nextR *= 0.98f
                    return true
                }
                curChunk = polled
                curFrames = polled.size / 2
                curPos = 0
            }
        }

        /** Écrit `frames` frames stéréo dans dst à partir de dstFrameOffset. */
        fun read(dst: FloatArray, dstFrameOffset: Int, frames: Int): Int {
            if (finished || closed) return 0
            ratio = srcSr.toDouble() * curRate / OUT_SR
            var out = 0
            while (out < frames) {
                // Boucle de sortie active : rejouer les derniers battements
                val ld = loopData
                if (ld != null) {
                    if (loopedOut >= LOOP_MAX_OUT) {
                        finished = true
                        return out
                    }
                    val i = (dstFrameOffset + out) * 2
                    var sL = ld[loopPos * 2]
                    var sR = ld[loopPos * 2 + 1]
                    if (loopXfade > 0 && loopPos >= loopLen) {
                        // Couture fondue vers l'amorce d'avant-boucle
                        val q = loopPos - loopLen
                        val t = (q + 1).toFloat() / loopXfade
                        sL = sL * (1f - t) + ld[q * 2] * t
                        sR = sR * (1f - t) + ld[q * 2 + 1] * t
                    }
                    dst[i] = sL
                    dst[i + 1] = sR
                    loopPos++
                    if (loopPos >= loopLen + loopXfade) loopPos = loopXfade
                    loopedOut++
                    out++
                    framesOut++
                    continue
                }
                // Fin du passage fort : basculer sur la boucle
                if (framesOut >= totalOutFrames && startLoop()) continue
                while (srcPos >= 1.0) {
                    if (!pullSrcFrame()) {
                        if (!startLoop()) {
                            finished = true
                            return out
                        }
                        break
                    }
                    srcPos -= 1.0
                }
                if (loopData != null) continue
                val fr = srcPos.toFloat()
                val i = (dstFrameOffset + out) * 2
                val l = (prevL + (nextL - prevL) * fr) * gain
                val r = (prevR + (nextR - prevR) * fr) * gain
                dst[i] = l
                dst[i + 1] = r
                // Capture circulaire pour la boucle de sortie
                loopCapture[loopWritePos * 2] = l
                loopCapture[loopWritePos * 2 + 1] = r
                loopWritePos = (loopWritePos + 1) % loopCapacity
                if (loopFilled < loopCapacity) loopFilled++
                // Boucle armée : démarrage pile sur la fin de mesure
                if (liveArmFrame in 0..framesOut) {
                    liveArmFrame = -1L
                    activateLiveLoop(liveArmBeats)
                }
                // Boucle live active : la sortie vient de la boucle, le flux
                // vient d'avancer d'une frame en dessous (slip)
                val lv = liveData
                if (lv != null && liveLen > 0) {
                    var sL = lv[livePos * 2]
                    var sR = lv[livePos * 2 + 1]
                    if (liveXf > 0 && livePos >= liveLen && !liveFinishing) {
                        val q = livePos - liveLen
                        val t = (q + 1).toFloat() / liveXf
                        sL = sL * (1f - t) + lv[q * 2] * t
                        sR = sR * (1f - t) + lv[q * 2 + 1] * t
                    }
                    dst[i] = sL
                    dst[i + 1] = sR
                    livePos++
                    if (livePos >= liveLen + liveXf) livePos = liveXf
                    if (liveFinishing && --liveRemain <= 0) liveData = null
                }
                srcPos += ratio
                out++
                framesOut++
            }
            return out
        }

        fun close() {
            closed = true
            queue.clear()
        }
    }

    // ------------------------------------------------------------- mix loop

    private fun runMix(gen: Int) {
        // Priorité temps-réel audio : Thread.MAX_PRIORITY (Java) n'influence
        // pas l'ordonnanceur Linux — écran verrouillé, le CPU ralentit et le
        // thread se faisait voler des cycles (saccades).
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
            )
        } catch (_: Exception) {
        }
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(OUT_SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Réserve de sortie : c'est le temps dont dispose le thread de
            // mixage quand le système lui prend le CPU (ouverture d'une
            // autre appli, GC...). 0,3 s ajoutés aux ~0,75 s de base après
            // des saccades constatées à l'usage.
            .setBufferSizeInBytes(
                max(minBuf * 3, 256 * 1024) + OUT_EXTRA_BYTES
            )
            .build()
        // Exposé à stop() pour débloquer une écriture bloquante en cours ;
        // la propriété (création ET release, dans le finally) reste ici.
        liveAudioTrack = audioTrack
        // Le son écrit maintenant ne se fera entendre qu'une fois le tampon
        // écoulé : les annonces à l'interface sont retardées d'autant, sinon
        // le titre changerait une seconde avant qu'on entende le morceau.
        outLatencyMs = (
            audioTrack.bufferSizeInFrames.toLong() * 1000L / OUT_SR
            ).coerceIn(0L, 2_000L)

        var deckA: Deck? = null
        var deckB: Deck? = null
        val tmpA = FloatArray(BLOCK_FRAMES * 2)
        val tmpB = FloatArray(BLOCK_FRAMES * 2)
        val out = FloatArray(BLOCK_FRAMES * 2)
        var framesGlobal = 0L
        var fadeStartF = -1L
        var fadeLenF = 0L
        var fadeKindF = KIND_NORMAL
        // Fin de la dernière transition : le nouveau morceau garde le tempo
        // calé quelques secondes de plus avant de revenir au sien
        var fadeEndF = 0L
        var echoBuf: FloatArray? = null
        var echoPos = 0
        var endFadeFrames = -1L
        var blockCount = 0
        // Pilotage manuel (panneau « Performance ») : gains du fader et
        // rampes, locaux au run (un nouveau start() repart donc à zéro).
        // manualBlend : 1 = fader tenu, glisse en ~250 ms vers 0 après
        // « Auto » (et symétriquement à la saisie) — c'est lui qui évite
        // les sauts de volume dans les deux sens.
        var manualBlend = 0f
        var manualPos = 0f
        var manualGA = 1f
        var manualGB = 0f
        // Kill de basses manuel : cibles lissées par bloc (coupe nette = clic)
        var killSmA = 0f
        var killSmB = 0f
        // Dernier état de transition annoncé à l'UI
        var transAnnounced = false

        // Ouverture asynchrone du deck suivant (jamais sur le thread audio)
        class OpenResult(
            val deck: Deck?,
            val fadeS: Double,
            val fadeKind: Int,
            val jumping: Boolean,
            val jumpTarget: Int
        )

        val openResult =
            java.util.concurrent.atomic.AtomicReference<OpenResult?>(null)
        var opening = false
        var failedForSeg = -1
        // Fin de set annoncée une seule fois (dernier passage, dernières
        // secondes) — désarmée si un saut ramène en arrière dans le plan.
        var setEndingSent = false

        // Renfort dynamique des basses (sur le signal mixé)
        var mixLpL = 0f
        var mixLpR = 0f
        var bassGain = 0f
        var recentPeak = 0f
        // Bass boost manuel (bouton) : rampe progressive
        var manualBass = 0f
        // Limiteur doux de sortie (attaque rapide, relâche lente)
        var limGain = 1f
        // Effets live du panneau « Effets » : filtre maître balayé, écho calé
        // sur le tempo, auto-pan, gate rythmique
        var filtSm = 0f
        var filtLpL = 0f
        var filtLpR = 0f
        val echoDly = FloatArray(OUT_SR * 2) // 1 s stéréo
        var echoDlyPos = 0
        var echoSm = 0f
        var panPhase = 0.0
        var gatePhase = 0.0
        var gateSm = 1f

        // Répétition : avance rapide jusqu'aux ~15 dernières secondes du deck
        fun fastForward(d: Deck) {
            if (!rehearsal) return
            val tailFrames = 15L * OUT_SR
            var w = 0
            while (running && d.remainingOut > tailFrames) {
                if (d.read(tmpA, 0, BLOCK_FRAMES) == 0) break
                if (++w % 8 == 0) {
                    java.util.Arrays.fill(out, 0f)
                    audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                }
            }
            // recaler la grille de beats sur la position réellement atteinte
            d.startedAtFrame = framesGlobal - d.framesOut
        }

        fun openNextValid(
            fromIndex: Int,
            rate: Float,
            fromStart: Boolean = false
        ): Deck? {
            var idx = fromIndex
            while (running && idx < segments.size) {
                val factor = phaseLengthFactor.getOrElse(segments[idx].phaseIndex) { 1f }
                val d = Deck(
                    idx, segments[idx], rate, factor,
                    playToEnd = idx == segments.size - 1,
                    playFromStart = fromStart
                )
                if (d.open()) return d
                d.close()
                idx++
            }
            return null
        }

        try {
            audioTrack.play()
            ui.post { listener.onSessionReady(audioTrack.audioSessionId) }
            deckA = openNextValid(startSegIndex, 1f, fromStart = true)
            if (deckA == null) {
                djLog("aucun deck n'a pu s'ouvrir au lancement (décodage impossible ?)")
                return
            }
            deckA.startedAtFrame = 0L
            currentPhaseIndex = deckA.segment.phaseIndex
            currentSegIndex = deckA.segIndex
            announce(deckA)
            fastForward(deckA)

            while (running && gen == runGeneration) {
                // Pause (miroir du bouton play/pause et du Bluetooth).
                // 200 ms entre deux réveils : à 40 ms, ce thread (priorité
                // audio max) se réveillait 25 fois par seconde pour rien
                // pendant toute la pause ; la latence de reprise reste
                // imperceptible à 5 réveils par seconde.
                if (paused) {
                    audioTrack.pause()
                    while (paused && running && gen == runGeneration) Thread.sleep(200)
                    if (!running) break
                    audioTrack.play()
                }

                val a = deckA ?: break

                // Hors crossfade, le deck actif rejoint son tempo cible.
                // Deux régimes très différents :
                //  - retour au tempo naturel après une transition : le
                //    morceau a été calé sur le tempo du précédent, il faut
                //    l'y ramener SANS que ça s'entende. On laisse d'abord
                //    le morceau s'installer, puis on glisse à ~1 %/s.
                //  - cran de vitesse demandé à la main : réponse immédiate
                //    (~11 %/s), l'utilisateur doit entendre son geste.
                if (deckB == null) {
                    val level = PlayerCore.speedLevel.value
                    val target = 1f + 0.08f * level
                    if (level != lastSpeedLevel) {
                        lastSpeedLevel = level
                        manualRamp = true
                    }
                    if (abs(a.curRate - target) < 1e-4f) manualRamp = false
                    if (manualRamp) {
                        a.nudgeTowardNatural(0.005f, framesGlobal, target)
                    } else if (framesGlobal > fadeEndF + SETTLE_FRAMES) {
                        a.nudgeTowardNatural(NATURAL_STEP, framesGlobal, target)
                    }
                }

                // Boucle live (bouton maintenu dans le panneau Effets ; un
                // tap préalable double la taille de la boucle) — ou boucle
                // de sortie manuelle du panneau « Performance » (bascule
                // 4/8 temps) : même mécanique calée sur la mesure, le flux
                // glisse en dessous (slip) et reprend à la désactivation.
                // Si le passage s'épuise pendant la boucle, la boucle de
                // sortie automatique prend le relais : le deck reste en
                // vie, c'est le but (tenir le morceau jusqu'au mix).
                val mLoopBeats = manualLoopBeats
                if (PlayerCore.liveLoop.value)
                    a.startLiveLoop(PlayerCore.liveLoopBeats.value)
                else if (mLoopBeats > 0) a.startLiveLoop(mLoopBeats)
                else a.stopLiveLoop()

                // Saut demandé alors qu'une transition est déjà chargée
                var pj = pendingJump
                // « Suivant » pendant une transition en vol : la cible se
                // compte à partir du morceau qui ARRIVE, pas de celui qui
                // sort — deux appuis rapprochés avancent bien de deux
                // morceaux au lieu de rouvrir le même. Et s'il n'y a rien
                // après, la transition en cours va à son terme au lieu
                // d'être cassée pour rien.
                if (pj == -2 && deckB != null) {
                    val after = deckB!!.segIndex + 1
                    pj = if (after < segments.size) after else -1
                    pendingJump = pj
                }
                if (pj != -1 && deckB != null && deckB!!.segIndex != pj) {
                    deckB!!.close()
                    deckB = null
                    fadeStartF = -1L
                    echoBuf = null
                }

                // Déplacement demandé dans le morceau en cours : on rouvre
                // le même morceau à l'endroit visé et on y fait une vraie
                // transition. Ignoré si une transition est déjà en route —
                // on ne va pas couper un fondu en cours.
                val ps = pendingSeek
                if (ps >= 0f) {
                    pendingSeek = -1f
                    if (deckB == null && !opening) {
                        val from = a.startMs
                        val to = a.logicalEndMs
                        val at = from + ((to - from) * ps).toLong()
                        opening = true
                        val curRate = a.curRate
                        val segIdx = a.segIndex
                        val factor = phaseLengthFactor
                            .getOrElse(segments[segIdx].phaseIndex) { 1f }
                        thread(name = "DjSeek") {
                            val d = Deck(
                                segIdx, segments[segIdx], curRate, factor,
                                playToEnd = segIdx == segments.size - 1,
                                seekFromMs = at
                            )
                            val ok = if (d.open()) d else { d.close(); null }
                            openResult.set(
                                OpenResult(ok, SEEK_FADE_S, KIND_EQ, true, -1)
                            )
                        }
                    }
                }

                // Programmer la prochaine transition. L'OUVERTURE du deck
                // (seek + MediaCodec, parfois > 1 s) se fait sur un thread
                // dédié : la faire ici bloquait le thread audio et vidait le
                // tampon de sortie — saccade à chaque transition.
                if (deckB == null && !opening) {
                    val jumping = pendingJump != -1
                    if (jumping) failedForSeg = -1
                    val nextIdx = when {
                        pendingJump >= 0 -> pendingJump
                        else -> a.segIndex + 1
                    }
                    if (nextIdx < segments.size && failedForSeg != a.segIndex) {
                        val rate =
                            computeRate(a.track.bpm * a.curRate, segments[nextIdx].track.bpm)
                        val (fadeS, fadeKind) = fadeSpec(
                            a.track, a.curRate, segments[nextIdx].track,
                            rate, jumping, lastFadeKind
                        )
                        val fadeF = (fadeS * OUT_SR).toLong()
                        // 3 s d'avance : le deck est prêt (et pré-décodé)
                        // avant l'heure du fondu
                        if (jumping || a.remainingOut <= fadeF + 3L * OUT_SR) {
                            opening = true
                            val jt = pendingJump
                            thread(name = "DjOpen") {
                                val b = openNextValid(nextIdx, rate)
                                openResult.set(OpenResult(b, fadeS, fadeKind, jumping, jt))
                            }
                        }
                    } else if (nextIdx >= segments.size &&
                        a.remainingOut <= 0L && endFadeFrames < 0
                    ) {
                        endFadeFrames = OUT_SR / 2L // fondu de fin : 0,5 s
                    }
                    // Rien après ce passage : le set se termine. Prévenu
                    // AVANT l'arrêt (différé de la latence de sortie, comme
                    // announce) — l'enchaînement automatique doit démarrer
                    // pendant que le son tourne encore.
                    if (nextIdx >= segments.size && !setEndingSent &&
                        a.remainingOut <= SET_ENDING_LEAD_S * OUT_SR
                    ) {
                        setEndingSent = true
                        val remMs = a.remainingOut * 1000L / OUT_SR
                        ui.postDelayed({
                            if (gen == runGeneration) listener.onSetEnding(remMs)
                        }, outLatencyMs)
                    } else if (nextIdx < segments.size && setEndingSent) {
                        // Un saut est revenu en arrière : le set continue,
                        // l'annonce de fin était une fausse alerte (-1).
                        setEndingSent = false
                        ui.post {
                            if (gen == runGeneration) listener.onSetEnding(-1L)
                        }
                    }
                    // Saut demandé au-delà du dernier morceau : rien à jouer
                    // ensuite — le désarmer plutôt que le retraiter sans fin.
                    if (jumping && nextIdx >= segments.size) pendingJump = -1
                }

                // Deck suivant prêt : programmer le fondu, aligné sur la
                // grille de beats du deck A (de préférence fin de mesure).
                val ready = openResult.getAndSet(null)
                if (ready != null) {
                    opening = false
                    val b = ready.deck
                    val pj2 = pendingJump
                    if (b == null) {
                        failedForSeg = a.segIndex
                        if (ready.jumping) pendingJump = -1
                    } else if (pj2 != -1 && pj2 != ready.jumpTarget) {
                        // Un autre saut est arrivé pendant l'ouverture
                        b.close()
                    } else {
                        val fadeF = (ready.fadeS * OUT_SR).toLong()
                        val period = a.beatPeriodFrames
                        val phaseNow = a.beatPhaseAt(framesGlobal)
                        // Heure idéale du fondu : fin du passage fort moins
                        // la durée du fondu (le deck a été ouvert en avance,
                        // il ne faut PAS démarrer plus tôt pour autant) ;
                        // saut manuel = dès la prochaine mesure.
                        val dueIn = if (ready.jumping) 0L
                        else max(0L, a.remainingOut - fadeF)
                        val phaseAtDue = phaseNow + dueIn / period +
                            (OUT_SR * 0.15) / period
                        val nextBeat = ceil(phaseAtDue)
                        val nextBar = ceil(phaseAtDue / 4.0) * 4.0
                        val toBeat = ((nextBeat - phaseNow) * period).toLong()
                        val toBar = ((nextBar - phaseNow) * period).toLong()
                        var start = framesGlobal +
                            if (toBar <= a.remainingOut + OUT_SR) toBar else toBeat
                        if (start < framesGlobal) start = framesGlobal
                        // La boucle de sortie prolonge le deck A sous le
                        // fondu : la capacité inclut LOOP_MAX_OUT
                        val maxLen = a.remainingOut + LOOP_MAX_OUT -
                                (start - framesGlobal) - OUT_SR / 10
                        fadeStartF = start
                        fadeLenF = min(fadeF, max(OUT_SR / 4L, maxLen))
                        // Durée arrondie à la mesure la plus proche (4 temps
                        // du sortant) : la jonction dure un nombre entier de
                        // mesures et retombe sur une frontière musicale.
                        val barF = (4.0 * period).toLong()
                        if (barF in 1..fadeLenF * 2) {
                            fadeLenF = ((fadeLenF + barF / 2) / barF)
                                .coerceAtLeast(1L) * barF
                            fadeLenF = min(fadeLenF, max(OUT_SR / 4L, maxLen))
                        }
                        fadeKindF = ready.fadeKind
                        lastFadeKind = ready.fadeKind
                        // Echo-out : ligne à retard d'un battement
                        echoBuf = if (ready.fadeKind == KIND_CUT) {
                            echoPos = 0
                            FloatArray(
                                a.beatPeriodFrames.toInt()
                                    .coerceIn(4_410, 88_200) * 2
                            )
                        } else null

                        b.startedAtFrame = fadeStartF
                        deckB = b
                        pendingJump = -1
                    }
                }

                // Remplir un bloc
                java.util.Arrays.fill(tmpA, 0f)
                java.util.Arrays.fill(tmpB, 0f)
                val na = a.read(tmpA, 0, BLOCK_FRAMES)
                val b = deckB
                if (b != null) {
                    if (na == 0 && framesGlobal < fadeStartF) {
                        // Deck A épuisé plus tôt que prévu : démarrer B tout de suite
                        fadeStartF = framesGlobal
                        b.startedAtFrame = framesGlobal
                    }
                    if (framesGlobal + BLOCK_FRAMES > fadeStartF) {
                        val off = max(0L, fadeStartF - framesGlobal).toInt()
                        b.read(tmpB, off, BLOCK_FRAMES - off)
                    }
                }

                var blockSq = 0.0
                var bassSq = 0.0
                // Bass boost manuel : rampe très rapide (~0,3 s par cran),
                // par crans (négatif = coupe des basses, bornée pour ne pas
                // inverser la phase des graves)
                val manualTarget = (0.55f * PlayerCore.bassLevel.value)
                    .coerceAtLeast(-0.9f)
                manualBass = if (manualTarget > manualBass)
                    min(manualTarget, manualBass + 0.08f)
                else max(manualTarget, manualBass - 0.08f)
                val appliedBass =
                    if (manualBass < 0f) manualBass else max(bassGain, manualBass)
                val bd = b
                val fadeActive = bd != null && fadeLenF > 0
                // ---- Pilotage manuel du bloc (panneau « Performance ») ----
                // États @Volatile lus UNE fois par bloc, dans des locales ;
                // aucune allocation ici (chemin chaud).
                val mf = manualFade
                if (mf >= 0f) {
                    manualPos = mf
                    manualBlend = min(
                        1f, manualBlend +
                            BLOCK_FRAMES.toFloat() / MANUAL_RESUME_FRAMES
                    )
                } else if (manualBlend > 0f) {
                    // « Auto » : rampe courte vers la courbe du moteur,
                    // depuis la dernière position du fader — un retour sec
                    // sauterait.
                    manualBlend = max(
                        0f, manualBlend -
                            BLOCK_FRAMES.toFloat() / MANUAL_RESUME_FRAMES
                    )
                }
                val manualOn = manualBlend > 0f
                if (manualOn) {
                    // Transition en vol : la position du fader remplace la
                    // progression temporelle du fondu (equal-power). Hors
                    // transition : rien à déclencher, le fader n'atténue
                    // que le deck actif (plein volume jusqu'à mi-course).
                    // Cibles atteintes en pas bornés : le changement de
                    // mapping (une transition qui démarre sous le fader)
                    // ne fait pas non plus de saut.
                    val tGA = if (fadeActive) fadeGainA(manualPos)
                    else soloGain(manualPos)
                    val tGB = if (fadeActive) fadeGainB(manualPos) else 0f
                    manualGA += (tGA - manualGA).coerceIn(-0.25f, 0.25f)
                    manualGB += (tGB - manualGB).coerceIn(-0.25f, 0.25f)
                }
                // Kill de basses manuel : rampe rapide (~0,3 s) vers la
                // coupe pleine, même amplitude que le bass swap auto —
                // qu'il supplante (max) quand les deux jouent en même temps.
                killSmA = if (manualBassKillA) min(1f, killSmA + 0.15f)
                else max(0f, killSmA - 0.15f)
                killSmB = if (manualBassKillB) min(1f, killSmB + 0.15f)
                else max(0f, killSmB - 0.15f)
                val killA = BASS_SWAP_CUT * killSmA
                val killB = BASS_SWAP_CUT * killSmB
                // Nudge tempo : consommé une fois par bloc, appliqué au
                // deck qu'un DJ recale — l'entrant pendant une transition,
                // le deck actif sinon (cf. nudgeTempo()).
                val nudge = pendingManualNudge
                if (nudge != 0f) {
                    pendingManualNudge = 0f
                    (bd ?: a).syncNudge(nudge)
                }
                // Filter sweep : coupure balayée sur la durée du fondu.
                // KIND_NORMAL : passe-haut ~40 Hz -> ~6 kHz (le sortant
                // s'amincit). KIND_DARK : passe-bas ~6 kHz -> ~150 Hz (le
                // sortant s'assombrit et s'étouffe). Coefficient recalculé
                // par bloc (suffisant à 2048 frames).
                val xb = if (fadeActive && framesGlobal >= fadeStartF)
                    ((framesGlobal - fadeStartF).toFloat() / fadeLenF)
                        .coerceIn(0f, 1f)
                else 0f
                // Forme de la jonction (voir SHAPE_*)
                val shape = when (fadeKindF) {
                    KIND_DARK -> SHAPE_DARK
                    KIND_EQ -> SHAPE_EQ
                    KIND_HARMONIC -> SHAPE_HARMONIC
                    else -> SHAPE_NORMAL
                }
                val holdA = shape[4]
                val riseB = shape[5]
                var sweepAlpha = 0f
                if (fadeActive && framesGlobal >= fadeStartF &&
                    (fadeKindF == KIND_NORMAL || fadeKindF == KIND_DARK)
                ) {
                    // KIND_NORMAL : passe-haut qui grimpe VITE au début —
                    // le sortant est vidé de ses basses dès ~15 % du fondu
                    // (c'est là que l'entrant pose les siennes), puis
                    // continue de s'amincir jusqu'à ~6 kHz.
                    // KIND_DARK : le filtre se referme sur les premiers 60 %
                    // puis RESTE fermé — le sortant ronronne étouffé pendant
                    // que l'entrant arrive, sans être vraiment là.
                    val fc = if (fadeKindF == KIND_NORMAL)
                        60f * 10f.pow(2.0f * xb.pow(0.55f))
                    else 6_000f * 10f.pow(-1.6f * (xb / 0.6f).coerceAtMost(1f))
                    sweepAlpha = 1f - exp(-2f * Math.PI.toFloat() * fc / OUT_SR)
                }
                // Entrant : passe-bas 2 pôles dont la coupure monte de
                // 140 Hz (basses seules) à 16 kHz, puis fondu vers le
                // signal plein pour une ouverture totale et transparente.
                var alphaB = 0f
                var openMix = 0f
                if (fadeActive && framesGlobal >= fadeStartF &&
                    fadeKindF != KIND_CUT
                ) {
                    val o = ((xb - shape[2]) / (shape[3] - shape[2]))
                        .coerceIn(0f, 1f)
                    val fcB = OPEN_FC_LOW *
                        (OPEN_FC_HIGH / OPEN_FC_LOW).pow(o.pow(1.4f))
                    alphaB = 1f - exp(-2f * Math.PI.toFloat() * fcB / OUT_SR)
                    openMix = o.pow(3f)
                }
                var subA = 0f
                var subB = 0f
                // Effets live : cibles lissées et paramètres du bloc
                filtSm += (PlayerCore.filterLevel.value - filtSm)
                    .coerceIn(-0.08f, 0.08f)
                echoSm += (PlayerCore.echoLevel.value - echoSm)
                    .coerceIn(-0.05f, 0.05f)
                val filtOn = abs(filtSm) > 0.05f
                val fAlpha = if (filtOn) {
                    // Crans doublés : la pleine fermeture est atteinte dès
                    // le cran 1,5 ; >0 : passe-haut 60 Hz -> 2,5 kHz ;
                    // <0 : passe-bas 12 kHz -> 400 Hz (glissé en douceur)
                    val t = (abs(filtSm) / 1.5f).coerceAtMost(1f)
                    val fc = if (filtSm > 0f) 60f * 42f.pow(t)
                    else 12_000f * 0.033f.pow(t)
                    1f - exp(-2f * Math.PI.toFloat() * fc / OUT_SR)
                } else 0f
                val echoOn = echoSm > 0.05f
                val echoDelay = (a.beatPeriodFrames / 2.0).toInt()
                    .coerceIn(2_205, OUT_SR - 1) // ½ temps
                val echoWet = (0.26f * echoSm).coerceAtMost(0.6f)
                val echoFb = (0.34f * echoSm).coerceAtMost(0.85f)
                val panLvl = PlayerCore.panLevel.value
                val panStep = if (panLvl != 0)
                    (if (panLvl > 0) 1 else -1) * 2.0 * Math.PI /
                        (OUT_SR * 6.0 / (1 shl (abs(panLvl) - 1)))
                else 0.0
                val gateDepth = when (PlayerCore.gateLevel.value) {
                    1 -> 0.8f; 2 -> 0.95f; 3 -> 1f; else -> 0f
                }
                val gateStep = 1.0 / a.beatPeriodFrames
                for (i in 0 until BLOCK_FRAMES) {
                    val gf = framesGlobal + i
                    var gA = 1f
                    var gB = 0f
                    var x = 0f
                    val inFade = fadeActive && gf >= fadeStartF
                    if (inFade) {
                        x = ((gf - fadeStartF).toFloat() / fadeLenF).coerceIn(0f, 1f)
                        if (fadeKindF == KIND_CUT) {
                            // Coupe franche : sortie raide, entrée franche
                            gA = cos(x.pow(0.7f) * HALF_PI)
                            gB = sin(x.pow(1.3f) * HALF_PI)
                        } else if (fadeKindF == KIND_HARMONIC) {
                            // Long blend : les deux morceaux sont faits pour
                            // se superposer, courbes equal-power symétriques
                            // (l'entrant reste filtré jusqu'à mi-parcours,
                            // donc la superposition reste lisible)
                            gA = cos(x * HALF_PI)
                            gB = sin(x * HALF_PI)
                        } else {
                            // Le sortant garde son plein volume tant qu'il
                            // porte le morceau — il est déjà aminci par le
                            // filtre — puis s'efface franchement sur la fin.
                            gA = if (x < holdA) 1f else cos(
                                ((x - holdA) / (1f - holdA))
                                    .coerceIn(0f, 1f).pow(0.75f) * HALF_PI
                            )
                            // L'entrant monte vite : il n'est encore que
                            // basses, donc aucun risque de brouiller le
                            // sortant. Plein volume quand celui-ci s'efface.
                            gB = sin(
                                (x / riseB).coerceAtMost(1f).pow(0.85f) * HALF_PI
                            )
                        }
                    }
                    // Crossfader manuel : la position du fader prend la
                    // main sur les gains automatiques (blend 1 = tout
                    // manuel ; la rampe de saisie/reprise traverse les
                    // intermédiaires — jamais de saut). Les filtres du
                    // fondu (sweep, ouverture de l'entrant), eux, restent
                    // pilotés par le temps : seule la COURBE DE VOLUME est
                    // reprise à la main.
                    if (manualOn) {
                        gA = blendGain(gA, manualGA, manualBlend)
                        gB = blendGain(gB, manualGB, manualBlend)
                    }
                    var master = 1f
                    if (endFadeFrames >= 0) {
                        master = (endFadeFrames - i).coerceAtLeast(0L).toFloat() /
                                (OUT_SR / 2f)
                    }

                    val aL = tmpA[i * 2]
                    val aR = tmpA[i * 2 + 1]
                    val bL = tmpB[i * 2]
                    val bR = tmpB[i * 2 + 1]
                    // Basses de chaque deck (bass swap + détection de kick)
                    a.lpL += BASS_ALPHA * (aL - a.lpL)
                    a.lpR += BASS_ALPHA * (aR - a.lpR)
                    var vaL = aL
                    var vaR = aR
                    var vbL = bL
                    var vbR = bR
                    if (inFade && bd != null) {
                        bd.lpL += BASS_ALPHA * (bL - bd.lpL)
                        bd.lpR += BASS_ALPHA * (bR - bd.lpR)
                        // ENTRANT (commun aux jonctions fondues) : passe-bas
                        // 2 pôles qui s'ouvre. Au début il n'apporte QUE ses
                        // basses — le sortant garde médiums et aigus, donc
                        // rien ne se superpose ; puis il s'ouvre vers le haut
                        // au moment où le sortant s'efface. Une seule source
                        // par bande. (Coupe nette : aucun traitement,
                        // l'echo-out agit après le mixage.)
                        if (fadeKindF != KIND_CUT) {
                            bd.sweepLpL += alphaB * (bL - bd.sweepLpL)
                            bd.sweepLpR += alphaB * (bR - bd.sweepLpR)
                            bd.sweep2L += alphaB * (bd.sweepLpL - bd.sweep2L)
                            bd.sweep2R += alphaB * (bd.sweepLpR - bd.sweep2R)
                            vbL = bd.sweep2L + (bL - bd.sweep2L) * openMix
                            vbR = bd.sweep2R + (bR - bd.sweep2R) * openMix
                        }
                        // SORTANT : cède ses basses tôt et vite, pour
                        // laisser la place à celles de l'entrant.
                        val bassOut = ((x - shape[0]) / (shape[1] - shape[0]))
                            .coerceIn(0f, 1f)
                        when (fadeKindF) {
                            KIND_HARMONIC -> {
                                // Long blend : bass swap, puis mid swap —
                                // le sortant se vide bande par bande, façon
                                // EQ 3 bandes d'une table de mixage. Le
                                // kill manuel emprunte le même chemin et
                                // l'emporte sur la coupe automatique (max).
                                val cutA = max(BASS_SWAP_CUT * bassOut, killA)
                                vaL -= cutA * a.lpL
                                vaR -= cutA * a.lpR
                                a.midLpL += MID_ALPHA * (aL - a.midLpL)
                                a.midLpR += MID_ALPHA * (aR - a.midLpR)
                                val ms = ((x - 0.55f) / 0.15f).coerceIn(0f, 1f)
                                val mCutA = 0.8f * ms
                                vaL -= mCutA * (a.midLpL - a.lpL)
                                vaR -= mCutA * (a.midLpR - a.lpR)
                            }
                            KIND_NORMAL -> {
                                // Filter sweep passe-haut : le sortant
                                // s'amincit, ses basses partent dès ~15 %
                                // (le passe-haut s'en charge : pas de
                                // soustraction en plus, qui déphaserait).
                                a.sweepLpL += sweepAlpha * (aL - a.sweepLpL)
                                a.sweepLpR += sweepAlpha * (aR - a.sweepLpR)
                                vaL = aL - a.sweepLpL
                                vaR = aR - a.sweepLpR
                                // Kill manuel : le passe-haut balayé enlève
                                // déjà le bas du spectre — ne retrancher que
                                // la part de basses ENCORE présente
                                // (lp − sweepLp) ; une soustraction pleine
                                // sur-creuserait.
                                if (killA > 0f) {
                                    vaL -= killA * (a.lpL - a.sweepLpL)
                                    vaR -= killA * (a.lpR - a.sweepLpR)
                                }
                            }
                            KIND_DARK -> {
                                // Filter sweep passe-bas : le sortant
                                // s'assombrit et s'étouffe, et cède quand
                                // même ses basses tôt (sinon deux lignes de
                                // basse cohabiteraient).
                                a.sweepLpL += sweepAlpha * (aL - a.sweepLpL)
                                a.sweepLpR += sweepAlpha * (aR - a.sweepLpR)
                                // Kill manuel : même soustraction one-pole,
                                // il l'emporte sur la coupe automatique (max)
                                val cutA = max(BASS_SWAP_CUT * bassOut, killA)
                                vaL = a.sweepLpL - cutA * a.lpL
                                vaR = a.sweepLpR - cutA * a.lpR
                            }
                            KIND_EQ -> {
                                // Échange de basses classique, sans filtre :
                                // la transition « table de mixage » sobre.
                                // Kill manuel : même chemin, il l'emporte.
                                val cutA = max(BASS_SWAP_CUT * bassOut, killA)
                                vaL -= cutA * a.lpL
                                vaR -= cutA * a.lpR
                            }
                            // KIND_CUT : pas de traitement spectral ici,
                            // l'echo-out agit après le mixage.
                        }
                        // Kill manuel des basses de l'ENTRANT : même
                        // soustraction one-pole que le bass swap. Son
                        // signal garde ses basses dans tous les cas (le
                        // passe-bas d'ouverture les laisse passer), la
                        // soustraction directe est donc juste.
                        if (killB > 0f) {
                            vbL -= killB * bd.lpL
                            vbR -= killB * bd.lpR
                        }
                        // Enveloppes de kick par sous-fenêtre de 256 frames
                        subA += abs(a.lpL) + abs(a.lpR)
                        subB += abs(bd.lpL) + abs(bd.lpR)
                        if ((i + 1) % 256 == 0) {
                            a.onsets.feed(
                                subA / 256, gf, (a.beatPeriodFrames * 0.6).toLong()
                            )
                            bd.onsets.feed(
                                subB / 256, gf, (bd.beatPeriodFrames * 0.6).toLong()
                            )
                            subA = 0f
                            subB = 0f
                        }
                    }
                    // Kill manuel du deck actif hors fondu — et pendant une
                    // coupe franche (KIND_CUT), qui ne fait aucun traitement
                    // spectral : soustraction des basses one-pole, le même
                    // chemin que le bass swap. a.lp* est tenu à jour à
                    // chaque échantillon, fondu ou pas.
                    if (killA > 0f && (!inFade || fadeKindF == KIND_CUT)) {
                        vaL -= killA * a.lpL
                        vaR -= killA * a.lpR
                    }

                    var l = (vaL * gA + vbL * gB) * master
                    var r = (vaR * gA + vbR * gB) * master
                    // Echo-out (KIND_CUT) : le début du fondu alimente une
                    // ligne à retard d'un battement ; ses répétitions
                    // s'éteignent en feedback pendant que l'entrant démarre.
                    val eb = echoBuf
                    if (eb != null && inFade) {
                        val n = eb.size / 2
                        val eL = eb[echoPos * 2]
                        val eR = eb[echoPos * 2 + 1]
                        l += eL * master
                        r += eR * master
                        val feedL = if (x < 0.3f) vaL * gA else 0f
                        val feedR = if (x < 0.3f) vaR * gA else 0f
                        eb[echoPos * 2] = eL * ECHO_FEEDBACK + feedL
                        eb[echoPos * 2 + 1] = eR * ECHO_FEEDBACK + feedR
                        echoPos = (echoPos + 1) % n
                    }
                    // ---- Effets live (panneau Effets) ----
                    // Filtre maître : passe-haut (crans +) ou passe-bas (crans -)
                    if (filtOn) {
                        filtLpL += fAlpha * (l - filtLpL)
                        filtLpR += fAlpha * (r - filtLpR)
                        if (filtSm > 0f) {
                            l -= filtLpL
                            r -= filtLpR
                        } else {
                            l = filtLpL
                            r = filtLpR
                        }
                    } else {
                        filtLpL = l
                        filtLpR = r
                    }
                    // Écho calé sur le tempo (½ temps, dose par crans)
                    if (echoOn) {
                        var rp = echoDlyPos - echoDelay
                        if (rp < 0) rp += OUT_SR
                        val dL = echoDly[rp * 2]
                        val dR = echoDly[rp * 2 + 1]
                        echoDly[echoDlyPos * 2] = l + dL * echoFb
                        echoDly[echoDlyPos * 2 + 1] = r + dR * echoFb
                        echoDlyPos++
                        if (echoDlyPos >= OUT_SR) echoDlyPos = 0
                        l += dL * echoWet
                        r += dR * echoWet
                    }
                    // Gate rythmique : hachage en croches calé sur le beat
                    if (gateDepth > 0f) {
                        gatePhase += gateStep
                        val frac = (gatePhase * 2.0) % 1.0
                        val target = if (frac < 0.55) 1f else 1f - gateDepth
                        gateSm += 0.004f * (target - gateSm)
                        l *= gateSm
                        r *= gateSm
                    } else gateSm = 1f
                    // Auto-pan : le son tourne lentement (sens et vitesse par crans)
                    if (panStep != 0.0) {
                        panPhase += panStep
                        val c = kotlin.math.cos(panPhase).toFloat()
                        l *= 0.7f + 0.3f * c
                        r *= 0.7f - 0.3f * c
                    }
                    // Renfort dynamique des basses : extraction < ~120 Hz
                    // (one-pole), boost lissé appliqué sur les passages forts
                    // où les basses manquent.
                    mixLpL += BASS_ALPHA * (l - mixLpL)
                    mixLpR += BASS_ALPHA * (r - mixLpR)
                    blockSq += (l * l + r * r).toDouble()
                    bassSq += (mixLpL * mixLpL + mixLpR * mixLpR).toDouble()
                    val lb = l + appliedBass * mixLpL
                    val rb = r + appliedBass * mixLpR
                    // Limiteur doux : évite l'écrêtage brut quand normalisation,
                    // renfort de basses et EQ s'empilent
                    val peak = max(abs(lb), abs(rb))
                    if (peak * limGain > 0.98f) limGain = 0.98f / peak
                    else limGain += (1f - limGain) * 0.0008f
                    out[i * 2] = (lb * limGain).coerceIn(-1f, 1f)
                    out[i * 2 + 1] = (rb * limGain).coerceIn(-1f, 1f)
                }
                // Verrouillage actif : si les kicks des deux decks dérivent
                // pendant le fade, micro-corriger le rate de l'entrant.
                if (fadeActive && bd != null && framesGlobal >= fadeStartF) {
                    val pa = a.beatPeriodFrames
                    val pb = bd.beatPeriodFrames
                    if (abs(pa - pb) < 0.02 * pa) { // uniquement si tempos verrouillés
                        val oa = a.onsets.lastOnsetFrame
                        val ob = bd.onsets.lastOnsetFrame
                        if (oa > 0 && ob > 0) {
                            var d = (ob - oa).toDouble()
                            d -= Math.round(d / pb) * pb // repli à ±période/2
                            val delta = (d / pb * 0.002).coerceIn(-3.0e-4, 3.0e-4)
                            bd.syncNudge(delta.toFloat())
                        }
                    }
                }
                // Mise à jour du boost pour le bloc suivant. Gelé pendant les
                // fondus : le bass swap et le filter sweep creusent les basses
                // exprès, le renfort dynamique ne doit pas les recombler.
                run {
                    val blockRms = kotlin.math.sqrt(blockSq / (2 * BLOCK_FRAMES)).toFloat()
                    recentPeak = max(blockRms, recentPeak * 0.9995f)
                    val loud = recentPeak > 1e-3f && blockRms > 0.75f * recentPeak
                    val ratio = if (blockRms > 1e-4f)
                        kotlin.math.sqrt(bassSq / (2 * BLOCK_FRAMES)).toFloat() / blockRms
                    else 1f
                    val inFadeBlock = fadeActive && framesGlobal >= fadeStartF
                    val target = if (!inFadeBlock && loud && ratio < 0.30f)
                        min(0.5f, (0.30f - ratio) * 2.5f)
                    else 0f
                    bassGain += 0.02f * (target - bassGain)
                }
                audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                recorder?.write(out, BLOCK_FRAMES * 2)
                framesGlobal += BLOCK_FRAMES
                a.advancePhase(BLOCK_FRAMES)

                // Fin du crossfade : B devient le deck actif. JAMAIS tant
                // que le fader manuel est tenu (ou en rampe de reprise) :
                // fermer le deck A pendant que le fader le tient audible
                // ferait un saut — le moteur attend la remise en Auto, le
                // deck A tenant sous sa boucle de sortie automatique.
                if (b != null && framesGlobal >= fadeStartF + fadeLenF &&
                    !manualOn
                ) {
                    a.close()
                    deckA = b
                    deckB = null
                    fadeStartF = -1L
                    fadeEndF = framesGlobal
                    echoBuf = null
                    // La boucle de sortie manuelle a rempli son office :
                    // la laisser armée mettrait le morceau entrant en
                    // boucle dès ses premières mesures.
                    manualLoopBeats = 0
                    currentPhaseIndex = b.segment.phaseIndex
                    currentSegIndex = b.segIndex
                    announce(b)
                    fastForward(b)
                }

                // Début/fin de transition annoncés à l'UI (activation du
                // crossfader manuel), différés de la latence de sortie
                // comme announce() : l'état suit ce qu'on entend, pas ce
                // qu'on vient de calculer.
                val transNow = deckB != null
                if (transNow != transAnnounced) {
                    transAnnounced = transNow
                    transitionActive = transNow
                    ui.postDelayed({
                        if (running && gen == runGeneration)
                            listener.onTransitionChanged(transNow)
                    }, outLatencyMs)
                }

                // Fin de set
                if (endFadeFrames >= 0) {
                    endFadeFrames -= BLOCK_FRAMES
                    if (endFadeFrames <= 0) break
                }
                if (na == 0 && deckB == null &&
                    (a.segIndex + 1 >= segments.size ||
                        (!opening && failedForSeg == a.segIndex))
                ) break

                // Progression (toutes les ~0,7 s)
                blockCount++
                if (blockCount % 16 == 0) {
                    val total = a.totalOutFrames
                    val played = if (total > 0)
                        a.framesOut.toFloat() / total else 0f
                    // Lue sur le passage entier, pas seulement sur ce qu'il
                    // reste à jouer (cf. Deck.progressFrom)
                    val p = a.progressFrom + (1f - a.progressFrom) * played
                    ui.post { listener.onProgress(p.coerceIn(0f, 1f)) }
                }
            }
        } catch (e: Exception) {
            // Une panne du moteur DJ était avalée en silence : la lecture
            // « ne démarrait jamais » sans aucune trace. On journalise.
            djLog(android.util.Log.getStackTraceString(e))
        } finally {
            recorder?.stop()
            recorder = null
            deckA?.close()
            deckB?.close()
            openResult.getAndSet(null)?.deck?.close()
            // `running` encore vrai = la boucle est sortie d'elle-même :
            // le set est allé à son terme (un stop() manuel l'aurait mis
            // à false avant). Photographié AVANT les écritures ci-dessous.
            val natural = running && gen == runGeneration
            // Les champs partagés ne sont rendus QUE par leur génération :
            // le finally d'un thread traînard (finalisation du M4A > délai
            // du join) s'exécute APRÈS le démarrage du set suivant — écrire
            // running=false ou effacer liveAudioTrack sans cette garde
            // tuait le nouveau set en silence.
            if (gen == runGeneration) {
                liveAudioTrack = null
                running = false
                transitionActive = false
            }
            try {
                audioTrack.stop()
            } catch (_: Exception) {
            }
            audioTrack.release()
            ui.post {
                // Un nouveau set a démarré entre-temps : cette fin ne le
                // concerne pas — la livrer mettait le set suivant en pause.
                if (gen == runGeneration) listener.onStopped(natural)
            }
        }
    }

    /** Journal des pannes du moteur DJ : dj_log.txt (interne + externe,
     *  comme crash_log.txt — visible dans Android/data/.../files). */
    private fun djLog(message: String) {
        try {
            for (dir in listOfNotNull(
                context.filesDir, context.getExternalFilesDir(null)
            )) {
                val f = java.io.File(dir, "dj_log.txt")
                if (f.length() > 64_000) f.delete()
                f.appendText("${java.util.Date()}: $message\n")
            }
        } catch (_: Exception) {
        }
    }

    private fun announce(deck: Deck) {
        val t = deck.track
        val p = deck.segment.phaseIndex
        val gen = runGeneration
        // Retardé de la latence du tampon de sortie : le morceau annoncé
        // est celui qu'on entend, pas celui qu'on vient de calculer. À la
        // livraison, le set a pu être arrêté (et un autre mode lancé) :
        // l'annonce d'un set mort écrasait le morceau courant du nouveau.
        ui.postDelayed({
            if (running && gen == runGeneration) listener.onTrackChanged(t, p)
        }, outLatencyMs)
    }

    // computeRate et fadeSpec : voir le companion object (fonctions pures,
    // internal, testées en JVM).
}
