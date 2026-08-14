package com.pulsemix.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.data.Rule
import com.pulsemix.app.library.SongRecognizer
import com.pulsemix.app.PlayerViewModel
import kotlinx.coroutines.delay

/** En-tête d'un sous-écran : retour + titre. Partagé par tous les
 *  sous-écrans de la bibliothèque (playlists, tags, stats, tonalités…). */
@Composable
fun SubHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, "Retour")
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Écran de choix des playlists : charger (= lire comme file de lecture),
 * exporter en M3U, supprimer. La création se fait depuis la file de lecture
 * (« Enregistrer… ») ou un résultat de recherche (« En playlist… »).
 */
@Composable
fun PlaylistsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    // Suppression sur confirmation : un simple tap de croix effaçait la
    // playlist sans retour possible — action définitive, comme les morceaux.
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // Playlists intelligentes : suppression confirmée + dialogue de création
    val smartPlaylists by vm.smartPlaylists.collectAsStateWithLifecycle()
    var deleteSmartTarget by remember { mutableStateOf<String?>(null) }
    var showNewRule by remember { mutableStateOf(false) }

    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer la playlist « $name » ?") },
            text = {
                Text(
                    "La playlist sera supprimée — c'est définitif. Les " +
                        "morceaux, eux, restent dans la bibliothèque."
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.deletePlaylist(name)
                    deleteTarget = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Annuler") }
            }
        )
    }

    deleteSmartTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteSmartTarget = null },
            title = { Text("Supprimer la règle « $name » ?") },
            text = {
                Text(
                    "La playlist intelligente sera supprimée — c'est " +
                        "définitif. Les morceaux, eux, ne bougent pas."
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.removeSmartPlaylist(name)
                    deleteSmartTarget = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSmartTarget = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (showNewRule) {
        NewSmartRuleDialog(
            existingNames = smartPlaylists.map { it.name }.toSet(),
            onDismiss = { showNewRule = false },
            onCreate = { name, rule ->
                vm.addSmartPlaylist(name, rule)
                showNewRule = false
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Playlists", onBack)
        Spacer(Modifier.height(8.dp))
        if (playlists.isEmpty()) {
            Text(
                "Aucune playlist pour l'instant.\n\n" +
                    "Pour en créer une :\n" +
                    "• depuis le lecteur : ouvre la file de lecture puis " +
                    "« Enregistrer… » ;\n" +
                    "• depuis la bibliothèque : fais une recherche puis " +
                    "« En playlist… ».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            Text(
                "Appuie sur une playlist pour la charger comme file de lecture.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            // Résultat RÉEL de l'export (chemin créé, ou erreur) : l'ancien
            // message générique confirmait même quand l'écriture échouait.
            val exportMsg by vm.playlistExportMessage.collectAsStateWithLifecycle()
            exportMsg?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (msg.contains("échoué") || msg.contains("impossible"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                // Le message confirme puis s'efface : il ne doit pas
                // s'installer entre le titre et la liste.
                LaunchedEffect(msg) {
                    delay(6_000)
                    vm.playlistExportMessage.value = null
                }
            }
            Spacer(Modifier.height(4.dp))
            // weight : laisse la place à la section « Playlists
            // intelligentes » en bas, même avec une longue liste.
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(playlists, key = { it.name }) { pl ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.playPlaylist(pl); onBack() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${pl.uris.size} morceaux",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = { vm.playPlaylist(pl); onBack() }) {
                            Icon(Icons.Rounded.PlayArrow, "Lire")
                        }
                        TextButton(onClick = {
                            vm.exportPlaylist(pl)
                        }) { Text("m3u") }
                        IconButton(onClick = { deleteTarget = pl.name }) {
                            Icon(
                                Icons.Rounded.Close, "Supprimer",
                                tint = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.5f)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }

        // ------------------------------------------ playlists intelligentes
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Playlists intelligentes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showNewRule = true }) {
                Text("Nouvelle règle…")
            }
        }
        if (smartPlaylists.isEmpty()) {
            Text(
                "Des règles (BPM, genre, énergie…) évaluées sur la " +
                    "bibliothèque au moment de jouer.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            Text(
                "Appuie sur une règle pour lancer la sélection du moment.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(smartPlaylists, key = { it.name }) { sp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.playSmartPlaylist(sp.name)
                                onBack()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                sp.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                ruleSummary(sp.rule),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { deleteSmartTarget = sp.name }) {
                            Icon(
                                Icons.Rounded.Close, "Supprimer",
                                tint = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.5f)
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

/** Résumé lisible d'une règle : « BPM 120-130 · énergie ≥ 0,5 · pas jouée
 *  depuis 30 j ». */
private fun ruleSummary(r: Rule): String {
    val parts = ArrayList<String>()
    val min = r.minBpm?.toInt()
    val max = r.maxBpm?.toInt()
    when {
        min != null && max != null -> parts.add("BPM $min-$max")
        min != null -> parts.add("BPM ≥ $min")
        max != null -> parts.add("BPM ≤ $max")
    }
    r.genre?.let { parts.add(it) }
    r.minEnergy?.let { parts.add("énergie ≥ ${frDecimal(it)}") }
    r.maxEnergy?.let { parts.add("énergie ≤ ${frDecimal(it)}") }
    r.notPlayedDays?.let { parts.add("pas jouée depuis $it j") }
    if (r.favoritesOnly == true) parts.add("favoris seulement")
    return if (parts.isEmpty()) "tous les morceaux" else parts.joinToString(" · ")
}

/** 0.5f → « 0,5 » (virgule française). */
private fun frDecimal(v: Float): String =
    String.format(java.util.Locale.FRENCH, "%.1f", v)

/**
 * Création d'une playlist intelligente : nom + critères optionnels (vides =
 * pas de filtre), construit une [Rule] et la remonte via [onCreate].
 */
@Composable
private fun NewSmartRuleDialog(
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, Rule) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var minBpm by remember { mutableStateOf("") }
    var maxBpm by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var useEnergy by remember { mutableStateOf(false) }
    var minEnergy by remember { mutableFloatStateOf(0.5f) }
    var notPlayed by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle règle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Chaque critère est optionnel : vide = pas de filtre.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nom") },
                    singleLine = true,
                    // Créer sous un nom déjà pris REMPLACE la règle
                    // existante : prévenir plutôt que d'écraser en silence.
                    supportingText = if (name.trim() in existingNames) {
                        { Text("Ce nom existe déjà : la règle sera remplacée.") }
                    } else null
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minBpm,
                        onValueChange = { minBpm = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("BPM min") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard
                    )
                    OutlinedTextField(
                        value = maxBpm,
                        onValueChange = { maxBpm = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("BPM max") },
                        singleLine = true,
                        keyboardOptions = numberKeyboard
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Genre") },
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { useEnergy = !useEnergy }
                ) {
                    Checkbox(
                        checked = useEnergy,
                        onCheckedChange = { useEnergy = it }
                    )
                    Text(
                        if (useEnergy) "Énergie min : ${frDecimal(minEnergy)}"
                        else "Filtrer sur l'énergie",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (useEnergy) {
                    Slider(
                        value = minEnergy,
                        onValueChange = { minEnergy = it },
                        valueRange = 0f..1f
                    )
                }
                OutlinedTextField(
                    value = notPlayed,
                    onValueChange = { notPlayed = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pas joué depuis (jours)") },
                    singleLine = true,
                    keyboardOptions = numberKeyboard
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { favoritesOnly = !favoritesOnly }
                ) {
                    Switch(
                        checked = favoritesOnly,
                        onCheckedChange = { favoritesOnly = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Favoris seulement",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(
                        name.trim(),
                        Rule(
                            minBpm = minBpm.trim().toFloatOrNull(),
                            maxBpm = maxBpm.trim().toFloatOrNull(),
                            genre = genre.trim().takeIf { it.isNotBlank() },
                            minEnergy = if (useEnergy) minEnergy else null,
                            notPlayedDays = notPlayed.trim().toIntOrNull(),
                            favoritesOnly = if (favoritesOnly) true else null
                        )
                    )
                }
            ) { Text("Créer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

/**
 * Écran de gestion des tags en ligne : lancement de la recherche
 * (bibliothèque entière), progression, et liste des propositions
 * incertaines à valider une par une.
 */
@Composable
fun TagsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val pending by vm.tagPending.collectAsStateWithLifecycle()
    val prog by vm.tagProgress.collectAsStateWithLifecycle()
    val tagError by vm.tagError.collectAsStateWithLifecycle()
    val appliedList by vm.tagApplied.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    // Afficher aussi les corrections déjà faites dans la liste, pour
    // pouvoir les revoir / re-corriger
    var showFixed by remember { mutableStateOf(false) }
    // Recherche manuelle : depuis une proposition incertaine, ou depuis
    // n'importe quel morceau via le sélecteur ci-dessous
    var manualFor by remember { mutableStateOf<com.pulsemix.app.data.Track?>(null) }
    // Vrai quand la recherche doit se lancer dès l'ouverture (bouton
    // « Chercher » d'une proposition incertaine)
    var manualAuto by remember { mutableStateOf(false) }
    // Message des lignes de correction dont le morceau a quitté la
    // bibliothèque (suggestions persistées, fichier supprimé depuis)
    var missingTrackMsg by remember { mutableStateOf<String?>(null) }
    var pickTrack by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val writeToFiles by vm.writeTagsToFiles.collectAsStateWithLifecycle()
    val resetMessage by vm.tagResetMessage.collectAsStateWithLifecycle()

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Tout remettre à zéro ?") },
            text = {
                val surLesFichiers = if (writeToFiles) {
                    "Comme l'option « écrire les tags dans les fichiers » est " +
                        "active, les fichiers eux-mêmes sont réécrits avec " +
                        "leurs tags d'origine. C'est long : un fichier est " +
                        "recopié par correction à défaire."
                } else {
                    "Les fichiers audio ne sont pas touchés."
                }
                Text(
                    "Toutes les corrections de tags sont effacées — celles " +
                        "trouvées automatiquement comme celles que tu as " +
                        "faites à la main. Chaque morceau retrouve le titre " +
                        "et l'artiste inscrits dans son fichier.\n\n" +
                        surLesFichiers + "\n\n" +
                        "Les analyses (BPM, tonalité, meilleur passage) sont " +
                        "conservées : rien à réanalyser."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetAllTags()
                }) {
                    Text("Tout remettre à zéro", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Annuler") }
            }
        )
    }

    resetMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearTagResetMessage() },
            title = { Text("Remise à zéro terminée") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.clearTagResetMessage() }) { Text("OK") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Tags en ligne", onBack)
        Spacer(Modifier.height(4.dp))
        // Une ligne, pas un mode d'emploi : la place appartient aux listes.
        Text(
            "Empreinte sonore puis recherche texte. Sûr : appliqué ; " +
                "douteux : à valider.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))

        val coverProg by vm.coverProgress.collectAsStateWithLifecycle()
        val writeProg by vm.tagWriteProgress.collectAsStateWithLifecycle()
        val p = prog
        when {
            // Un passage en cours prend la zone d'actions : progression et
            // Stop, rien d'autre — un seul passage tourne à la fois.
            p != null -> {
                val (done, total, applied) = p
                val resetting by vm.tagResetting.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (resetting) "Remise à zéro : $done/$total — $applied rétablis"
                        else "Recherche : $done/$total — $applied corrigés auto",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { vm.stopTagFetch() }) { Text("Stop") }
                }
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            coverProg != null -> {
                val (cDone, cTotal) = coverProg!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Jaquettes : $cDone/$cTotal",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { vm.stopTagFetch() }) { Text("Stop") }
                }
                LinearProgressIndicator(
                    progress = { if (cTotal > 0) cDone.toFloat() / cTotal else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Au repos : deux rangées de boutons de même famille, libellés
            // courts, largeurs égales — l'empilement de styles dépareillés
            // repoussait les listes hors de l'écran.
            else -> {
                val done by vm.tagChecked.collectAsStateWithLifecycle()
                val remaining = (tracks.size - done).coerceAtLeast(0)
                // Pendant le report des tags dans les fichiers (réglages),
                // le service refuserait un second passage : ne pas le promettre
                val canRun = tracks.isNotEmpty() && writeProg == null
                // Deux actions principales côte à côte, jamais repliées…
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.fetchTagsAll() },
                        enabled = remaining > 0 && canRun,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (done > 0) "Vérifier ($remaining)"
                            else "Vérifier tout",
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { vm.fetchAllCovers() },
                        enabled = canRun,
                        modifier = Modifier.weight(1f)
                    ) { Text("Jaquettes", maxLines = 1) }
                }
                // …et les secondaires à leur taille naturelle, sur une seule
                // ligne qui défile : des largeurs forcées repliaient les
                // libellés en pilules difformes de hauteurs inégales.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { pickTrack = true },
                        enabled = tracks.isNotEmpty()
                    ) { Text("Corriger un morceau…", maxLines = 1) }
                    if (done > 0) {
                        OutlinedButton(
                            onClick = { vm.recheckAllTags() },
                            enabled = canRun
                        ) { Text("Tout revérifier", maxLines = 1) }
                    }
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        enabled = tracks.isNotEmpty()
                    ) {
                        Text(
                            "Remise à zéro",
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    "~1 morceau/s, continue en arrière-plan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        val coverMsg by vm.coverMessage.collectAsStateWithLifecycle()
        coverMsg?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.clearCoverMessage() }) { Text("OK") }
            }
        }
        missingTrackMsg?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            LaunchedEffect(msg) {
                delay(4_000)
                missingTrackMsg = null
            }
        }
        tagError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "À valider (${pending.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (pending.isNotEmpty()) {
                TextButton(onClick = { pending.forEach { vm.rejectTag(it) } }) {
                    Text("Tout refuser", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        // Toggle : montrer aussi les corrections déjà appliquées, pour les
        // vérifier à l'oreille et les refaire si besoin
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { showFixed = !showFixed }
        ) {
            Switch(checked = showFixed, onCheckedChange = { showFixed = it })
            Spacer(Modifier.width(8.dp))
            Text(
                "Afficher les corrigés (${appliedList.size})",
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (pending.isEmpty() && !(showFixed && appliedList.isNotEmpty())) {
            Text(
                "Rien à valider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            // Clés par OCCURRENCE pour la liste des corrigés (même approche
            // que la file de lecture) : une clé d'index ("a:$i") faisait
            // changer de clé toutes les lignes suivantes à chaque retrait,
            // et l'URI seul dupliquerait la clé d'un morceau corrigé deux
            // fois. L'URI, suffixé seulement pour les doublons, donne les
            // deux : stabilité et unicité.
            val appliedKeys = remember(appliedList) {
                val seen = HashMap<String, Int>()
                appliedList.map { s ->
                    val n = (seen[s.uri] ?: 0) + 1
                    seen[s.uri] = n
                    if (n == 1) "a:" + s.uri else "a:${s.uri}#$n"
                }
            }
            LazyColumn {
                items(pending, key = { "p:" + it.uri }) { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            TagChangeTexts(s)
                            Row {
                                TextButton(onClick = { vm.acceptTag(s) }) {
                                    Text("Accepter")
                                }
                                TextButton(onClick = { vm.rejectTag(s) }) {
                                    Text(
                                        "Refuser",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                // Ni l'un ni l'autre : voir la liste des
                                // possibilités et choisir le bon résultat
                                TextButton(onClick = {
                                    // Morceau disparu de la bibliothèque :
                                    // sans message, le bouton semblait mort
                                    // (et manualAuto restait armé pour rien)
                                    val t = tracks.find { it.uri == s.uri }
                                    if (t != null) {
                                        manualAuto = true
                                        manualFor = t
                                    } else missingTrackMsg =
                                        "Ce morceau n'est plus dans la bibliothèque."
                                }) { Text("Chercher") }
                            }
                        }
                        // Écouter le morceau pour vérifier l'artiste
                        IconButton(onClick = {
                            val t = tracks.find { it.uri == s.uri }
                            if (t != null) vm.playTrack(t)
                            else missingTrackMsg =
                                "Ce morceau n'est plus dans la bibliothèque."
                        }) { Icon(Icons.Rounded.PlayArrow, "Écouter") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                if (showFixed && appliedList.isNotEmpty()) {
                    item(key = "fixed-header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text(
                                "Corrigés (${appliedList.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.clearTagApplied() }) {
                                Text(
                                    "Vider",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    itemsIndexed(appliedList, key = { i, _ -> appliedKeys[i] }) { _, s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                TagChangeTexts(s)
                                Row {
                                    // Correction douteuse ? Revoir la liste
                                    // des possibilités, en choisir une autre.
                                    TextButton(onClick = {
                                        val t = tracks.find { it.uri == s.uri }
                                        if (t != null) {
                                            manualAuto = true
                                            manualFor = t
                                        } else missingTrackMsg =
                                            "Ce morceau n'est plus dans la bibliothèque."
                                    }) { Text("Chercher") }
                                    // Ou revenir aux tags d'avant correction
                                    TextButton(onClick = { vm.revertTag(s) }) {
                                        Text(
                                            "Rétablir l'original",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = {
                                val t = tracks.find { it.uri == s.uri }
                                if (t != null) vm.playTrack(t)
                                else missingTrackMsg =
                                    "Ce morceau n'est plus dans la bibliothèque."
                            }) { Icon(Icons.Rounded.PlayArrow, "Écouter") }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------- recherche manuelle depuis la liste
    manualFor?.let { t ->
        ManualTagDialog(vm, t, autoSearch = manualAuto, onClose = {
            manualFor = null
            manualAuto = false
        })
    }

    // -------------------------- choisir n'importe quel morceau à corriger
    if (pickTrack) {
        var filter by remember { mutableStateOf("") }
        val filtered = remember(filter, tracks) {
            val q = filter.trim()
            (if (q.isBlank()) tracks
            else tracks.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
            }).take(60)
        }
        AlertDialog(
            onDismissRequest = { pickTrack = false },
            title = { Text("Quel morceau corriger ?") },
            text = {
                Column {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Filtrer (titre ou artiste)") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.uri }) { t ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pickTrack = false
                                        manualAuto = false
                                        manualFor = t
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    t.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (t.artist.isNotBlank()) {
                                    Text(
                                        t.artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                            .copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickTrack = false }) { Text("Fermer") }
            }
        )
    }
}

/** Lignes « ancien — artiste » puis « → nouveau — artiste » d'une correction. */
@Composable
private fun TagChangeTexts(s: com.pulsemix.app.library.TagFixer.Suggestion) {
    Text(
        listOf(s.oldTitle, s.oldArtist)
            .filter { it.isNotBlank() }
            .joinToString(" — "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        "→ " + listOf(s.newTitle, s.newArtist)
            .filter { it.isNotBlank() }
            .joinToString(" — "),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Écran « Importer depuis une URL » :
 *  - colle un lien (YouTube/SoundCloud/Bandcamp, fichier audio direct, item
 *    Internet Archive, flux podcast RSS) ; ou
 *  - cherche directement sur YouTube et télécharge un résultat d'un tap.
 * L'audio arrive dans le dossier scanné par le lecteur, puis est analysé.
 * En bas, « Mettre à jour yt-dlp » récupère la dernière version du moteur
 * en temps réel (utile quand YouTube change son site et casse l'extraction).
 *
 * Note affichée : la récupération et l'usage des contenus relèvent de la
 * responsabilité de l'utilisateur.
 */
@Composable
fun ImportUrlScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val state by vm.importState.collectAsStateWithLifecycle()
    val results by vm.ytResults.collectAsStateWithLifecycle()
    val searching by vm.ytSearching.collectAsStateWithLifecycle()
    val searchError by vm.ytSearchError.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val hasFolder = vm.hasFolder()
    val working = state is com.pulsemix.app.library.UrlImporter.State.Working

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Importer depuis une URL", onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "Colle un lien (YouTube, SoundCloud, Bandcamp, fichier audio, " +
                "Internet Archive, podcast RSS) ou cherche sur YouTube. " +
                "L'audio est téléchargé en MP3 dans ton dossier de musique, " +
                "puis analysé. Tu es responsable de ce que tu télécharges " +
                "et de son usage.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(10.dp))

        if (!hasFolder) {
            Text(
                "Choisis d'abord un dossier de musique dans la bibliothèque : " +
                    "c'est là que les fichiers importés seront rangés.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }

        // ------------------------------------------------------ lien direct
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL") },
            placeholder = { Text("https://…") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = androidx.compose.foundation.layout
                .Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { vm.importFromUrl(url) },
                enabled = url.isNotBlank() && !working
            ) { Text("Importer") }
            if (working) {
                OutlinedButton(onClick = { vm.stopImport() }) { Text("Stop") }
            }
        }

        // ----------------------------------------------- état de l'import
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            is com.pulsemix.app.library.UrlImporter.State.Working -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = {
                        if (s.total > 0) s.done.toFloat() / s.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is com.pulsemix.app.library.UrlImporter.State.Done -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { url = ""; vm.resetImport() }) {
                    Text("Importer un autre lien")
                }
            }
            is com.pulsemix.app.library.UrlImporter.State.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            else -> {}
        }

        // ------------------------------------------------ recherche YouTube
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Chercher sur YouTube") },
            placeholder = { Text("titre, artiste…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { vm.searchYoutube(query) }
            ),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (searching) vm.cancelYoutubeSearch()
                        else vm.searchYoutube(query)
                    },
                    enabled = searching || query.isNotBlank()
                ) {
                    Icon(
                        if (searching) Icons.Rounded.Close
                        else Icons.Rounded.Search,
                        if (searching) "Annuler" else "Chercher"
                    )
                }
            }
        )

        // --------------------------- reconnaissance « à la Shazam »
        val recogState by vm.songRecState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) vm.startSongRecognition()
            // Refus (ou refus définitif : Android ne montre même plus le
            // dialogue) : sans ce message, le bouton semblait mort.
            else SongRecognizer.state.value = SongRecognizer.State.Error(
                "Micro refusé — autorise-le dans les réglages Android " +
                    "(Applications > PulseMix > Autorisations)."
            )
        }
        // Titre identifié : remplir le champ et lancer la recherche YouTube
        LaunchedEffect(recogState) {
            val rs = recogState
            if (rs is SongRecognizer.State.Found) {
                query = listOf(rs.artist, rs.title)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                vm.resetSongRecognition()
                vm.searchYoutube(query)
            }
        }
        when (val rs = recogState) {
            is SongRecognizer.State.Listening -> {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (rs.searching)
                            "Identification… (${rs.elapsedSec} s écoutées)"
                        else "J'écoute… ${rs.elapsedSec} s",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { vm.stopSongRecognition() }) {
                        Text("Stop")
                    }
                }
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is SongRecognizer.State.NotFound,
            is SongRecognizer.State.Error -> {
                val message = (rs as? SongRecognizer.State.NotFound)?.message
                    ?: (rs as SongRecognizer.State.Error).message
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.resetSongRecognition() }) {
                        Text("OK")
                    }
                }
            }
            else -> {
                TextButton(onClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.startSongRecognition()
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(Icons.Rounded.Mic, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reconnaître ce qui joue")
                }
            }
        }
        if (searching) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        searchError?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.videoId }) { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Un tap sur la ligne remplit le champ URL (pour
                        // vérifier le lien avant de lancer, si on veut).
                        .clickable { url = r.url }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOf(r.channel, r.durationText)
                                .filter { it.isNotBlank() }
                                .joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.6f)
                        )
                    }
                    // Téléchargement direct du résultat, sans coller de lien
                    IconButton(
                        onClick = {
                            url = r.url
                            vm.importFromUrl(r.url)
                        },
                        enabled = !working
                    ) {
                        Icon(Icons.Rounded.Download, "Télécharger")
                    }
                }
                HorizontalDivider()
            }
        }

        // --------------------------------------- mise à jour du moteur
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { vm.updateImportEngine() },
                enabled = !working
            ) { Text("Mettre à jour yt-dlp") }
            Text(
                "si YouTube casse l'extraction",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}
