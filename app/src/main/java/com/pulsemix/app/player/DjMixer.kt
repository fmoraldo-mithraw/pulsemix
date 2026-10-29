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
import com.pulsemix.app.mix.Passages
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

    /**
     * Un passage du set : le morceau, sa phase, et le PASSAGE choisi pour
     * lui (Passages.choose, selon l'humeur de la phase et la rotation) —
     * null = passage fort historique (ancre sur le drop, `bestStartMs`).
     */
    private data class Segment(
        val track: Track,
        val phaseIndex: Int,
        val passage: Passages.Passage? = null
    )

    /** Ancre d'entrée d'un passage (là où son deck place son drop) : début
     *  du passage par rôle, sinon anchorFor — le même calcul que Deck.init. */
    private fun entryAnchor(seg: Segment, sections: List<StructureDetector.Section>): Long {
        val nx = seg.track
        val p = seg.passage
        return if (p != null) passageAnchor(p, nx.durationMs)
        else anchorFor(
            nx.bestStartMs.coerceIn(0L, max(0L, nx.durationMs - 15_000L)),
            nx.segmentMs, nx.firstBeatMs, nx.bpm, nx.durationMs, sections
        )
    }

    /** Construit le passage d'un morceau pour une phase d'humeur [mood] :
     *  rotation d'après le dernier rôle joué (PlayHistory). */
    private fun segmentFor(track: Track, phaseIndex: Int, mood: Passages.Mood): Segment {
        val last = try {
            com.pulsemix.app.data.PlayHistory.lastPassage(track.uri)?.let { r ->
                Passages.Role.entries.firstOrNull { it.name == r }
            }
        } catch (_: Exception) {
            null
        }
        val p = Passages.choose(track, mood, last)
        return Segment(track, phaseIndex, if (p.role == Passages.Role.FORT) null else p)
    }

    companion object {
        const val OUT_SR = 44100
        const val BLOCK_FRAMES = 2048

        /** Copie glissante des derniers blocs ÉCRITS dans la sortie (~4,5 s,
         *  multiple de BLOCK_FRAMES, plus que le plafond de 3 s du tampon) :
         *  quand la sortie doit être reconstruite en cours de set, ce qui
         *  était dans son tampon sans avoir été entendu est réécrit dans la
         *  nouvelle — ni saut, ni blanc. */
        const val RING_FRAMES = BLOCK_FRAMES * 96
        // Fondus longs (plusieurs mesures) pour « sentir arriver » le
        // morceau entrant : il pose d'abord ses basses seules (filtré),
        // et ne s'ouvre en pleine bande qu'au moment où le sortant
        // s'efface — jamais deux morceaux entiers en même temps.
        const val FADE_NORMAL_S = 14.0
        const val FADE_JUMP_S = 5.0
        // Fenêtre de calage du pitch fader. ±4 % au lieu de ±8 % : au-delà,
        // l'étirement s'entend comme un désaccordage sur tout ce qui est
        // chanté ou acoustique, pour un gain de calage nul (cf. computeRate).
        const val MIN_LOCK_RATE = 0.96f
        const val MAX_LOCK_RATE = 1.04f
        // Plafond des jonctions, relatif au passage sortant puis absolu
        // (cf. clampFadeS) : ~4 mesures à 128 BPM.
        const val MAX_FADE_RATIO = 0.15
        const val MAX_FADE_S = 8.0
        const val MIN_FADE_S = 2.0
        // Ouverture du deck entrant : combien de temps AVANT le fondu, et
        // quelle réserve décodée exiger avant de le confier au mixeur.
        // 3 s d'avance et un seul chunk décodé ne suffisaient pas — le
        // deck entrant abordait le fondu sans réserve.
        const val PREOPEN_LEAD_S = 8L
        const val PREBUFFER_FRAMES = 88_200 // ~2 s à 44,1 kHz
        const val PREBUFFER_DEADLINE_MS = 3_000L
        // Même réserve pour un deck ouvert sur un GESTE (seek, suivant),
        // mais l'attente est courte : l'utilisateur a appuyé, il attend.
        const val GESTURE_PREBUFFER_DEADLINE_MS = 750L
        // Micro-fondu de pause/reprise, sur le volume de l'AudioTrack
        // (appliqué à la sortie, pas au tampon) : couper net fait un clic —
        // ExoPlayer a le sien (120/150 ms), le moteur DJ n'en avait pas.
        const val PAUSE_FADE_MS = 80L
        const val RESUME_FADE_MS = 120L
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

        /** Durée d'une coupe franche (double appui) : 80 ms, assez pour ne
         *  pas claquer, assez court pour être une coupe. */
        const val CUT_FRAMES = 3_528L

        /** Facteur de vitesse du cran manuel : ±8 % par cran. Fonction PURE. */
        internal fun manualFactor(level: Int): Float = 1f + 0.08f * level
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
        const val KIND_DROP = 5     // drop swap : la montée du sortant, le drop de l'entrant

        const val ECHO_FEEDBACK = 0.55f
        // Jonctions en MESURES (cf. fadeBars) : jamais plus de cette part
        // du passage sortant, comptée en mesures.
        const val MAX_FADE_RATIO_BARS = 0.2
        // Enchaînement LONG des transitions pro : durée nominale doublée,
        // plafond relevé d'autant (le passage a été allongé de
        // [PRO_EXTRA_BARS] à l'ouverture du deck).
        const val MAX_FADE_RATIO_BARS_LONG = 0.35
        const val PRO_EXTRA_BARS = 8
        // Seuil d'énergie de l'entrant pour oser un drop swap : sur un
        // morceau mou, la coupe tomberait dans le vide.
        const val DROP_MIN_ENERGY = 0.12f

        /**
         * Drop swap ([KIND_DROP]) — le geste des sets électro/festival : la
         * MONTÉE du sortant porte la tension, et sur le « 1 » où SON drop
         * devait tomber, c'est le drop de l'ENTRANT qui tombe à sa place.
         * Une coupe, pas un fondu : un drop swap raté est pire que pas de
         * transition, tout tient donc au calage —
         *  - le sortant finit EXACTEMENT sur la fin de sa section BUILD
         *    (= début de son drop, frontière déjà calée sur le saut de
         *    basses à l'analyse puis sur la grille de phrases) ;
         *  - l'instant du swap est calculé sur la position SOURCE du
         *    sortant (pas sur un compteur de sortie qui dérive avec les
         *    glissements de tempo) ;
         *  - l'entrant démarre pile sur le début de son DROP (décodage
         *    calé à l'échantillon, sans pré-roll), tempos calés.
         * Les [DROP_PRE_BARS] dernières mesures de la montée, le sortant
         * passe dans un passe-haut qui grimpe ([DROP_HP_FROM] →
         * [DROP_HP_TO] Hz) : la tension monte et le grave est dégagé pour
         * les basses de l'entrant. Sur le « 1 » : sortant coupé en
         * [DROP_CUT_A_FRAMES] (sa queue d'écho d'un temps, discrète,
         * [DROP_ECHO_WET]), entrant plein en [DROP_IN_B_FRAMES] — juste
         * de quoi ne pas cliquer sans émousser l'attaque. La jonction
         * dure encore [DROP_TAIL_BARS] après le swap, le temps que l'écho
         * s'éteigne.
         */
        const val DROP_PRE_BARS = 2
        const val DROP_TAIL_BARS = 2
        const val DROP_HP_FROM = 30f
        const val DROP_HP_TO = 350f
        const val DROP_CUT_A_FRAMES = 441 // 10 ms
        const val DROP_IN_B_FRAMES = 132  // 3 ms
        const val DROP_ECHO_WET = 0.35f
        // Le sortant doit avoir joué au moins ça avant de partir sur sa
        // montée : en deçà, mieux vaut un enchaînement ordinaire.
        const val DROP_EXIT_MIN_PLAY_MS = 45_000L

        /** Gain du SORTANT d'un drop swap, [f] frames après le « 1 » (négatif
         *  = avant) : plein, puis coupé en cosinus sur [DROP_CUT_A_FRAMES].
         *  Fonction PURE (testée en JVM). */
        internal fun dropCutGainA(f: Long): Float = when {
            f <= 0L -> 1f
            f >= DROP_CUT_A_FRAMES -> 0f
            else -> cos(f.toFloat() / DROP_CUT_A_FRAMES * HALF_PI)
        }

        /** Gain de l'ENTRANT d'un drop swap : muet avant le « 1 », plein
         *  [DROP_IN_B_FRAMES] après. Fonction PURE (testée en JVM). */
        internal fun dropCutGainB(f: Long): Float = when {
            f < 0L -> 0f
            f >= DROP_IN_B_FRAMES -> 1f
            else -> f.toFloat() / DROP_IN_B_FRAMES
        }

        /** Coupure du passe-haut du sortant pendant la fin de sa montée,
         *  [t] = progression 0..1 vers le « 1 » : exponentielle, lente
         *  d'abord (la montée garde son corps), raide à la fin. PURE. */
        internal fun dropHpFc(t: Float): Float =
            DROP_HP_FROM * (DROP_HP_TO / DROP_HP_FROM).pow(t.coerceIn(0f, 1f).pow(2f))

        /**
         * L'entrant peut-il recevoir un drop swap ? Il faut un VRAI drop
         * sur son ancre (début d'une section DROP — le passage fort, pas
         * un passage par rôle), et un morceau assez énergique : sur un
         * morceau mou, la coupe tomberait dans le vide. Fonction PURE.
         */
        internal fun dropEntryOk(
            next: Track,
            fortPassage: Boolean,
            sections: List<StructureDetector.Section>,
            anchorMs: Long
        ): Boolean = fortPassage && next.energyMean >= DROP_MIN_ENERGY &&
            sections.any {
                it.kind == StructureDetector.SectionKind.DROP &&
                    abs(it.startMs - anchorMs) <= 50L
            }

        /**
         * Sortie « montée » du sortant pour un drop swap : la fin d'une
         * section BUILD suivie d'un DROP (là où SON drop tomberait), jouée
         * au moins [DROP_EXIT_MIN_PLAY_MS] après l'ancre, pas plus de deux
         * phrases au-delà de la fin calculée [endMs], et laissant 15 s de
         * morceau. La plus proche de [endMs]. @return -1 sans candidat.
         * Fonction PURE (testée en JVM).
         */
        internal fun dropExitMs(
            endMs: Long, anchorMs: Long, bpm: Float, durationMs: Long,
            sections: List<StructureDetector.Section>
        ): Long {
            if (bpm <= 0f || sections.size < 2) return -1L
            val phraseMs = 16.0 * 60_000.0 / bpm
            var best = -1L
            var bestD = Long.MAX_VALUE
            for (k in 0 until sections.size - 1) {
                val s = sections[k]
                if (s.kind != StructureDetector.SectionKind.BUILD ||
                    sections[k + 1].kind != StructureDetector.SectionKind.DROP
                ) continue
                val b = s.endMs
                if (b < anchorMs + DROP_EXIT_MIN_PLAY_MS || b > durationMs - 15_000L) continue
                if (b > endMs + 2.0 * phraseMs) continue
                val d = abs(b - endMs)
                if (d < bestD) {
                    bestD = d
                    best = b
                }
            }
            return best
        }

        /** Les tempos naturels sont-ils calables (même règle que
         *  [splitRates]) ? Fonction PURE. */
        internal fun tempoLockable(bpmA: Float, bpmB: Float): Boolean {
            if (bpmA <= 0f || bpmB <= 0f) return false
            val r = minOf(
                abs(kotlin.math.ln(bpmA / bpmB)),
                abs(kotlin.math.ln(bpmA / (bpmB * 2f))),
                abs(kotlin.math.ln(bpmA / (bpmB * 0.5f)))
            )
            // Moitié du chemin chacun, ±4 % par deck
            return kotlin.math.exp(r / 2.0) <= MAX_LOCK_RATE + 1e-6
        }


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
            // Rien de calable dans la fenêtre : tempo NATUREL. Étirer un
            // morceau de 8 % sans que les temps se calent pour autant ne
            // servait à rien — ça ne faisait que le désaccorder (0,92 =
            // presque un demi-ton), et le journal en était plein sur des
            // morceaux chantés. fadeSpec voit alors le non-calage et
            // choisit une coupe courte, ce qu'un DJ ferait aussi.
            var best = 1f
            var bestDist = Float.MAX_VALUE
            for (c in candidates) {
                if (c in MIN_LOCK_RATE..MAX_LOCK_RATE) {
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
         * Plafonne la durée d'une jonction à la taille du passage joué.
         *
         * Les durées de [fadeSpec] (14 s, 18 s en blend harmonique) sont
         * calibrées pour des morceaux entiers de 3 à 5 minutes. Sur des
         * passages d'une minute — ce que joue un set PulseMix — elles
         * faisaient jouer DEUX morceaux ensemble pendant un quart à un
         * tiers du set, chant sur chant : le journal montrait des fondus
         * de 17 à 19 s entre des passages de 46 à 65 s. D'où le plafond :
         * jamais plus de [MAX_FADE_RATIO] du passage sortant, jamais plus
         * de [MAX_FADE_S]. Un fondu déjà plus court n'est pas rallongé.
         *
         * Fonction PURE (companion, internal, testée en JVM).
         *
         * @param segmentFrames durée TOTALE du passage sortant, en frames
         *   de sortie (Deck.totalOutFrames).
         */
        /** Durée d'une mesure (4 temps) en secondes. */
        internal fun barSeconds(bpm: Float): Double = 4.0 * 60.0 / bpm

        /**
         * Durée d'une jonction en MESURES, par technique : un DJ compte 2, 4
         * ou 8 mesures, pas des secondes — 8 s à 90 BPM font trois mesures,
         * une jonction qui ne tombe sur aucune structure musicale. Coupe :
         * 2 mesures ; blend harmonique : 4, 6 sur un long passage (≥ 48
         * mesures) ; le reste : 4. Bornée à [MAX_FADE_RATIO_BARS] du passage
         * sortant, jamais moins d'une mesure. Drop swap : ses
         * [DROP_PRE_BARS] + [DROP_TAIL_BARS] (sa durée réelle est calée sur
         * la montée du sortant au moment de le programmer).
         * [long] (transitions pro, enchaînement long) : durée nominale
         * DOUBLÉE — 8 mesures, 8 à 12 en blend harmonique, 4 pour une
         * coupe — plafond relevé à [MAX_FADE_RATIO_BARS_LONG]. C'est le
         * blend de club : une phrase entière où l'entrant s'installe avant
         * l'échange des basses. Fonction PURE (testée en JVM).
         */
        internal fun fadeBars(kind: Int, segmentBars: Double, long: Boolean = false): Int {
            if (kind == KIND_DROP) return DROP_PRE_BARS + DROP_TAIL_BARS
            val nominal = when (kind) {
                KIND_CUT -> 2
                KIND_HARMONIC -> if (segmentBars >= 48.0) 6 else 4
                else -> 4
            } * (if (long) 2 else 1)
            val ratio = if (long) MAX_FADE_RATIO_BARS_LONG else MAX_FADE_RATIO_BARS
            val cap = floor(segmentBars * ratio).toInt().coerceAtLeast(1)
            return min(nominal, cap)
        }

        /**
         * Calage de tempo PARTAGÉ entre les deux decks : plutôt que d'étirer
         * l'entrant de tout l'écart, chacun fait la moitié du chemin (en
         * log : tempo cible = moyenne géométrique des tempos naturels, avec
         * double/moitié de tempo admis pour l'entrant). Pour la même
         * altération audible de ±4 % par deck, la plage calable double. Hors
         * plage : (1, 1) — chacun à son tempo naturel, fadeSpec coupera.
         * @return (rate du sortant, rate de l'entrant), relatifs aux tempos
         *   NATURELS des deux morceaux. Fonction PURE (testée en JVM).
         */
        internal fun splitRates(bpmA: Float, bpmB: Float): Pair<Float, Float> {
            if (bpmA <= 0f || bpmB <= 0f) return 1f to 1f
            var bestB = bpmB
            var bestD = Float.MAX_VALUE
            for (k in floatArrayOf(1f, 2f, 0.5f)) {
                val b = bpmB * k
                val d = abs(kotlin.math.ln(bpmA / b))
                if (d < bestD) {
                    bestD = d
                    bestB = b
                }
            }
            val t = kotlin.math.sqrt(bpmA * bestB)
            val rA = t / bpmA
            val rB = t / bestB
            return if (rA in MIN_LOCK_RATE..MAX_LOCK_RATE &&
                rB in MIN_LOCK_RATE..MAX_LOCK_RATE
            ) rA to rB else 1f to 1f
        }

        /**
         * Ancre du passage fort : le DÉBUT de la section DROP la plus proche
         * du meilleur passage (à ± une phrase) quand la structure est
         * connue — le « 1 » du drop, recalé sur le saut de basses à
         * l'analyse — sinon le premier beat détecté dans le passage, sinon
         * son début. Un deck qui démarre sur un vrai début de section sonne
         * juste ; « le premier beat de la meilleure minute » pouvait tomber
         * au milieu d'un couplet. Fonction PURE (testée en JVM).
         */
        internal fun anchorFor(
            bestStartMs: Long,
            segmentMs: Long,
            firstBeatMs: Long,
            bpm: Float,
            durationMs: Long,
            sections: List<StructureDetector.Section>
        ): Long {
            if (bpm > 0f && sections.isNotEmpty()) {
                val phraseMs = 16.0 * 60_000.0 / bpm
                val drop = sections
                    .filter {
                        it.kind == StructureDetector.SectionKind.DROP &&
                            abs(it.startMs - bestStartMs) <= phraseMs &&
                            it.startMs <= durationMs - 15_000L
                    }
                    .minByOrNull { abs(it.startMs - bestStartMs) }
                if (drop != null) return drop.startMs
            }
            return if (firstBeatMs in bestStartMs..(bestStartMs + segmentMs)) firstBeatMs
            else bestStartMs
        }

        /** Ancre d'un passage par rôle : son début, borné pour laisser 15 s
         *  de morceau (même borne que le passage fort). */
        internal fun passageAnchor(passage: Passages.Passage, durationMs: Long): Long =
            passage.startMs.coerceIn(0L, max(0L, durationMs - 15_000L))

        /** Section qui contient l'instant [ms] (null sans structure). */
        internal fun sectionAt(
            sections: List<StructureDetector.Section>,
            ms: Long
        ): StructureDetector.SectionKind? =
            sections.firstOrNull { ms >= it.startMs && ms < it.endMs }?.kind

        internal fun clampFadeS(fadeS: Double, segmentFrames: Long): Double {
            if (segmentFrames <= 0L) return fadeS
            val segmentS = segmentFrames.toDouble() / OUT_SR
            val cap = min(MAX_FADE_S, segmentS * MAX_FADE_RATIO)
                .coerceAtLeast(MIN_FADE_S)
            return min(fadeS, cap)
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
            lastKind: Int,
            // Section d'où l'on SORT (fin du passage du sortant) et section
            // où l'on ENTRE (ancre de l'entrant), quand la structure est
            // connue : ce qui compte pour choisir le geste est local à la
            // jonction, pas le caractère moyen des deux morceaux.
            exitKind: StructureDetector.SectionKind? = null,
            entryKind: StructureDetector.SectionKind? = null
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
            // Structure LOCALE à la jonction, quand elle est connue :
            //  - on sort d'un DROP (temps fort) : geste franc — coupe ou
            //    échange de basses, jamais l'étouffement lent d'un sweep
            //    grave, qui laisserait le climax mourir sous l'entrant ;
            //  - on sort d'un BREAK / d'une OUTRO (le morceau s'est déjà
            //    calmé) : blend long, jamais de coupe — il n'y a plus
            //    d'attaque sur laquelle une coupe sonnerait voulue ;
            //  - on entre directement dans un DROP (pas de montée avant) :
            //    l'entrant doit arriver franc, pas filtré sur des mesures.
            val local: List<Pair<Double, Int>> = when {
                exitKind == StructureDetector.SectionKind.DROP -> {
                    val p = pool.filter { it.second != KIND_DARK }
                    if (p.none { it.second == KIND_CUT || it.second == KIND_EQ })
                        p + (FADE_CUT_S to KIND_CUT) else p
                }
                exitKind == StructureDetector.SectionKind.BREAK ||
                    exitKind == StructureDetector.SectionKind.OUTRO ->
                    pool.filter { it.second != KIND_CUT }.ifEmpty { pool }
                entryKind == StructureDetector.SectionKind.DROP ->
                    pool.filter { it.second != KIND_DARK }.ifEmpty { pool }
                else -> pool
            }
            // Tirage stable par paire de morceaux, sans répéter la technique
            // de la transition précédente
            var idx = ((current.uri.hashCode() * 31 + next.uri.hashCode())
                ushr 1) % local.size
            if (local[idx].second == lastKind && local.size > 1) idx = (idx + 1) % local.size
            return local[idx]
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

        /**
         * Pré-roll du deck ENTRANT d'une transition automatique : au lieu
         * de démarrer pile sur son ancre (premier beat du passage fort —
         * son drop), le deck démarre ~la durée du fondu EN AVANCE,
         * arrondie à un nombre entier de mesures. Sous le fondu on entend
         * alors sa montée, et son drop tombe à la FIN du fondu, quand le
         * sortant s'efface — comme un DJ qui lance l'entrant par sa
         * montée, pas par son climax filtré.
         *
         * @return la durée de pré-roll en ms ; 0 = comportement
         * historique (départ sur l'ancre) : sans BPM, sans fondu, ancre
         * au début du fichier, ou structure indiquant qu'un pré-roll
         * rejouerait n'importe quoi.
         *
         * Avec structure : si une section BUILD se termine à ± une mesure
         * de l'ancre, le pré-roll est borné au début de cette montée
         * (arrondi à la mesure : on entre au début de la montée, pas
         * avant) ; si l'ancre est en plein milieu d'un DROP sans montée
         * adjacente (structure dégénérée), pré-roll nul — entrer en plein
         * drop filtré vaut mieux que rejouer la fin du morceau précédent
         * du fichier. Sans structure (liste vide) : le candidat purement
         * rythmique s'applique — l'entrant arrive ~une phrase avant son
         * passage fort, déjà mieux que rien.
         *
         * Fonction PURE (companion, internal, testée en JVM) comme
         * snapEndToStructure — appelée à l'ouverture d'un deck, jamais
         * dans la boucle audio par bloc.
         */
        internal fun preRollMs(
            anchorMs: Long, fadeMs: Long, bpm: Float,
            sections: List<StructureDetector.Section>
        ): Long {
            if (bpm <= 0f || fadeMs <= 0L || anchorMs <= 0L) return 0L
            val barMs = 4.0 * 60_000.0 / bpm
            // Candidat rythmique : nombre entier de mesures le plus
            // proche de la durée du fondu (≥ 1 mesure dès une
            // demi-mesure de fondu, 0 en dessous).
            var bars = Math.round(fadeMs / barMs)
            // Jamais avant le début du fichier : au plus le plus grand
            // multiple de mesures qui tient avant l'ancre.
            val maxBars = floor(anchorMs / barMs).toLong()
            if (bars > maxBars) bars = maxBars
            if (bars <= 0L) return 0L
            if (sections.isNotEmpty()) {
                var buildStartMs = -1L
                for (s in sections) {
                    if (s.kind == StructureDetector.SectionKind.BUILD &&
                        abs(s.endMs - anchorMs) <= barMs
                    ) buildStartMs = s.startMs
                }
                if (buildStartMs >= 0L) {
                    // Montée adjacente à l'ancre : le pré-roll entre au
                    // début de la montée, pas avant (arrondi à la mesure
                    // par défaut — plancher, pour ne pas la déborder).
                    val cap = floor((anchorMs - buildStartMs) / barMs).toLong()
                    if (bars > cap) bars = cap
                    if (bars <= 0L) return 0L
                } else {
                    // Structure connue mais PAS de montée avant l'ancre :
                    // un pré-roll démarrerait N mesures plus tôt, n'importe
                    // où dans un couplet. Le pré-roll n'entre que dans une
                    // montée ; sinon le deck part sur son ancre.
                    return 0L
                }
            }
            return Math.round(bars * barMs)
        }

        /**
         * Prochain « 1 » de PHRASE (16 temps) sur la grille de beats du
         * deck sortant : l'échelon au-dessus de la mesure pour lancer une
         * transition comme un DJ d'electro. [offsetBeats] recale la
         * grille quand le deck a démarré avec un pré-roll (nombre entier
         * de MESURES, pas forcément de phrases) : ses phrases restent
         * ancrées sur son ancre, pas sur son départ.
         * Fonction PURE (companion, internal, testée en JVM).
         */
        internal fun nextPhraseBeat(phase: Double, offsetBeats: Double): Double =
            ceil((phase - offsetBeats) / 16.0) * 16.0 + offsetBeats

        /** Nom lisible d'un KIND_*, pour le journal de diagnostic. */
        internal fun kindName(kind: Int): String = when (kind) {
            KIND_CUT -> "coupe+écho"
            KIND_HARMONIC -> "blend harmonique"
            KIND_DARK -> "sweep grave"
            KIND_EQ -> "échange de basses"
            KIND_DROP -> "drop swap"
            else -> "sweep aigu"
        }

        /**
         * Phase (en temps) du swap NET de basses : la dernière frontière
         * de MESURE de la grille du sortant avant la fin du fondu — avec
         * le pré-roll, c'est l'instant où le drop de l'entrant arrive.
         * « fin − une mesure », arrondie à la mesure : toujours au moins
         * 2 temps avant la fin du fondu.
         * Fonction PURE (companion, internal, testée en JVM).
         */
        internal fun bassSwapPhase(fadeEndPhase: Double): Double =
            Math.round((fadeEndPhase - 4.0) / 4.0) * 4.0

        /** Choix d'une jonction en mode pro : durée nominale (s), technique
         *  ([KIND_*]), et enchaînement LONG (durée en mesures doublée). */
        internal data class ProChoice(val fadeS: Double, val kind: Int, val long: Boolean)

        /** Part des bas-médiums au-delà de laquelle un morceau est tenu
         *  pour chanté (lowMidRatio). */
        const val VOCAL_LOWMID = 0.35f

        /**
         * Sélection « pro » des jonctions (toggle Transitions pro) : trois
         * styles, comme un DJ qui choisit son geste à chaque enchaînement.
         *
         *  - DROP SWAP ([KIND_DROP]) quand le sortant a été calé pour
         *    finir sur sa montée ([sortantDropExit], cf. Deck.init) et que
         *    l'entrant a un vrai drop sur son ancre ([dropEntryOk]) :
         *    la montée de l'un, le drop de l'autre ;
         *  - ENCHAÎNEMENT LONG : la palette de [fadeSpec] (sweep, échange
         *    de basses, blend harmonique), durée DOUBLÉE (8 mesures, une
         *    phrase de club) — quand les deux morceaux peuvent cohabiter
         *    aussi longtemps : tonalités compatibles, ou pas deux voix
         *    l'une sur l'autre, et pas de climax qu'on laisserait mourir
         *    (sortie d'un DROP) ;
         *  - MEDLEY : la palette de [fadeSpec] à sa durée courte (2-4
         *    mesures) — tout le reste : deux morceaux chantés en
         *    tonalités éloignées, sortie d'un temps fort, coupe.
         *
         * Saut manuel : fondu court neutre ; tempo non calable : coupe
         * courte (un drop swap ou un long blend sans beatlock battrait).
         * Fonction PURE (companion, internal, testée en JVM).
         * @param nextSections structure décodée de l'entrant (vide = pas
         *   de structure) — décodée par l'appelant, UNE fois.
         * @param anchorMs ancre de l'entrant : là où son drop tombe.
         */
        internal fun fadeSpecPro(
            current: Track,
            curRate: Float,
            next: Track,
            rate: Float,
            jumping: Boolean,
            lastKind: Int,
            sortantDropExit: Boolean,
            fortPassage: Boolean,
            nextSections: List<StructureDetector.Section>,
            anchorMs: Long,
            exitKind: StructureDetector.SectionKind? = null
        ): ProChoice {
            if (jumping) return ProChoice(FADE_JUMP_S, KIND_EQ, false)
            val base = fadeSpec(
                current, curRate, next, rate, jumping, lastKind,
                exitKind, sectionAt(nextSections, anchorMs)
            )
            val effA = current.bpm * curRate
            val effB = next.bpm * rate
            if (effA <= 0f || effB <= 0f) return ProChoice(base.first, base.second, false)
            val ratio = effA / effB
            val lockErr = minOf(
                abs(ratio - 1f),
                abs(ratio - 2f) / 2f,
                abs(ratio - 0.5f) * 2f
            )
            if (lockErr > 0.005f) return ProChoice(FADE_CUT_S, KIND_CUT, false)
            if (sortantDropExit && dropEntryOk(next, fortPassage, nextSections, anchorMs)) {
                return ProChoice(FADE_CUT_S, KIND_DROP, false)
            }
            val harmonic = MixEngine.camelotScore(current.camelot, next.camelot)
            val twoVoices = current.lowMidRatio > VOCAL_LOWMID &&
                next.lowMidRatio > VOCAL_LOWMID
            val long = base.second != KIND_CUT &&
                exitKind != StructureDetector.SectionKind.DROP &&
                (harmonic >= 0.8f || !twoVoices)
            return ProChoice(base.first, base.second, long)
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
    // Reprise du premier morceau à cette position (consommée à l'ouverture
    // du premier deck, puis remise à null)
    @Volatile private var pendingFirstSeekMs: Long? = null
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

    // Lus par le fil audio, remplacés d'un bloc par appendPlan (listes
    // immuables, la référence seule change) : @Volatile suffit.
    @Volatile private var segments: List<Segment> = emptyList()
    private var plan: MixEngine.MixPlan? = null
    private var mixThread: Thread? = null
    @Volatile private var phaseLengthFactor = FloatArray(0)

    /** Facteur de longueur par phase (0,85 à 1,15 : phases énergiques
     *  plus longues), même règle au start() et à l'ajout d'un plan. */
    private fun lengthFactors(plan: MixEngine.MixPlan): FloatArray {
        val phaseEnergy = plan.phases.map { ph ->
            val analyzed = ph.tracks.filter { it.analyzed }
            if (analyzed.isEmpty()) 0f
            else analyzed.map { it.energyPeak }.sum() / analyzed.size
        }
        val eMin = phaseEnergy.minOrNull() ?: 0f
        val eMax = phaseEnergy.maxOrNull() ?: 0f
        return FloatArray(plan.phases.size) { i ->
            val t = if (eMax > eMin) (phaseEnergy[i] - eMin) / (eMax - eMin) else 0.5f
            0.85f + 0.30f * t
        }
    }

    /**
     * Greffe [plan] à la suite du set EN COURS (enchaînement en fondu d'un
     * set au suivant) : ses passages jouables rejoignent la liste, avec
     * des indices de phase qui continuent ceux du set. Le fil audio voit
     * la liste rallongée au bloc suivant et enchaîne le dernier deck sur
     * le premier nouveau par une jonction ordinaire ; l'alerte de fin de
     * set en cours est levée par le moteur (-1). @return passages ajoutés.
     */
    fun appendPlan(plan: MixEngine.MixPlan): Int {
        if (!running) return 0
        val base = phaseLengthFactor.size
        val fresh = plan.phases.flatMapIndexed { pi, ph ->
            ph.tracks.filter { it.analyzed && it.bpm > 0f }
                .map { segmentFor(it, base + pi, ph.mood) }
        }
        if (fresh.isEmpty()) return 0
        phaseLengthFactor = phaseLengthFactor + lengthFactors(plan)
        segments = segments + fresh
        this.plan = plan
        return fresh.size
    }
    // Dernière technique utilisée : évite deux fois de suite la même quand
    // plusieurs conviennent (variété façon DJ)
    private var lastFadeKind = -1
    // Drop-swaps enchaînés : deux d'affilée sont permis (le geste standard
    // en festival), le troisième est forcé en blend (cf. fadeSpecPro).
    private var dropStreak = 0
    /**
     * Mode « Transitions pro » (medley, long, drop swap) : réglage PERSISTANT posé par
     * PlayerCore, lu au moment de programmer chaque transition — jamais
     * dans la boucle par bloc. Applicable à chaud (la prochaine transition
     * programmée le voit), et PAS remis à zéro au start() : c'est un
     * réglage, pas un état de set.
     */
    @Volatile private var proMode = false

    fun setProMode(on: Boolean) {
        proMode = on
    }

    /**
     * Volume maître du set, appliqué sur l'AudioTrack (donc à la sortie,
     * sans la latence du tampon). Porte le fondu de la minuterie de
     * sommeil, qu'`applyVolume` n'appliquait qu'aux modes ExoPlayer — en
     * DJ la pause tombait sans les 30 s de descente. Posé par PlayerCore,
     * lu une fois par bloc par le fil audio.
     */
    @Volatile private var masterVolume = 1f

    fun setMasterVolume(v: Float) {
        masterVolume = v.coerceIn(0f, 1f)
    }

    /**
     * Rampe du volume de l'AudioTrack, par pas de 10 ms. Appelée par le
     * fil audio uniquement autour de la pause/reprise (il s'apprête à
     * dormir ou vient de se réveiller : le temps de la rampe, le tampon de
     * ~2 s continue de jouer, la descente s'entend).
     */
    private fun rampTrackVolume(track: AudioTrack, from: Float, to: Float, ms: Long) {
        val steps = max(1L, ms / 10L).toInt()
        try {
            for (i in 1..steps) {
                track.setVolume(from + (to - from) * i / steps)
                Thread.sleep(10L)
            }
        } catch (_: Exception) {
        }
    }
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
    /** @param firstSeekMs position de reprise dans le premier morceau
     *  (null = départ normal du fichier) : « DJ sur ce morceau » reprend
     *  la lecture là où elle en était. */
    fun start(
        plan: MixEngine.MixPlan,
        startPhase: Int = 0,
        rehearsalMode: Boolean = false,
        firstSeekMs: Long? = null
    ) {
        stop()
        rehearsal = rehearsalMode
        pendingFirstSeekMs = firstSeekMs
        this.plan = plan
        // Le passage de chaque morceau est choisi ICI, une fois pour le set
        // (humeur de la phase + rotation d'après le dernier rôle joué) :
        // le deck et la programmation des transitions lisent le même.
        segments = plan.phases.flatMapIndexed { pi, phase ->
            phase.tracks.filter { it.analyzed && it.bpm > 0f }
                .map { segmentFor(it, pi, phase.mood) }
        }
        if (segments.isEmpty()) {
            ui.post { listener.onStopped(false) }
            return
        }
        // Modulation par phase : segments un peu plus longs dans les phases
        // énergiques (peak), un peu plus courts dans les phases calmes.
        phaseLengthFactor = lengthFactors(plan)
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
     * COUPE (double appui sur suivant/précédent) : la transition en cours
     * ou en préparation est achevée tout de suite — fondu ramené à
     * [CUT_FRAMES] à partir de maintenant, sans traitement spectral ni
     * écho. Rien en vol : c'est un « suivant » qui se coupera dès que le
     * deck sera ouvert. Consommé par la boucle audio (cutRequested).
     */
    fun cutNow() {
        cutRequested = true
    }

    @Volatile private var cutRequested = false

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
        val seekFromMs: Long? = null,
        // Durée estimée (s) du fondu de la transition qui OUVRE ce deck :
        // alimente le pré-roll (preRollMs). 0.0 partout ailleurs —
        // ouverture initiale, saut manuel, seek — le pré-roll ne
        // s'applique qu'aux transitions automatiques, où personne
        // n'attend le passage fort immédiatement.
        val preFadeS: Double = 0.0,
        // Drop swaps enchaînés JUSQU'À ce deck (celui qui l'ouvre compris) :
        // à partir de deux, sa sortie n'est pas calée sur une montée — deux
        // d'affilée, c'est le geste des festivals ; trois, une formule.
        // Transmis tel quel quand le deck est rouvert (seek, répétition) :
        // sa fin ne doit pas bouger.
        val dropStreakIn: Int = 0
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
        /** Frames source réclamées alors que le décodeur n'avait rien
         *  fourni (famine) : c'est du son manquant, et la cause la plus
         *  probable d'une saccade en jonction — compté pour le journal. */
        @Volatile var starvedFrames = 0L
        // ~4 s de son décodé d'avance : quand on change d'appli, le
        // chargement de l'autre appli accapare le CPU et les décodeurs
        // prennent du retard — cette réserve absorbe le pic sans saccade.
        val queue = ArrayBlockingQueue<FloatArray>(192)
        private val openLatch = CountDownLatch(1)

        val startMs: Long
        val logicalEndMs: Long
        private val decodeEndMs: Long
        /** Pré-roll (preRollMs) en frames de sortie ; 0 hors transitions
         *  automatiques. Le fondu qui ouvre ce deck vise cette durée :
         *  son drop (l'ancre) tombe à ± une mesure de la fin du fondu. */
        val preRollOutFrames: Long
        /** Décalage (en temps) entre le départ du deck et sa grille de
         *  phrases de 16 temps : sert à la quantisation de phrase de la
         *  PROCHAINE transition, quand ce deck sera le sortant. Hors
         *  pré-roll, 0 : le départ est déjà un premier temps du passage
         *  fort (approximation acceptable, cf. nextPhraseBeat). */
        val phraseBeatOffset: Double

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
        /** Total rejoué par la boucle de SORTIE depuis l'ouverture du deck
         *  (jamais remis à zéro, contrairement à [loopedOut]) : une boucle
         *  qui tourne sous un fondu s'entend comme un saut, elle doit se
         *  lire dans le journal. */
        @Volatile var loopTotalOut = 0L
            private set
        // Gain de passe : la première répétition joue pleine, chaque
        // répétition SUPPLÉMENTAIRE s'atténue (x0,82, plancher 0,35).
        // Le plan ne compte que sur une répétition ; au-delà, c'est un
        // imprévu (deck lent à s'ouvrir, fader manuel tenu) — une boucle
        // qui s'efface sonne comme un geste, une boucle constante comme
        // un disque rayé.
        private var loopPassGain = 1f

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

        /** Tempo cible posé avant la PROCHAINE transition (calage partagé,
         *  splitRates) : le deck y glisse en douceur pendant l'avance
         *  d'ouverture ; null = tempo naturel (× cran de vitesse). Fil
         *  audio seulement. */
        var pretargetRate: Float? = null

        /** Section (structure) d'où ce deck SORT : celle qui contient la
         *  fin de son passage. null sans structure. */
        val exitKind: StructureDetector.SectionKind?

        /** Fin de passage calée sur la fin d'une montée, pour un drop swap
         *  vers le passage suivant (transitions pro). */
        val dropExit: Boolean

        /**
         * Sortie « montée » de ce passage vers le SUIVANT du set : seulement
         * si le suivant peut recevoir un drop swap (tempos calables, vrai
         * drop sur son ancre — mêmes règles que la programmation de la
         * transition, fadeSpecPro). @return -1 sinon.
         */
        private fun dropExitFor(
            track: Track, anchor: Long, segMs: Long,
            sections: List<StructureDetector.Section>
        ): Long {
            val nextSeg = segments.getOrNull(segIndex + 1) ?: return -1L
            val nx = nextSeg.track
            if (sections.isEmpty() || nx.structure.isEmpty() ||
                !tempoLockable(track.bpm, nx.bpm)
            ) return -1L
            val nxSections = StructureDetector.decode(nx.structure)
            val nxAnchor = entryAnchor(nextSeg, nxSections)
            if (!dropEntryOk(nx, nextSeg.passage == null, nxSections, nxAnchor)) return -1L
            return dropExitMs(
                min(anchor + segMs, track.durationMs), anchor, track.bpm,
                track.durationMs, sections
            )
        }

        init {
            val best = track.bestStartMs.coerceIn(0L, max(0L, track.durationMs - 15_000L))
            val beat = track.firstBeatMs
            // Structure décodée UNE fois : l'ancre (anchorFor), le calage de
            // fin (snapEndToStructure) et le pré-roll (preRollMs) la lisent.
            // Vide (ancienne analyse) : comportement historique.
            val sections = if (track.structure.isEmpty()) emptyList()
            else StructureDetector.decode(track.structure)
            // Passage par RÔLE (montée, groove, calme — Passages) choisi
            // pour ce set : son début est l'ancre, sa longueur la base du
            // passage. Sans (passage fort) : calage historique.
            val pas = segment.passage
            // Ancre : début du DROP le plus proche du passage fort quand la
            // structure le connaît, sinon premier beat du passage
            val anchor = if (pas != null) passageAnchor(pas, track.durationMs)
            else anchorFor(
                best, track.segmentMs, beat, track.bpm, track.durationMs, sections
            )
            // Modulation par phase, puis ré-arrondi aux phrases de 16 temps
            // pour que la fin reste sur une frontière musicale.
            var segMs = ((pas?.lengthMs ?: track.segmentMs) * lengthFactor).toLong()
            // Plancher : un passage plus court ne laisse pas le temps
            // d'apprécier le morceau (les fondus mangent déjà ~30 s à eux
            // deux). Borné par ce qu'il reste de morceau après l'ancre.
            segMs = max(segMs, min(MIN_SEGMENT_MS, track.durationMs - anchor))
            // Transitions pro : l'enchaînement long dure jusqu'à 8 mesures
            // — le passage est ALLONGÉ d'autant, pour que la minute forte
            // se joue entière avant que l'entrant ne s'installe.
            if (!playToEnd && proMode && track.bpm > 0f) {
                segMs += Math.round(PRO_EXTRA_BARS * 4.0 * 60_000.0 / track.bpm)
            }
            if (track.bpm > 0f) {
                val phraseMs = 16.0 * 60_000.0 / track.bpm
                var phrases = floor(segMs / phraseMs).toLong()
                // L'arrondi ne doit pas faire repasser sous le plancher
                if (phrases * phraseMs < segMs - 1) {
                    phrases = ceil(segMs / phraseMs).toLong()
                }
                if (phrases >= 2) segMs = (phrases * phraseMs).toLong()
            }
            // Le deck démarre sur le passage fort — un pré-roll d'un fondu
            // le devance sur les transitions automatiques (cf. preRollMs) —
            // et déroule le morceau naturellement sous le fondu d'entrée
            // (pas de boucle de début : essayée, jugée décevante). À
            // l'autre bout, la boucle de sortie (8 derniers battements)
            // prend le relais sous le fondu de sortie.
            // Quand le morceau a une structure détectée, la fin de passage
            // est calée sur la frontière de section la plus proche (fin d'un
            // temps fort de préférence) : la transition part d'une vraie fin
            // de phrase.
            // Transitions pro, drop swap possible vers le passage suivant :
            // la fin est calée sur la fin d'une MONTÉE (là où le drop de ce
            // morceau tomberait — c'est celui de l'entrant qui tombera).
            val dropEnd = if (!playToEnd && proMode && dropStreakIn < 2)
                dropExitFor(track, anchor, segMs, sections) else -1L
            dropExit = dropEnd > 0L
            val end = when {
                playToEnd && track.durationMs > anchor -> track.durationMs
                dropExit -> dropEnd
                else -> snapEndToStructure(
                    min(anchor + segMs, track.durationMs), anchor, track.bpm,
                    track.durationMs, sections
                )
            }
            // Pré-roll (transitions automatiques : preFadeS > 0) : le
            // deck démarre ~un fondu AVANT son ancre, arrondi à la
            // mesure — sous le fondu on entend sa montée, et son drop
            // tombe quand le sortant s'efface, pas le climax filtré.
            // La fin (logicalEndMs) ne bouge pas : le passage s'allonge
            // du pré-roll, c'est voulu. L'annonce du morceau, elle,
            // reste liée au fondu — rien ne change pour l'interface.
            // Passage par rôle : pas de pré-roll — son début EST l'entrée
            // voulue (une montée se joue entière sous le blend, un calme ou
            // un groove n'a pas de drop à faire tomber après le fondu).
            val preRoll = if (seekFromMs == null && !playFromStart && pas == null)
                preRollMs(anchor, Math.round(preFadeS * 1000.0), track.bpm, sections)
            else 0L
            startMs = when {
                // Déplacement manuel : garder de quoi faire une VRAIE
                // transition après le point visé — l'avance d'ouverture du
                // deck suivant plus quatre mesures de fondu (~16 s à
                // 120 BPM). Avec l'ancienne marge de 10 s, un déplacement
                // vers la fin du passage laissait la jonction automatique
                // se faire en 1,8 s (journal 10). Dernier passage : vraie
                // fin, pas de transition derrière, la marge courte suffit.
                seekFromMs != null -> {
                    val tail = if (playToEnd || track.bpm <= 0f) 10_000L
                    else max(
                        10_000L,
                        ((PREOPEN_LEAD_S + 4.0 * barSeconds(track.bpm)) * 1000.0).toLong()
                    )
                    seekFromMs.coerceIn(0L, max(0L, end - tail))
                }
                playFromStart ->
                    if (PlayerCore.skipIntros.value && track.musicStartMs > 1_500L)
                        track.musicStartMs
                    else 0L
                else -> anchor - preRoll
            }
            // Pré-roll en frames de SORTIE (même conversion que
            // totalOutFrames : la source est lue à `rate`) — lu au
            // moment de programmer le fondu pour en viser la durée.
            preRollOutFrames = (preRoll / 1000.0 * OUT_SR / rate).toLong()
            // Décalage entre le départ du deck et sa grille de phrases :
            // le pré-roll est un nombre entier de mesures, pas forcément
            // de phrases — les phrases restent ancrées sur l'ancre.
            phraseBeatOffset = if (preRoll > 0L && track.bpm > 0f)
                ((Math.round(preRoll / (4.0 * 60_000.0 / track.bpm)) * 4L) % 16L)
                    .toDouble()
            else 0.0
            logicalEndMs = end
            exitKind = sectionAt(sections, end - 1)
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

        /**
         * Attend que le deck ait une vraie réserve décodée avant d'être
         * confié au mixeur. [open] ne garantissait QUE le premier chunk —
         * quelques dizaines de millisecondes : le deck entrant abordait
         * donc son fondu sans avance, et le moindre à-coup du décodeur
         * s'entendait comme un trou PENDANT la transition, au pire
         * moment. Appelée depuis le fil d'ouverture (« DjOpen »), jamais
         * depuis le fil audio.
         *
         * S'arrête dès que la réserve est atteinte, que la file est pleine
         * (le décodeur attend alors qu'on consomme : rien de mieux à
         * espérer), que le morceau est entièrement décodé, ou au bout de
         * [deadlineMs] — mieux vaut une transition à l'heure avec peu
         * d'avance qu'une transition en retard.
         */
        fun prebuffer(minFrames: Int, deadlineMs: Long) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            while (!closed && !decoderDone &&
                queue.remainingCapacity() > 0 &&
                android.os.SystemClock.elapsedRealtime() - t0 < deadlineMs
            ) {
                var frames = 0
                for (c in queue) frames += c.size / 2
                if (frames >= minFrames) return
                try {
                    Thread.sleep(20L)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        val totalOutFrames: Long
            get() = ((logicalEndMs - startMs) / 1000.0 * OUT_SR / rate).toLong()

        /** Frames SOURCE réellement décodées et consommées (les frames de
         *  famine n'en sont pas : le flux reprend où il en était). */
        private var srcPulled = 0L

        /** Position SOURCE (ms dans le fichier) du prochain échantillon
         *  produit : exacte quels que soient les glissements de tempo
         *  passés — contrairement à framesOut, compté en sortie. Fil audio. */
        fun srcMsNow(): Double {
            val sr = srcSr
            if (sr <= 0) return startMs.toDouble()
            return startMs + (srcPulled - 2 + srcPos) * 1000.0 / sr
        }

        /** Frames de SORTIE avant que la source atteigne [ms], au tempo
         *  courant (figé pendant une transition). Fil audio. */
        fun outFramesUntilSrc(ms: Long): Long =
            ((ms - srcMsNow()) / 1000.0 * OUT_SR / curRate).toLong()

        /** Drop swap programmé : la boucle de sortie n'a pas le droit de
         *  partir avant que la SOURCE ait atteint la fin du passage (la fin
         *  de la montée) — framesOut, compté en sortie au tempo d'ouverture,
         *  peut y arriver plus tôt quand le tempo a glissé. */
        @Volatile var holdLoopToSrcEnd = false

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
                    srcPulled++
                    return true
                }
                curChunk = null
                val polled = queue.poll(250, TimeUnit.MILLISECONDS)
                if (polled == null) {
                    if (decoderDone && queue.isEmpty()) return false
                    // Famine du décodeur : décroître doucement vers le silence
                    // (tenir une valeur fixe produisait un bourdonnement type
                    // bégaiement sous forte charge CPU)
                    starvedFrames++
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
                    dst[i] = sL * loopPassGain
                    dst[i + 1] = sR * loopPassGain
                    loopPos++
                    if (loopPos >= loopLen + loopXfade) {
                        loopPos = loopXfade
                        // Chaque répétition au-delà de la première s'efface
                        loopPassGain = max(0.35f, loopPassGain * 0.82f)
                    }
                    loopedOut++
                    loopTotalOut++
                    out++
                    framesOut++
                    continue
                }
                // Fin du passage fort : basculer sur la boucle
                if (framesOut >= totalOutFrames &&
                    !(holdLoopToSrcEnd && srcMsNow() < logicalEndMs) &&
                    startLoop()
                ) continue
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

    /**
     * Sortie audio du moteur DJ. Extraite de [runMix] parce qu'elle sert
     * DEUX fois : à l'ouverture du set, et pour la reconstruire si le
     * système la tue en cours de route (cf. l'écriture qui échoue).
     */
    /** Canal du réveil (USAGE_ALARM) au lieu du média : posé par
     *  PlayerCore avant le lancement du set, lu à la construction de la
     *  sortie. Changé EN COURS de set, la sortie est reconstruite au bloc
     *  suivant (voir rebuildOutput dans runMix) : Android diffuse le canal
     *  alarme sur le haut-parleur ET le Bluetooth à la fois, on ne peut
     *  pas y rester une fois le réveil passé. */
    @Volatile private var alarmUsage = false

    /** Sortie à reconstruire au prochain bloc (canal changé en cours de set). */
    @Volatile private var outputSwitchRequested = false

    fun setAlarmUsage(on: Boolean) {
        if (alarmUsage == on) return
        alarmUsage = on
        if (running) outputSwitchRequested = true
    }

    /** [sessionId] : celui de la sortie précédente quand on la reconstruit,
     *  pour que l'égaliseur (attaché à la session) suive. */
    private fun newAudioTrack(
        sessionId: Int = android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
    ): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioTrack.Builder()
            .setSessionId(sessionId)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (alarmUsage) AudioAttributes.USAGE_ALARM
                        else AudioAttributes.USAGE_MEDIA
                    )
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
            // autre appli, GC...). Portée de ~1 s à ~2 s de PCM float
            // stéréo (8 octets par frame) : à 1 s, la musique saccadait
            // encore dès que le téléphone ramait. La latence des annonces
            // et des gestes suit outLatencyMs, rien d'autre à toucher.
            .setBufferSizeInBytes(
                max(minBuf * 3, OUT_SR * 2 * 8) + OUT_EXTRA_BYTES
            )
            .build()
    }

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
        // `var` : une sortie audio peut MOURIR en cours de route (redémarrage
        // du serveur audio, changement de route après une longue pause) — on
        // la reconstruit alors une fois plutôt que de tourner à vide.
        var audioTrack = newAudioTrack()
        // Exposé à stop() pour débloquer une écriture bloquante en cours ;
        // la propriété (création ET release, dans le finally) reste ici.
        liveAudioTrack = audioTrack
        // Le son écrit maintenant ne se fera entendre qu'une fois le tampon
        // écoulé : les annonces à l'interface sont retardées d'autant, sinon
        // le titre changerait une seconde avant qu'on entende le morceau.
        outLatencyMs = (
            audioTrack.bufferSizeInFrames.toLong() * 1000L / OUT_SR
            ).coerceIn(0L, 3_000L)
        diag(
            "set démarré : ${segments.size} passage(s), " +
                "transitions pro=${if (proMode) "oui" else "non"}, " +
                "tampon de sortie ${outLatencyMs} ms"
        )
        // Sous-alimentations de la sortie (le matériel a manqué de son) :
        // relevé au fil du set, c'est LA mesure d'une saccade réelle.
        var underrunsSeen = 0

        var deckA: Deck? = null
        var deckB: Deck? = null
        val tmpA = FloatArray(BLOCK_FRAMES * 2)
        val tmpB = FloatArray(BLOCK_FRAMES * 2)
        val out = FloatArray(BLOCK_FRAMES * 2)
        var framesGlobal = 0L
        var fadeStartF = -1L
        var fadeLenF = 0L
        var fadeKindF = KIND_NORMAL
        // Photos prises au DÉPART d'une transition, relues à son terme :
        // ce qui a manqué au décodeur du sortant, ce que le matériel a
        // manqué de son, et jusqu'où le limiteur a dû écraser le mélange
        // (deux morceaux qui s'additionnent, c'est là qu'il travaille).
        var fadeStarvedA = 0L
        var fadeStarvedB = 0L
        var fadeLoopedA = 0L
        var fadeUnderrunAt = 0
        var limMinInFade = 1f
        // Swap net des basses (KIND_EQ / KIND_HARMONIC) : frame du « 1 »
        // où le sortant cède ses basses d'un geste (-1 = geste progressif
        // historique) et durée de la rampe anti-clic (~1 temps), tous
        // deux PRÉCALCULÉS au moment de programmer le fondu — jamais
        // dans la boucle par bloc.
        var bassSwapF = -1L
        var bassSwapRampF = 1L
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
            val jumpTarget: Int,
            // Génération d'ouverture (cf. openGen) : un résultat d'une
            // génération passée est refermé sans être joué.
            val gen: Int,
            // Enchaînement long des transitions pro (pour le journal)
            val long: Boolean = false
        )

        val openResult =
            java.util.concurrent.atomic.AtomicReference<OpenResult?>(null)
        var opening = false
        // Jeton des ouvertures en vol : un seek reçu pendant qu'un deck
        // s'ouvre (8 s avant chaque transition, désormais) l'incrémente et
        // prend la main — l'ancienne ouverture, quand elle aboutit, est
        // refermée. Avant, le seek était simplement jeté : barre sourde
        // pendant toute la fenêtre d'ouverture.
        var openGen = 0
        // Volume appliqué sur l'AudioTrack (masterVolume + micro-fondus de
        // pause) : posé seulement quand il change.
        var trackVol = 1f
        // Copie glissante des derniers blocs écrits (voir RING_FRAMES) et
        // compte des frames écrites dans la sortie courante.
        val ring = FloatArray(RING_FRAMES * 2)
        var ringPos = 0
        var framesWrittenOut = 0L

        fun recordOut(buf: FloatArray) {
            // RING_FRAMES est un multiple de BLOCK_FRAMES : jamais à cheval
            System.arraycopy(buf, 0, ring, ringPos * 2, BLOCK_FRAMES * 2)
            ringPos = (ringPos + BLOCK_FRAMES) % RING_FRAMES
            framesWrittenOut += BLOCK_FRAMES
        }

        /**
         * Coupe franche IMMÉDIATE : jette ce que la sortie avait en tampon
         * (~2,3 s déjà mixées, pas encore entendues) pour que le prochain
         * bloc écrit soit entendu tout de suite. Sans ça, un double appui
         * calculait bien la coupe sur-le-champ… mais on l'entendait 2,3 s
         * plus tard — « le passage au morceau suivant est lent ». Le deck
         * sortant saute d'autant (il part de toute façon) ; à n'appeler
         * que si l'entrant n'a presque rien dans le tampon (voir les
         * appelants). La tête de lecture est remise à zéro par flush() :
         * la comptabilité suit. @return ms jetées.
         */
        fun flushOutput(): Long {
            val played = try {
                audioTrack.playbackHeadPosition.toLong() and 0xffffffffL
            } catch (_: Exception) {
                framesWrittenOut
            }
            val unplayed = (framesWrittenOut - played).coerceAtLeast(0L)
            try {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.play()
            } catch (_: Exception) {
            }
            framesWrittenOut = 0L
            outFlushedAtFrame = framesGlobal
            // Le premier bloc après la purge part sur un tampon vide : une
            // sous-alimentation comptée qui n'est pas une saccade.
            fadeUnderrunAt = audioTrack.underrunCount + 1
            return unplayed * 1000L / OUT_SR
        }

        /**
         * Reconstruit la sortie audio en cours de set — changement de canal
         * (fin du réveil), ou sortie morte — sur la MÊME session audio
         * (l'égaliseur suit), en y réécrivant ce que l'ancienne avait en
         * tampon sans l'avoir encore joué : la musique reprend où
         * l'oreille en était, au lieu de sauter d'un tampon entier (~2,3 s)
         * en avant. [playing] faux pendant une pause : la nouvelle sortie
         * attend la reprise. Faux si la sortie n'a pas pu être recréée.
         */
        fun rebuildOutput(reason: String, playing: Boolean): Boolean {
            val old = audioTrack
            val played = try {
                old.playbackHeadPosition.toLong() and 0xffffffffL
            } catch (_: Exception) {
                framesWrittenOut
            }
            val unplayed = (framesWrittenOut - played).coerceIn(0L, RING_FRAMES.toLong())
            val fresh = try {
                newAudioTrack(old.audioSessionId)
            } catch (e: Exception) {
                djLog("reconstruction de la sortie impossible : ${e.message}")
                return false
            }
            try {
                old.pause()
                old.flush()
            } catch (_: Exception) {
            }
            try {
                old.release()
            } catch (_: Exception) {
            }
            try {
                fresh.setVolume(trackVol)
            } catch (_: Exception) {
            }
            // Réécriture bornée au tampon neuf : une écriture AVANT play()
            // bloque dès qu'il est plein.
            val cap = (fresh.bufferSizeInFrames - BLOCK_FRAMES).toLong().coerceAtLeast(0L)
            val n = minOf(unplayed, cap).toInt()
            var start = ((ringPos - n) % RING_FRAMES + RING_FRAMES) % RING_FRAMES
            var left = n
            while (left > 0) {
                val chunk = minOf(left, RING_FRAMES - start)
                fresh.write(ring, start * 2, chunk * 2, AudioTrack.WRITE_BLOCKING)
                start = (start + chunk) % RING_FRAMES
                left -= chunk
            }
            framesWrittenOut = n.toLong()
            if (playing) fresh.play()
            audioTrack = fresh
            liveAudioTrack = fresh
            diag("sortie audio reconstruite ($reason) : ${n * 1000L / OUT_SR} ms réécrits")
            return true
        }
        var failedForSeg = -1
        // Fin de set annoncée une seule fois (dernier passage, dernières
        // secondes) — désarmée si un saut ramène en arrière dans le plan.
        var setEndingSent = false
        // Mode pro : structure de l'entrant décodée UNE fois par candidat
        // et mise en cache — le bloc « programmer la prochaine transition »
        // repasse à chaque tour tant que le fondu n'est pas dû, pas
        // question d'y décoder en boucle.
        var proSectionsIdx = -1
        var proSections: List<StructureDetector.Section> = emptyList()

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

        // Répétition : SAUT direct aux ~15 dernières secondes du deck, en
        // le ROUVRANT à la bonne position (même mécanique que le
        // déplacement manuel, seekFromMs). L'ancienne « avance rapide »
        // LISAIT tout le morceau en écrivant un bloc de silence sur huit
        // (WRITE_BLOCKING) : à ~8x le temps réel, « répéter les
        // transitions » jouait des dizaines de secondes de silence avant
        // chaque jonction — vécu comme « ça ne lance rien ». Rouvrir
        // coûte ~0,5 s de silence, une fois par morceau. Échec
        // d'ouverture : le deck d'origine continue tel quel.
        fun rehearsalSkip(d: Deck): Deck {
            if (!rehearsal) return d
            if (d.remainingOut <= 15L * OUT_SR) return d
            val target = d.logicalEndMs - 15_000L
            if (target <= 0L) return d
            val factor = phaseLengthFactor.getOrElse(d.segment.phaseIndex) { 1f }
            // Mêmes paramètres de longueur que le deck d'origine : le
            // calcul de fin (segMs x facteur + calage structure) est pur,
            // la réouverture retombe sur le même logicalEndMs.
            val nd = Deck(
                d.segIndex, d.segment, d.curRate, factor,
                playToEnd = d.playToEnd, seekFromMs = target,
                dropStreakIn = d.dropStreakIn
            )
            if (!nd.open()) {
                nd.close()
                return d
            }
            d.close()
            nd.startedAtFrame = framesGlobal
            return nd
        }

        fun openNextValid(
            fromIndex: Int,
            rate: Float,
            fromStart: Boolean = false,
            // Durée estimée du fondu à venir, pour le pré-roll du deck
            // ouvert (transitions automatiques seulement). Si le premier
            // candidat échoue, le suivant hérite de la même estimation —
            // même approximation que `rate`, calculé lui aussi pour le
            // candidat d'origine.
            preFadeS: Double = 0.0,
            dropStreakIn: Int = 0
        ): Deck? {
            var idx = fromIndex
            while (running && idx < segments.size) {
                val factor = phaseLengthFactor.getOrElse(segments[idx].phaseIndex) { 1f }
                val d = Deck(
                    idx, segments[idx], rate, factor,
                    playToEnd = idx == segments.size - 1,
                    playFromStart = fromStart,
                    preFadeS = preFadeS,
                    dropStreakIn = dropStreakIn
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
            // Reprise « DJ sur ce morceau » : le premier deck repart de la
            // position où la lecture en était (mécanique seekFromMs du
            // déplacement manuel) au lieu du début du fichier. Sous 3 s,
            // autant repartir du début proprement.
            val resume = pendingFirstSeekMs
            pendingFirstSeekMs = null
            deckA = if (resume != null && resume > 3_000L) {
                val seg = segments[startSegIndex]
                val factor =
                    phaseLengthFactor.getOrElse(seg.phaseIndex) { 1f }
                val d = Deck(
                    startSegIndex, seg, 1f, factor,
                    playToEnd = startSegIndex == segments.size - 1,
                    seekFromMs = resume
                )
                if (d.open()) d else {
                    d.close()
                    openNextValid(startSegIndex, 1f, fromStart = true)
                }
            } else {
                openNextValid(startSegIndex, 1f, fromStart = true)
            }
            if (deckA == null) {
                djLog("aucun deck n'a pu s'ouvrir au lancement (décodage impossible ?)")
                return
            }
            deckA.startedAtFrame = 0L
            currentPhaseIndex = deckA.segment.phaseIndex
            currentSegIndex = deckA.segIndex
            announce(deckA)
            deckA = rehearsalSkip(deckA)

            while (running && gen == runGeneration) {
                // Pause (miroir du bouton play/pause et du Bluetooth).
                // 200 ms entre deux réveils : à 40 ms, ce thread (priorité
                // audio max) se réveillait 25 fois par seconde pour rien
                // pendant toute la pause ; la latence de reprise reste
                // imperceptible à 5 réveils par seconde.
                if (paused) {
                    // Micro-fondu de pause : le tampon de sortie continue
                    // de jouer pendant la rampe, la descente s'entend —
                    // puis seulement la pause. Symétrique à la reprise.
                    rampTrackVolume(audioTrack, trackVol, 0f, PAUSE_FADE_MS)
                    trackVol = 0f
                    audioTrack.pause()
                    val pausedAt = android.os.SystemClock.elapsedRealtime()
                    while (paused && running && gen == runGeneration) {
                        // Canal changé pendant la pause (fin du réveil) : le
                        // meilleur moment, la sortie est muette.
                        if (outputSwitchRequested) {
                            outputSwitchRequested = false
                            rebuildOutput("changement de canal en pause", playing = false)
                        }
                        Thread.sleep(200)
                    }
                    if (!running) break
                    audioTrack.play()
                    rampTrackVolume(audioTrack, 0f, masterVolume, RESUME_FADE_MS)
                    trackVol = masterVolume
                    // Une reprise après des heures de pause n'a rien d'anodin
                    // (décodeurs et sortie audio dormaient) : c'est le genre
                    // de contexte qu'on veut voir dans le journal quand une
                    // jonction se passe mal juste après.
                    val pausedMs = android.os.SystemClock.elapsedRealtime() - pausedAt
                    if (pausedMs > 60_000L) {
                        diag("reprise après ${pausedMs / 60_000L} min de pause")
                    }
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
                    // Base = sa part du calage à venir (pretargetRate) ou
                    // le tempo naturel ; le cran de vitesse s'y multiplie.
                    val pre = a.pretargetRate
                    val target = (pre ?: 1f) * manualFactor(level)
                    if (level != lastSpeedLevel) {
                        lastSpeedLevel = level
                        manualRamp = true
                    }
                    if (abs(a.curRate - target) < 1e-4f) manualRamp = false
                    if (manualRamp) {
                        a.nudgeTowardNatural(0.005f, framesGlobal, target)
                    } else if (pre != null) {
                        // Glissement vers sa part du calage : ~2 %/s, assez
                        // lent pour ne pas s'entendre, assez vite pour être
                        // en place avant que le fondu soit programmé.
                        a.nudgeTowardNatural(2f * NATURAL_STEP, framesGlobal, target)
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
                    bassSwapF = -1L
                    echoBuf = null
                }
                // Coupe franche demandée (double appui) : rien en vol ni en
                // préparation → c'est un « suivant » qui se coupera dès que
                // le deck sera ouvert.
                if (cutRequested && deckB == null && !opening && pendingJump == -1) {
                    pendingJump = -2
                }
                // Sur une transition chargée : le fondu est ramené à
                // CUT_FRAMES à partir de maintenant — le deck entrant est
                // seul dans 80 ms.
                if (cutRequested && deckB != null && fadeStartF >= 0L) {
                    cutRequested = false
                    val cutEnd = framesGlobal + CUT_FRAMES
                    if (fadeStartF + fadeLenF > cutEnd) {
                        // L'entrant est-il déjà dans le tampon de sortie ?
                        // Pas encore (fondu programmé plus tard) ou à peine
                        // (< 200 ms) : on PURGE le tampon, la coupe
                        // s'entend tout de suite au lieu de 2,3 s plus tard.
                        // Au-delà, purger jetterait le début de l'entrant
                        // (son drop) : on garde le tampon.
                        val startedF = framesGlobal - deckB!!.startedAtFrame
                        fadeStartF = framesGlobal
                        // Entrant pas encore parti (drop swap avant son
                        // « 1 ») : il part maintenant.
                        if (deckB!!.startedAtFrame > framesGlobal) {
                            deckB!!.startedAtFrame = framesGlobal
                        }
                        fadeLenF = CUT_FRAMES
                        fadeKindF = KIND_CUT
                        bassSwapF = -1L
                        echoBuf = null
                        val flushed = if (startedF <= OUT_SR / 5L) flushOutput() else -1L
                        diag(
                            "coupe franche (double appui) : transition achevée en 80 ms" +
                                (if (flushed >= 0L) ", tampon purgé ($flushed ms)" else "")
                        )
                    }
                }

                // Déplacement demandé dans le morceau en cours : on rouvre
                // le même morceau à l'endroit visé et on y fait une vraie
                // transition. Ignoré si une transition est déjà en route —
                // on ne va pas couper un fondu en cours.
                val ps = pendingSeek
                if (ps >= 0f) {
                    pendingSeek = -1f
                    if (deckB == null) {
                        // Une ouverture en vol (le deck suivant, ouvert
                        // 8 s avant sa transition, ou un seek précédent)
                        // est supplantée : nouvelle génération, son résultat
                        // sera refermé à l'arrivée. Le geste le plus récent
                        // gagne — un saut en attente aussi s'efface.
                        if (opening) openGen++
                        pendingJump = -1
                        val from = a.startMs
                        val to = a.logicalEndMs
                        val at = from + ((to - from) * ps).toLong()
                        opening = true
                        val og = openGen
                        val curRate = a.curRate
                        val segIdx = a.segIndex
                        val streakIn = a.dropStreakIn
                        val factor = phaseLengthFactor
                            .getOrElse(segments[segIdx].phaseIndex) { 1f }
                        thread(name = "DjSeek") {
                            val d = Deck(
                                segIdx, segments[segIdx], curRate, factor,
                                playToEnd = segIdx == segments.size - 1,
                                seekFromMs = at,
                                dropStreakIn = streakIn
                            )
                            val ok = if (d.open()) d else { d.close(); null }
                            // Réserve décodée courte : le geste attend, mais
                            // un deck sans avance faisait des trous.
                            ok?.prebuffer(
                                PREBUFFER_FRAMES, GESTURE_PREBUFFER_DEADLINE_MS
                            )
                            // getAndSet : un résultat non encore consommé
                            // (ouverture supplantée) est refermé, jamais fuité.
                            // Deux MESURES pour un déplacement : 8 s de deux
                            // endroits du même morceau superposés, c'était
                            // long ; deux mesures suffisent à se déplacer en
                            // musique. Sans tempo : la durée historique.
                            val bpmEff = segments[segIdx].track.bpm * curRate
                            val seekFadeS = if (bpmEff > 0f) 2 * barSeconds(bpmEff)
                            else SEEK_FADE_S
                            openResult.getAndSet(
                                OpenResult(ok, seekFadeS, KIND_EQ, true, -1, og)
                            )?.deck?.close()
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
                        val nx = segments[nextIdx].track
                        // Calage PARTAGÉ (splitRates) : chaque deck fait la
                        // moitié du chemin. Le sortant glisse vers sa part
                        // pendant l'avance d'ouverture (cf. pretargetRate),
                        // l'entrant ouvre directement à la sienne ; hors
                        // plage, (1, 1) et fadeSpec coupera. Saut manuel :
                        // pas le temps de glisser, l'entrant prend tout.
                        // Cran de vitesse manuel : il MULTIPLIE tout — le
                        // sortant (via la cible du nudge) comme l'entrant.
                        // Avant, l'entrant ouvrait à sa seule part de calage,
                        // au tempo naturel : avec un cran à +8 %, la jonction
                        // se faisait à deux tempos différents (les temps
                        // dérivaient pendant le fondu), puis le nouveau deck
                        // remontait lentement vers le cran — un coup de mou
                        // à chaque transition. Le calage (splitRates,
                        // computeRate, fadeSpec) se raisonne sur les tempos
                        // NATURELS, le cran s'applique ensuite.
                        val manual = manualFactor(PlayerCore.speedLevel.value)
                        val (rateA0, rateB0) = splitRates(a.track.bpm, nx.bpm)
                        val rateA = if (jumping) a.curRate / manual else rateA0
                        val lockB = if (jumping)
                            computeRate(a.track.bpm * a.curRate / manual, nx.bpm) else rateB0
                        val rate = lockB * manual
                        // Structure de l'entrant (décodée une fois, en cache)
                        // et son ancre — recalculée comme dans Deck.init :
                        // c'est là que le drop tombera. Sert au mode pro ET
                        // au choix local de la technique (section d'entrée).
                        if (proSectionsIdx != nextIdx) {
                            proSectionsIdx = nextIdx
                            proSections = if (nx.structure.isEmpty())
                                emptyList()
                            else StructureDetector.decode(nx.structure)
                        }
                        // Passage par rôle choisi pour ce segment : même
                        // ancre que celle que prendra Deck.init.
                        val anchor = entryAnchor(segments[nextIdx], proSections)
                        // Mode pro : medley, enchaînement long ou drop swap
                        // (ce dernier seulement si le sortant a été calé
                        // pour finir sur sa montée, vers CE passage-ci).
                        val choice = if (proMode) {
                            fadeSpecPro(
                                a.track, rateA, nx, lockB, jumping, lastFadeKind,
                                a.dropExit && nextIdx == a.segIndex + 1,
                                segments[nextIdx].passage == null,
                                proSections, anchor, a.exitKind
                            )
                        } else fadeSpec(
                            a.track, rateA, nx, lockB, jumping, lastFadeKind,
                            a.exitKind, sectionAt(proSections, anchor)
                        ).let { ProChoice(it.first, it.second, false) }
                        val rawFadeS = choice.fadeS
                        val fadeKind = choice.kind
                        val fadeLong = choice.long
                        // Durée en MESURES du sortant (fadeBars) : arrêtée
                        // ICI, avant l'ouverture du deck — le pré-roll de
                        // l'entrant est calculé dessus (Deck.init), les deux
                        // restent cohérents. Sans tempo : plafond en secondes.
                        val bpmEff = a.track.bpm * a.curRate
                        val fadeS = if (bpmEff > 0f) {
                            val barS = barSeconds(bpmEff)
                            val segBars = a.totalOutFrames.toDouble() / OUT_SR / barS
                            (if (jumping) 2 else fadeBars(fadeKind, segBars, fadeLong)) * barS
                        } else clampFadeS(rawFadeS, a.totalOutFrames)
                        val fadeF = (fadeS * OUT_SR).toLong()
                        // Le sortant commence à glisser vers sa part du
                        // calage un peu avant l'ouverture : à ~2 %/s, il y
                        // est bien avant que le fondu soit programmé.
                        // Drop swap : le reste se lit sur la position SOURCE
                        // (la fin de la montée), pas sur le compteur de
                        // sortie qui dérive avec les glissements de tempo.
                        val remain = if (fadeKind == KIND_DROP)
                            a.outFramesUntilSrc(a.logicalEndMs).coerceAtLeast(0L)
                        else a.remainingOut
                        if (!jumping) {
                            a.pretargetRate = if (
                                remain <= fadeF + (PREOPEN_LEAD_S + 4L) * OUT_SR
                            ) rateA0 else null
                        }
                        // Avance d'ouverture : le deck entrant doit être
                        // prêt ET pré-décodé avant l'heure du fondu (cf.
                        // PREOPEN_LEAD_S). Son décodeur se bloque de
                        // lui-même dès la file pleine : ouvrir tôt ne
                        // coûte rien, et évite que les deux décodeurs se
                        // disputent le CPU pile pendant la transition.
                        if (jumping ||
                            remain <= fadeF + PREOPEN_LEAD_S * OUT_SR
                        ) {
                            opening = true
                            val jt = pendingJump
                            val og = openGen
                            // Drop swaps enchaînés jusqu'à l'entrant (lu ici,
                            // sur le fil audio, qui seul l'écrit)
                            val streakIn = if (fadeKind == KIND_DROP) dropStreak + 1 else 0
                            thread(name = "DjOpen") {
                                // Pré-roll : transitions automatiques
                                // seulement — sur un saut manuel,
                                // l'utilisateur attend le passage fort
                                // tout de suite ; sur un drop swap,
                                // l'entrant part PILE sur son drop.
                                val b = openNextValid(
                                    nextIdx, rate,
                                    preFadeS = if (jumping || fadeKind == KIND_DROP) 0.0
                                    else fadeS,
                                    dropStreakIn = streakIn
                                )
                                // Réserve décodée avant de le confier au
                                // mixeur : c'est ce fil-ci qui attend, le
                                // fil audio ne voit qu'un deck déjà prêt.
                                // Attente courte sur un saut manuel :
                                // quelqu'un attend.
                                b?.prebuffer(
                                    PREBUFFER_FRAMES,
                                    if (jumping) GESTURE_PREBUFFER_DEADLINE_MS
                                    else PREBUFFER_DEADLINE_MS
                                )
                                // getAndSet : un résultat non encore consommé
                                // (ouverture supplantée) est refermé, jamais fuité.
                                openResult.getAndSet(
                                    OpenResult(b, fadeS, fadeKind, jumping, jt, og, fadeLong)
                                )?.deck?.close()
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
                if (ready != null && ready.gen != openGen) {
                    // Ouverture périmée (un seek l'a supplantée) : refermée
                    // sans être jouée. `opening` reste levé : l'ouverture
                    // qui l'a remplacée est encore en vol.
                    ready.deck?.close()
                } else if (ready != null) {
                    opening = false
                    val b = ready.deck
                    val pj2 = pendingJump
                    if (b == null) {
                        // Le passage suivant n'a pas pu s'ouvrir : le set
                        // saute ce morceau (ou s'arrête s'il était le
                        // dernier) — ça s'entend, ça se journalise.
                        diag("ouverture impossible du passage suivant : morceau sauté")
                        failedForSeg = a.segIndex
                        if (ready.jumping) pendingJump = -1
                    } else if (pj2 != -1 && pj2 != ready.jumpTarget) {
                        // Un autre saut est arrivé pendant l'ouverture
                        b.close()
                    } else {
                        // Pré-roll de l'entrant : quand B démarre en avance
                        // sur son ancre, le fondu VISE la durée du pré-roll
                        // (en frames de sortie) — le drop de B tombe alors
                        // à ± une mesure de la fin du fondu, pile quand le
                        // sortant s'efface. Sans pré-roll : durée nominale.
                        val preF = b.preRollOutFrames
                        val fadeF = if (preF > 0L) preF
                        else (ready.fadeS * OUT_SR).toLong()
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
                        // Phrase de 16 temps : un DJ d'electro lance sa
                        // transition sur un « 1 » de phrase, pas seulement
                        // de mesure. Grille de phrase = celle du deck A,
                        // ancrée sur son départ — départ qui est déjà un
                        // premier temps de son passage fort (approximation
                        // acceptable), corrigé du pré-roll éventuel
                        // (cf. Deck.phraseBeatOffset).
                        val nextPhrase =
                            nextPhraseBeat(phaseAtDue, a.phraseBeatOffset)
                        val toBeat = ((nextBeat - phaseNow) * period).toLong()
                        val toBar = ((nextBar - phaseNow) * period).toLong()
                        val toPhrase = ((nextPhrase - phaseNow) * period).toLong()
                        // Budget de PLAN sur la boucle de sortie : une seule
                        // répétition de sa cellule de 8 temps. Planifier
                        // au-delà (l'ancien budget allait jusqu'à
                        // LOOP_MAX_OUT, 30 s) faisait tourner la même
                        // cellule jusqu'à 8 fois sous le fondu — l'effet
                        // « disque rayé ». LOOP_MAX_OUT reste le garde-fou
                        // d'EXÉCUTION (fader manuel tenu, deck lent à
                        // s'ouvrir), pas un budget de plan.
                        val loopSlack = (8.0 * period).toLong()
                        val barF0 = (4.0 * period).toLong().coerceAtLeast(1L)
                        // LA PHRASE D'ABORD. Une transition qui part au
                        // milieu d'une phrase est exactement ce qui fait
                        // amateur : plutôt que d'y renoncer quand la phrase
                        // suivante ne laisse plus la place au fondu, on
                        // ADAPTE LE FONDU — la phrase précédente est-elle
                        // encore devant nous ? on part là, fondu un peu plus
                        // long (au plus deux mesures de plus) ; sinon on
                        // attend la phrase suivante avec un fondu raccourci
                        // (jamais sous une mesure). Mesure et temps ne
                        // restent que pour les sauts manuels (réponse rapide)
                        // et les cas sans matière. Le libellé part au
                        // journal (une allocation par transition, jamais
                        // dans la boucle par bloc).
                        val budget = a.remainingOut + loopSlack
                        var fadeUse = fadeF
                        val (quantOff, quant) = when {
                            ready.jumping ->
                                if (toBar <= a.remainingOut + OUT_SR) toBar to "mesure"
                                else toBeat to "temps"
                            toPhrase + fadeF <= budget -> toPhrase to "phrase"
                            else -> {
                                val toPrev = ((nextPhrase - 16.0 - phaseNow) * period).toLong()
                                val longer = a.remainingOut - toPrev
                                val shorter = budget - toPhrase
                                when {
                                    toPrev >= 0L && longer <= fadeF + 2L * barF0 -> {
                                        fadeUse = longer
                                        toPrev to "phrase (fondu allongé)"
                                    }
                                    shorter >= barF0 -> {
                                        fadeUse = shorter
                                        toPhrase to "phrase (fondu raccourci)"
                                    }
                                    toBar <= a.remainingOut + OUT_SR -> toBar to "mesure"
                                    else -> toBeat to "temps"
                                }
                            }
                        }
                        var start = framesGlobal + quantOff
                        if (start < framesGlobal) start = framesGlobal
                        // La boucle de sortie prolonge le deck A sous le
                        // fondu : la capacité inclut UNE répétition de boucle
                        val maxLen = a.remainingOut + loopSlack -
                                (start - framesGlobal) - OUT_SR / 10
                        fadeStartF = start
                        fadeLenF = min(fadeUse, max(OUT_SR / 4L, maxLen))
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
                        // Départ de l'entrant : le début du fondu, sauf
                        // pour le drop swap où il part sur le « 1 ».
                        var bStartF = fadeStartF
                        var quantLabel = quant
                        var dropSwapAt = -1L
                        if (fadeKindF == KIND_DROP) {
                            // DROP SWAP : l'instant du « 1 » est celui où la
                            // SOURCE du sortant atteint la fin de sa montée
                            // (son tempo est figé dès qu'un deck B est en
                            // vol) — calé à l'échantillon, indépendant de la
                            // grille de sortie. La jonction démarre
                            // DROP_PRE_BARS plus tôt (passe-haut montant sur
                            // le sortant), l'entrant n'est lu qu'au « 1 ».
                            val toSwap = a.outFramesUntilSrc(a.logicalEndMs)
                            val barD = 4.0 * period
                            if (period > 0.0 && !period.isNaN() && toSwap >= (barD / 2.0).toLong()) {
                                dropSwapAt = framesGlobal + toSwap
                                fadeStartF = max(framesGlobal, dropSwapAt - (DROP_PRE_BARS * barD).toLong())
                                fadeLenF = dropSwapAt - fadeStartF + (DROP_TAIL_BARS * barD).toLong()
                                bStartF = dropSwapAt
                                a.holdLoopToSrcEnd = true
                                quantLabel = "fin de montée (source, à " +
                                    "${toSwap * 1000L / OUT_SR} ms)"
                            } else {
                                // Montée déjà passée (deck lent à s'ouvrir) :
                                // un drop swap raté est pire que rien —
                                // coupe courte à la mesure.
                                fadeKindF = KIND_CUT
                                quantLabel = "$quant (montée manquée)"
                            }
                        }
                        lastFadeKind = fadeKindF
                        // Coupe franche en attente (double appui pendant
                        // l'ouverture du deck) : pas de fondu du tout.
                        if (cutRequested) {
                            cutRequested = false
                            fadeStartF = framesGlobal
                            bStartF = framesGlobal
                            fadeLenF = CUT_FRAMES
                            fadeKindF = KIND_CUT
                            // Le tampon ne contient que le sortant : purgé,
                            // l'entrant est entendu tout de suite.
                            val flushed = flushOutput()
                            diag("coupe franche (double appui) : arrivée directe, tampon purgé ($flushed ms)")
                        }
                        // Compteur de drop swaps enchaînés (cf. Deck.dropStreakIn) :
                        // permis deux fois d'affilée, jamais trois.
                        dropStreak =
                            if (fadeKindF == KIND_DROP) dropStreak + 1
                            else 0
                        // Swap net des basses (KIND_EQ / KIND_HARMONIC) :
                        // le « 1 » du swap est la dernière frontière de
                        // mesure de A avant la fin du fondu — l'instant où
                        // le drop de l'entrant arrive avec le pré-roll.
                        // Précalculé ICI (une variable Long, comme
                        // fadeStartF) : rien à chercher dans la boucle par
                        // bloc. Période invalide (BPM absent) : -1, le
                        // geste progressif historique s'applique.
                        bassSwapF = -1L
                        if (fadeKindF == KIND_DROP) {
                            // Drop swap : le « 1 » de la coupe (cf. plus haut)
                            bassSwapF = dropSwapAt
                        } else if ((fadeKindF == KIND_EQ || fadeKindF == KIND_HARMONIC) &&
                            period > 0.0 && !period.isNaN()
                        ) {
                            val endPhase = phaseNow +
                                (fadeStartF + fadeLenF - framesGlobal) / period
                            val swapPhase = bassSwapPhase(endPhase)
                            bassSwapF = (framesGlobal +
                                ((swapPhase - phaseNow) * period).toLong())
                                .coerceAtLeast(fadeStartF)
                            // Rampe anti-clic d'un temps, calculée hors
                            // boucle chaude
                            bassSwapRampF = period.toLong().coerceAtLeast(1L)
                        }
                        // Echo-out : ligne à retard d'un battement —
                        // allouée ICI (jamais dans la boucle par bloc),
                        // pour la coupe écho ET pour le drop swap, qui en
                        // fait sa queue d'un temps.
                        echoBuf = if (fadeLenF > CUT_FRAMES &&
                            (fadeKindF == KIND_CUT || fadeKindF == KIND_DROP)
                        ) {
                            echoPos = 0
                            FloatArray(
                                a.beatPeriodFrames.toInt()
                                    .coerceIn(4_410, 88_200) * 2
                            )
                        } else null

                        b.startedAtFrame = bStartF
                        deckB = b
                        pendingJump = -1
                        // Une jonction DJ ne laissait AUCUNE trace : quand
                        // une transition sonnait mal, le journal ne disait
                        // ni son type, ni sa durée, ni son calage. Une
                        // ligne par transition (≈ une par minute) suffit.
                        diag(
                            "transition « ${a.track.title} » → « ${b.track.title} » : " +
                                "${kindName(fadeKindF)}" +
                                (if (proMode && !ready.jumping) ", style " + when {
                                    fadeKindF == KIND_DROP -> "drop"
                                    ready.long -> "enchaînement long"
                                    else -> "medley"
                                } else "") +
                                ", fondu ${fadeLenF * 1000L / OUT_SR} ms, calée sur $quantLabel, " +
                                "pré-roll ${preF * 1000L / OUT_SR} ms, " +
                                "tempo entrant ×${"%.3f".format(b.curRate)}" +
                                (if (ready.jumping) ", geste" else "")
                        )
                        fadeStarvedA = a.starvedFrames
                        fadeStarvedB = b.starvedFrames
                        fadeLoopedA = a.loopTotalOut
                        fadeUnderrunAt = audioTrack.underrunCount
                        limMinInFade = 1f
                    }
                }

                // Remplir un bloc
                java.util.Arrays.fill(tmpA, 0f)
                java.util.Arrays.fill(tmpB, 0f)
                val na = a.read(tmpA, 0, BLOCK_FRAMES)
                val b = deckB
                if (b != null) {
                    if (na == 0 && framesGlobal < b.startedAtFrame) {
                        // Deck A épuisé plus tôt que prévu : démarrer B tout
                        // de suite (drop swap : le « 1 » est avancé d'autant)
                        diag(
                            "le sortant s'est tari avant l'heure : entrant avancé de " +
                                "${(b.startedAtFrame - framesGlobal) * 1000L / OUT_SR} ms " +
                                "(calage sur la phrase perdu)"
                        )
                        if (framesGlobal < fadeStartF) fadeStartF = framesGlobal
                        b.startedAtFrame = framesGlobal
                        // Le sortant s'est tu : libérer tout de suite les
                        // basses de l'entrant (le « 1 » du swap visait une
                        // fin de fondu qui n'existe plus) — la rampe d'un
                        // temps évite le clic.
                        if (bassSwapF > framesGlobal) bassSwapF = framesGlobal
                    }
                    // L'entrant n'est lu qu'à partir de son départ : le début
                    // du fondu, ou le « 1 » d'un drop swap.
                    if (framesGlobal + BLOCK_FRAMES > b.startedAtFrame) {
                        val off = max(0L, b.startedAtFrame - framesGlobal).toInt()
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
                } else if (fadeActive && framesGlobal >= fadeStartF &&
                    fadeKindF == KIND_DROP && bassSwapF > fadeStartF
                ) {
                    // Drop swap : passe-haut du sortant qui grimpe sur la
                    // fin de sa montée, jusqu'au « 1 » (cf. dropHpFc)
                    val t = (framesGlobal - fadeStartF).toFloat() /
                        (bassSwapF - fadeStartF)
                    sweepAlpha = 1f - exp(-2f * Math.PI.toFloat() * dropHpFc(t) / OUT_SR)
                }
                // Entrant : passe-bas 2 pôles dont la coupure monte de
                // 140 Hz (basses seules) à 16 kHz, puis fondu vers le
                // signal plein pour une ouverture totale et transparente.
                var alphaB = 0f
                var openMix = 0f
                // (Coupe et drop swap : l'entrant arrive brut, plein spectre.)
                if (fadeActive && framesGlobal >= fadeStartF &&
                    fadeKindF != KIND_CUT && fadeKindF != KIND_DROP
                ) {
                    val o = ((xb - shape[2]) / (shape[3] - shape[2]))
                        .coerceIn(0f, 1f)
                    val fcB = OPEN_FC_LOW *
                        (OPEN_FC_HIGH / OPEN_FC_LOW).pow(o.pow(1.4f))
                    alphaB = 1f - exp(-2f * Math.PI.toFloat() * fcB / OUT_SR)
                    openMix = o.pow(3f)
                }
                // Swap net des basses (KIND_EQ / KIND_HARMONIC) :
                // progression de la rampe du swap aux bornes du bloc,
                // interpolée par échantillon dans la boucle — le « 1 »
                // (bassSwapF) et la rampe d'un temps ont été précalculés
                // au moment de programmer le fondu : ici deux floats par
                // bloc, zéro allocation, zéro pas sec.
                var swapOn = false
                var swapT0 = 0f
                var swapStep = 0f
                if (fadeActive && bassSwapF >= 0L &&
                    (fadeKindF == KIND_EQ || fadeKindF == KIND_HARMONIC)
                ) {
                    swapOn = true
                    val ramp = bassSwapRampF.toFloat()
                    swapT0 = ((framesGlobal - bassSwapF).toFloat() / ramp)
                        .coerceIn(0f, 1f)
                    val swapT1 =
                        ((framesGlobal + BLOCK_FRAMES - bassSwapF).toFloat() / ramp)
                            .coerceIn(0f, 1f)
                    swapStep = (swapT1 - swapT0) / BLOCK_FRAMES
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
                        } else if (fadeKindF == KIND_DROP) {
                            // Drop swap : sortant plein (passe-haut montant)
                            // jusqu'au « 1 », puis coupé ; l'entrant tombe
                            // plein sur son drop au même échantillon.
                            gA = dropCutGainA(gf - bassSwapF)
                            gB = dropCutGainB(gf - bassSwapF)
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
                        // Swap NET (KIND_EQ / KIND_HARMONIC / KIND_DROP) :
                        // -1 = geste progressif historique ; sinon 0 avant
                        // le « 1 » du swap (sortant plein, entrant coupé),
                        // 1 après (inversion), rampe précalculée entre les
                        // deux (un temps ; un demi-temps pour le drop-swap).
                        val st = if (swapOn) swapT0 + swapStep * i else -1f
                        // ENTRANT (commun aux jonctions fondues) : passe-bas
                        // 2 pôles qui s'ouvre. Au début il n'apporte QUE ses
                        // basses — le sortant garde médiums et aigus, donc
                        // rien ne se superpose ; puis il s'ouvre vers le haut
                        // au moment où le sortant s'efface. Une seule source
                        // par bande. (Coupe nette : aucun traitement,
                        // l'echo-out agit après le mixage.)
                        if (fadeKindF != KIND_CUT && fadeKindF != KIND_DROP) {
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
                                // Long blend : bass swap — NET quand le
                                // « 1 » est calculable : les basses du
                                // sortant tiennent jusqu'au swap puis
                                // cèdent d'un geste, comme un DJ qui
                                // bascule son EQ sur le 1 — puis mid swap :
                                // le sortant se vide bande par bande, façon
                                // EQ 3 bandes d'une table de mixage. Le
                                // kill manuel emprunte le même chemin et
                                // l'emporte sur la coupe automatique (max).
                                val cutA = max(
                                    BASS_SWAP_CUT * (if (st >= 0f) st else bassOut),
                                    killA
                                )
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
                            KIND_DROP -> {
                                // Drop swap : passe-haut montant sur la fin
                                // de la montée (cf. sweepAlpha) — le grave
                                // se dégage pour les basses de l'entrant.
                                a.sweepLpL += sweepAlpha * (aL - a.sweepLpL)
                                a.sweepLpR += sweepAlpha * (aR - a.sweepLpR)
                                vaL = aL - a.sweepLpL
                                vaR = aR - a.sweepLpR
                                if (killA > 0f) {
                                    vaL -= killA * (a.lpL - a.sweepLpL)
                                    vaR -= killA * (a.lpR - a.sweepLpR)
                                }
                            }
                            KIND_EQ -> {
                                // Échange de basses classique, sans filtre :
                                // la transition « table de mixage » sobre —
                                // désormais NETTE quand le « 1 » du swap
                                // est calculable : le sortant garde ses
                                // basses pleines jusqu'au swap puis les
                                // cède d'un geste (rampe d'un temps).
                                // Kill manuel : même chemin, il l'emporte.
                                val cutA = max(
                                    BASS_SWAP_CUT * (if (st >= 0f) st else bassOut),
                                    killA
                                )
                                vaL -= cutA * a.lpL
                                vaR -= cutA * a.lpR
                            }
                            // KIND_CUT : pas de traitement spectral ici,
                            // l'echo-out agit après le mixage.
                        }
                        // Basses de l'ENTRANT : même soustraction one-pole
                        // que le bass swap — son signal garde ses basses
                        // dans tous les cas (le passe-bas d'ouverture les
                        // laisse passer), la soustraction directe est donc
                        // juste. Swap net (KIND_EQ / KIND_HARMONIC) :
                        // coupées AVANT le « 1 » du swap, libérées d'un
                        // geste après — une seule ligne de basse à la
                        // fois, et le drop de l'entrant arrive basses
                        // pleines. Le kill manuel l'emporte toujours (max).
                        val cutB = if (st >= 0f)
                            max(BASS_SWAP_CUT * (1f - st), killB)
                        else killB
                        if (cutB > 0f) {
                            vbL -= cutB * bd.lpL
                            vbR -= cutB * bd.lpR
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
                    // Echo-out : ligne à retard d'un battement, répétitions
                    // qui s'éteignent en feedback. KIND_CUT : nourrie sur
                    // le début du fondu, pendant que l'entrant démarre.
                    // KIND_DROP : nourrie sur le DERNIER temps avant le
                    // drop (la ligne fait un temps) — sa première relecture
                    // tombe pile sur la coupe : le sortant s'éteint sur sa
                    // propre queue d'écho.
                    val eb = echoBuf
                    if (eb != null && inFade) {
                        val n = eb.size / 2
                        val eL = eb[echoPos * 2]
                        val eR = eb[echoPos * 2 + 1]
                        // Drop swap : queue d'écho discrète — elle ne doit
                        // pas brouiller l'attaque du drop de l'entrant.
                        val wet = if (fadeKindF == KIND_DROP) DROP_ECHO_WET else 1f
                        l += eL * wet * master
                        r += eR * wet * master
                        val feeding = if (fadeKindF == KIND_DROP)
                            bassSwapF >= 0L && gf >= bassSwapF - n && gf < bassSwapF
                        else x < 0.3f
                        val feedL = if (feeding) vaL * gA else 0f
                        val feedR = if (feeding) vaR * gA else 0f
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
                // Travail du limiteur pendant la jonction : une lecture par
                // BLOC (jamais par échantillon). Un écrasement marqué
                // s'entend comme une baisse de niveau au milieu du fondu —
                // deux morceaux à plein régime s'additionnent.
                if (fadeStartF >= 0 && framesGlobal >= fadeStartF &&
                    limGain < limMinInFade
                ) {
                    limMinInFade = limGain
                }

                // Verrouillage actif : si les kicks des deux decks dérivent
                // pendant le fade, micro-corriger le rate de l'entrant.
                // (Pas sur un drop swap : le sortant, coupé, ne sert plus
                // de référence.)
                if (fadeActive && bd != null && framesGlobal >= fadeStartF &&
                    fadeKindF != KIND_DROP
                ) {
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
                // Volume maître (minuterie de sommeil) : sur l'AudioTrack,
                // donc à la sortie — posé seulement quand il change.
                val mv = masterVolume
                if (mv != trackVol) {
                    try {
                        audioTrack.setVolume(mv)
                    } catch (_: Exception) {
                    }
                    trackVol = mv
                }
                // Canal changé en cours de lecture (fin du réveil sans
                // geste) : sortie reconstruite au bloc, ce qui était en
                // tampon est réécrit — un accroc de quelques ms, une fois.
                if (outputSwitchRequested) {
                    outputSwitchRequested = false
                    rebuildOutput("changement de canal", playing = true)
                }
                var wrote =
                    audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                if (wrote < 0) {
                    // Sortie audio morte (serveur audio redémarré, route
                    // perdue pendant une longue pause...). Sans ce test, la
                    // boucle tournait à pleine vitesse CPU sur une sortie
                    // muette : le set défilait en silence, sans une trace.
                    // On la reconstruit UNE fois ; si ça ne repart pas, on
                    // s'arrête proprement plutôt que de faire semblant.
                    diag("écriture audio en échec (code $wrote) : sortie reconstruite")
                    djLog("AudioTrack.write a renvoyé $wrote")
                    if (!rebuildOutput("écriture en échec", playing = true)) {
                        diag("sortie audio irrécupérable : set interrompu")
                        running = false
                        break
                    }
                    // Le bloc refusé repart dans la sortie neuve
                    wrote = try {
                        audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                    } catch (_: Exception) {
                        -1
                    }
                }
                if (wrote > 0) recordOut(out)
                recorder?.write(out, BLOCK_FRAMES * 2)
                framesGlobal += BLOCK_FRAMES
                framesGlobalShared = framesGlobal
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
                    bassSwapF = -1L
                    fadeEndF = framesGlobal
                    echoBuf = null
                    // La boucle de sortie manuelle a rempli son office :
                    // la laisser armée mettrait le morceau entrant en
                    // boucle dès ses premières mesures.
                    manualLoopBeats = 0
                    currentPhaseIndex = b.segment.phaseIndex
                    currentSegIndex = b.segIndex
                    announce(b)
                    deckA = rehearsalSkip(b)
                    // Bilan de la jonction qu'on vient d'entendre. Silencieux
                    // quand tout va bien : une ligne n'apparaît QUE s'il y a
                    // eu du son manquant, une sous-alimentation de la sortie
                    // ou un écrasement notable du limiteur.
                    // Les DEUX decks sont mesurés : c'est l'ENTRANT qui
                    // risque le plus la famine (décodeur tout juste
                    // lancé), et ne compter que le sortant rendait un
                    // « famine 0 ms » rassurant pendant que l'arrivant
                    // faisait des trous.
                    val starvedA = if (a.srcSr > 0)
                        (a.starvedFrames - fadeStarvedA) * 1000L / a.srcSr else 0L
                    val starvedB = if (b.srcSr > 0)
                        (b.starvedFrames - fadeStarvedB) * 1000L / b.srcSr else 0L
                    // Boucle de sortie du sortant SOUS le fondu : la même
                    // cellule qui se répète s'entend comme un saut.
                    val loopedMs =
                        (a.loopTotalOut - fadeLoopedA) * 1000L / OUT_SR
                    val under = (audioTrack.underrunCount - fadeUnderrunAt).coerceAtLeast(0)
                    underrunsSeen += under
                    val squashDb = if (limMinInFade < 0.999f)
                        20.0 * kotlin.math.log10(limMinInFade.toDouble()) else 0.0
                    if (starvedA > 5L || starvedB > 5L || loopedMs > 100L ||
                        under > 0 || squashDb < -1.0
                    ) {
                        diag(
                            "jonction terminée avec : " +
                                "famine sortant ${starvedA} ms, " +
                                "famine entrant ${starvedB} ms, " +
                                "boucle de sortie ${loopedMs} ms, " +
                                "sous-alimentations $under, " +
                                "limiteur ${"%.1f".format(squashDb)} dB"
                        )
                    }
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
            // Bilan du set : le total des sous-alimentations dit d'un coup
            // d'œil si la sortie a manqué de son pendant l'écoute, et la
            // part tombée pendant les jonctions si c'est là que ça a lâché.
            val totalUnder = try {
                audioTrack.underrunCount
            } catch (_: Exception) {
                -1
            }
            diag(
                "set ${if (natural) "terminé" else "arrêté"} : " +
                    "$totalUnder sous-alimentation(s) de la sortie, " +
                    "dont $underrunsSeen en jonction"
            )
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

    /**
     * Journal de marche du moteur DJ : écrit dans le MÊME fichier que
     * PlayerCore (service_log.txt), pour que l'export « Journal » montre
     * les transitions DJ à leur place dans la chronologie — jusqu'ici il
     * n'y avait AUCUNE trace des jonctions DJ, seulement des pannes.
     * L'écriture part sur le fil d'entrées-sorties de PlayerCore : le
     * thread audio n'attend rien.
     */
    private fun diag(message: String) {
        try {
            PlayerCore.engineLog("DJ", message)
        } catch (_: Exception) {
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
        // Passage joué : mémorisé pour la rotation du prochain set, et
        // journalisé (rôle, bornes, nombre de passages que le morceau offre).
        try {
            val pas = deck.segment.passage ?: Passages.fort(t)
            com.pulsemix.app.data.PlayHistory.recordPassage(t.uri, pas.role.name)
            val known = 1 + Passages.candidates(t).size
            diag(
                "passage « ${t.title} » : ${pas.role.label} " +
                    "(${Passages.describe(pas)}, $known connu${if (known > 1) "s" else ""})"
            )
        } catch (_: Exception) {
        }
        // Retardé de la latence du tampon de sortie : le morceau annoncé
        // est celui qu'on entend, pas celui qu'on vient de calculer. À la
        // livraison, le set a pu être arrêté (et un autre mode lancé) :
        // l'annonce d'un set mort écrasait le morceau courant du nouveau.
        // Après une purge du tampon (coupe franche), ce qu'on calcule EST
        // ce qu'on entend : annonce immédiate.
        val sinceFlush = framesGlobalNow() - outFlushedAtFrame
        val delay = if (outFlushedAtFrame >= 0L && sinceFlush * 1000L / OUT_SR < outLatencyMs) 0L
        else outLatencyMs
        ui.postDelayed({
            if (running && gen == runGeneration) listener.onTrackChanged(t, p)
        }, delay)
    }

    /** Position de sortie (frames) au moment de la dernière purge du
     *  tampon (coupe franche) ; -1 = jamais. */
    @Volatile private var outFlushedAtFrame = -1L
    /** Position de sortie courante, vue hors de la boucle (annonce). */
    @Volatile private var framesGlobalShared = 0L
    private fun framesGlobalNow(): Long = framesGlobalShared

    // computeRate et fadeSpec : voir le companion object (fonctions pures,
    // internal, testées en JVM).
}
