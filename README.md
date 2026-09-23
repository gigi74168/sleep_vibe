# Sleep Track

> 🤖 Vibecodé avec [Claude Code](https://claude.com/claude-code) — la quasi-totalité du code a été générée par IA.

Application Android qui affiche une année de données de santé sous forme de grille façon GitHub :
plus la nuit est longue (ou la journée active), plus la case est verte ; plus elle est courte, plus elle tire vers le rouge.

Les données viennent de [Health Connect](https://health.google/health-connect-android/) et restent sur le téléphone : l'app ne demande pas la permission `INTERNET`, n'envoie rien nulle part et n'écrit dans aucun stockage externe.

À compter de la 3.0, plus rien ne se télécharge au démarrage. Les versions 2.4 à 2.7.1 faisaient
exception : la bibliothèque ML Kit des commentaires Gemini Nano ajoutait d'elle-même `INTERNET`
et un service de télémétrie au manifeste — elle est retirée, et avec elle ces deux ajouts.
Retiré aussi l'initialiseur d'EmojiCompat, qui adressait au démarrage un fournisseur de polices
pour télécharger des émojis que l'app n'affiche jamais.

L'app refuse aussi la sauvegarde automatique d'Android (`allowBackup="false"` et
`data_extraction_rules.xml`), qui recopierait sinon l'historique de santé vers le Google Drive du
téléphone. Les données ne sortent que par l'export, quand on le demande.

## Fonctionnalités

- **Navigation par onglets** en bas de l'écran : Accueil, Grilles, Réglages.
- **Deux thèmes** (voir [Thèmes](#thèmes)) : **Aube**, clair et chaud, avec sa variante sombre
  **Nuit tombée**, et **Classique**, l'ancien thème sombre.
- **Accueil** : le score de récupération du matin, avec ses composantes, puis des bandes des
  dernières semaines pour les métriques choisies dans les réglages. Les cases des bandes se
  touchent directement : une case ouvre le détail de son jour, « Année › » ouvre sa grille
  complète.
- **Score de récupération** (0-100, voir [Score de récupération](#score-de-récupération)) :
  sommeil, variabilité cardiaque (VFC) et pas de la veille, comparés à ta propre moyenne. Chaque
  composante se désactive séparément dans les réglages ; le score est la moyenne de celles qui
  restent actives.
- Grille annuelle pour quatre métriques — sommeil, récupération, pas, temps d'écran — navigation par année, détail d'une journée au toucher.
- Sommeil : sessions Health Connect, stades « éveillé » déduits, chevauchements entre montre et téléphone fusionnés, nuit rattachée à la date du réveil.
- Pas : agrégation quotidienne Health Connect.
- Statistiques par métrique : moyenne, 7 derniers jours, record, tendance sur 30 jours.
- **Objectifs réglables** : sommeil, pas, temps d'écran et seuil « au vert » de récupération se
  règlent dans les réglages et pilotent à la fois les couleurs des grilles et les séries.
- **Séries** : jours consécutifs du bon côté de l'objectif, série en cours et record de l'année.
  Un jour sans donnée coupe la série.
- **Semaine type** : moyenne de chaque jour de la semaine, avec le jour le plus haut et le plus bas.
  Les barres se comparent entre elles et non à zéro, sans quoi l'écart réel serait invisible.
- **Temps d'écran** : cinquième grille, lue dans les statistiques d'utilisation d'Android (Health
  Connect n'en a pas). Compte le temps où l'écran est allumé **et** déverrouillé — un peu plus que
  Bien-être numérique, qui ne compte pas l'écran d'accueil. Android ne garde qu'une dizaine de jours
  d'événements : l'historique commence là et s'allonge dans l'archive à chaque ouverture de l'app.
  La journée en cours n'apparaît qu'une fois terminée.
- **Activité et sommeil** : nuage de points et coefficient de corrélation entre les pas d'une journée
  et la nuit qui la suit, avec la comparaison « après 8 000 pas ou plus » contre « après une journée calme ».
- **Rappels** (optionnels) : rappel du soir quand la moyenne des 7 derniers jours passe sous l'objectif,
  et résumé du dimanche comparant la semaine à la précédente. Calculés sur le téléphone.
- **Widget** d'écran d'accueil : les dernières semaines de la métrique choisie dans les réglages,
  redimensionnable de 4x2 jusqu'à 2x1 (l'en-tête s'efface quand la tuile est trop plate).
  Pas de rafraîchissement périodique : il est redessiné quand ses données changent, et au
  passage à minuit par une alarme qui ne réveille pas le téléphone.
- **Partage** : export de la grille de l'année en PNG, via le sélecteur de partage Android, en
  SD (3840 px de large, 4K) ou en HD (15 360 px, 16K). La 16K est dessinée et compressée par
  bandes, sans jamais tenir l'image entière en mémoire : compter quelques secondes de préparation.
- **Métriques masquables** : sommeil, récupération, pas et temps d'écran se retirent chacun du
  menu principal depuis les réglages (il en reste toujours au moins une visible) ; leurs
  autorisations (Health Connect comme l'accès aux données d'utilisation) ne sont alors plus
  réclamées.
- **Réglages détaillés** : à peu près tout se règle séparément — les composantes du score de
  récupération et leur période de référence, les quatre objectifs, ce que montre l'accueil (carte,
  jauge, détail des composantes, quelles bandes, leur hauteur, le détail au toucher, l'onglet
  d'ouverture), les métriques visibles, et les affichages des grilles. Un bouton « Rétablir les
  réglages par défaut » remet tout à zéro sans toucher aux données.
- **Sauvegarde JSON** : export et import d'un fichier lisible tel quel, une ligne par jour
  (voir [Format de sauvegarde](#format-de-sauvegarde)), plus un export CSV pour les tableurs.
  L'app tient son propre historique, alimenté par ce qu'elle lit dans Health Connect et par ce qui
  est importé ; il survit donc à un changement de téléphone ou à la rétention de Health Connect.
- **Affichage réglable** : statistiques, séries, semaine type, légende, panneau « activité et
  sommeil » et commentaires s'activent séparément ; la taille des cases se choisit, et la grille
  peut remplir la hauteur de l'écran quand le téléphone passe à l'horizontale (elle défile alors
  latéralement).
- **Rotation indolore** : tourner ou redimensionner le téléphone ne relance pas le chargement —
  l'année, la case choisie et les données restent en place.
- Mode démo si Health Connect n'est pas disponible.

## Thèmes

Réglages › Apparence : **Thème** Aube (par défaut) ou Classique, et **Mode** Système (par défaut),
Clair ou Sombre. Le mode ne concerne qu'Aube : Clair affiche Aube, Sombre affiche Nuit tombée,
Système suit le thème sombre d'Android. Le choix part dans l'export JSON et revient au défaut avec
« Rétablir les réglages par défaut ».

- **Aube** : fond crème, cartes blanches à l'ombre douce (un filet en Nuit tombée), accent
  terracotta. Titres, score et verdict en Young Serif, le reste en Figtree avec des chiffres à
  chasse fixe. Les deux polices sont embarquées (licences OFL dans `assets/licenses/`) : rien
  n'est téléchargé.
- **Pastilles** : en Aube, chaque jour est une pastille ronde dont le diamètre suit le niveau
  (de 0,56 à 1 fois la cellule), second codage pour qui distingue mal les couleurs. Les deux
  niveaux les plus bas ont un anneau, un jour sans donnée n'est qu'un anneau vide, un jour à venir
  un petit point. Le toucher reste lu sur la cellule carrée entière.
- **Lever de soleil** : la jauge de récupération devient un arc sur l'horizon, rempli jusqu'au
  score, le soleil posé au bout et un trait au seuil « au vert ». Il se lève (1,1 s) au premier
  affichage du jour.
- **Classique** garde exactement le rendu d'avant : cases carrées, jauge circulaire, typographie
  système.

Le widget suit le thème ; sur Android 12 et plus, en mode Système, il bascule seul entre Aube et
Nuit tombée. L'image de partage d'Aube garde toujours le fond clair.

## Échelles de couleurs

| Métrique | ← moins bien | | | | mieux → |
| --- | --- | --- | --- | --- | --- |
| **Sommeil** | < 5h | 5-6h | 6-7h | 7-8h | 8h+ |
| **Récupération** | < 34 | 34-50 | 50-67 | 67-80 | 80+ |
| **Pas** | < 3k | 3-6k | 6-8k | 8-10k | 10k+ |
| **Temps d'écran** | 5h+ | 4-5h | 3-4h | 2-3h | < 2h |

Ces seuils sont ceux des objectifs par défaut (7 h de sommeil, 10 000 pas, 3 h d'écran, 67 de
récupération) ; ils suivent les objectifs réglés dans les réglages. Les couleurs viennent du thème :
du rouge au vert en Classique ; en Aube, du rose au vert sapin, plus foncées à mesure que le jour
est bon (plus claires en Nuit tombée).

## Score de récupération

Un score par matin, rattaché comme la nuit à la date du réveil : la **moyenne des composantes
actives**, chacune notée sur 100. Une composante se désactive séparément dans les réglages ; le
score n'utilise alors que celles qui restent.

| Composante | Note |
| --- | --- |
| Sommeil | durée de la nuit : 4 h ou moins sous l'objectif → 0, 1 h au-dessus → 100 |
| VFC (RMSSD) | écart à ta moyenne des jours précédents, en écarts-types (sur le logarithme) : dans la moyenne → 60, deux écarts-types au-dessus → 100 |
| Pas de la veille | charge : jusqu'à 1,2 fois ta moyenne des jours précédents → 100, puis baisse jusqu'à 40 au double |

- La VFC se compare à ta propre moyenne, qui demande 7 jours de relevés sur la période de
  référence (14, 28 ou 56 jours, réglable) : une VFC de 40 ms est excellente pour l'un, basse pour
  l'autre.
- Il faut au moins deux composantes actives pour obtenir un score (une seule composante active
  suffit si c'est la seule qui reste).
- La VFC est lue dans Health Connect (enregistrée par la montre pendant la nuit) ; un relevé pris
  après 18 h compte pour la nuit qui suit. Elle sert seulement au score, sans grille à elle ; sa
  permission n'est réclamée que si la composante est active.
- Le score n'est jamais stocké : il se recalcule à partir des séries, de l'archive comme d'un import.

C'est un repère pour comparer tes matins entre eux, pas un avis médical.

## Format de sauvegarde

```json
{
  "app": "sommeil",
  "format": 1,
  "exportedAt": "2026-09-18",
  "days": [
    { "date": "2026-01-01", "sleepMinutes": 431, "steps": 8123, "screenMinutes": 214, "hrvRmssd": 48.1 }
  ],
  "settings": { "theme": "aube", "mode": "system" }
}
```

`settings` porte l'apparence (`theme` : `aube` ou `classique` ; `mode` : `system`, `light` ou
`dark`). Il est facultatif à l'import, et une valeur inconnue y est ignorée.

Chaque champ est facultatif : un fichier qui ne contient que `date` et `steps` s'importe très bien.
`hrvRmssd` est la VFC nocturne en millisecondes ; le score de récupération n'est pas exporté, il se
recalcule.
Les minutes de sommeil sont celles de la nuit **qui se termine** ce jour-là, comme dans la grille.

La lecture est volontairement tolérante, pour accepter ce que produisent d'autres applis :
`days` peut aussi être un objet indexé par date, `sleepHours` remplace `sleepMinutes`, et les noms
`step_count`, `hrv`, `rmssd` sont reconnus. Le cœur au repos et le poids des anciennes sauvegardes
sont ignorés : l'app ne les suit plus. Un import complète l'historique existant sans l'écraser ;
il n'écrit rien dans Health Connect.

L'historique local se vide depuis les réglages : la grille repart de ce que la prochaine lecture
retrouvera dans Health Connect et Android, sans rien effacer de Health Connect.

## Compiler

Nécessite un JDK 17+ et le SDK Android (plateforme 36). Indique le chemin du SDK dans `local.properties` :

```
sdk.dir=C:/chemin/vers/android-sdk
```

Puis :

```
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/`. Les tests (score de récupération,
géométrie des pastilles, écriture du PNG par bandes) tournent sur la JVM, sans téléphone :
`./gradlew testDebugUnitTest`.

Les previews d'Android Studio (Accueil, Grilles et Réglages en Aube et Nuit tombée, lever de
soleil, pastilles) sont dans `app/src/debug/java/.../Previews.kt` ; l'outillage de preview
n'entre que dans l'APK de debug.

Sous Windows, `build.ps1` compile avec l'outillage rangé dans `tools/` (JDK, Gradle et SDK
embarqués) et laisse l'APK à la racine (`Sommeil.apk`) ; `install.ps1` l'installe avec adb
et lance l'app.

Chaque push est aussi compilé par GitHub Actions
([.github/workflows/build.yml](.github/workflows/build.yml)), qui garde l'APK en artéfact :
de quoi l'installer sans rien compiler soi-même.

## Installer

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Au premier lancement, accorde à l'app l'accès aux données dans Health Connect. Sans l'autorisation
« historique », Health Connect ne restitue que les 30 jours précédant la première autorisation ;
sans « arrière-plan », le widget et les rappels se contentent des dernières données lues par l'app.

## Permissions

- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_STEPS`
- `android.permission.health.READ_HEART_RATE_VARIABILITY` (score de récupération, si la composante VFC est active)
- `android.permission.health.READ_HEALTH_DATA_HISTORY`
- `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`
- `android.permission.POST_NOTIFICATIONS` (rappels, demandée seulement si tu les actives)
- `android.permission.RECEIVE_BOOT_COMPLETED` (replacer les rappels après un redémarrage)
- `android.permission.PACKAGE_USAGE_STATS` (temps d'écran) : autorisation spéciale, à accorder
  soi-même dans Réglages › Accès aux données d'utilisation ; l'app ouvre l'écran au bon endroit

## Limites

L'APK publié en release est signé avec la clé de debug : suffisant pour une installation personnelle,
pas pour une publication sur le Play Store.

Les rappels utilisent des alarmes inexactes (`setAndAllowWhileIdle`) : ils tombent à quelques minutes
près, ce qui évite de réclamer l'autorisation « alarmes exactes ».
