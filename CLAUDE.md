# Consignes pour Claude

## Workflow git (par défaut)

Après chaque modification :
1. Commit et push sur la branche de travail.
2. Merge de cette branche dans `main`, puis push de `main`.

Pas besoin de demander confirmation pour le merge dans `main`.

## Lien de téléchargement (toujours)

Le push sur `main` déclenche le workflow « Build APK ». Attendre la fin du
build, puis donner systématiquement le lien direct de l'artefact :
`https://github.com/fmoraldo-mithraw/pulsemix/actions/runs/<run_id>/artifacts/<artifact_id>`
(ids via `gh api repos/fmoraldo-mithraw/pulsemix/actions/runs/<run_id>/artifacts`).
Si le build échoue : le dire, corriger, et recommencer jusqu'au lien.
