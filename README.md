# Sommeil

> 🤖 Vibecodé avec [Claude Code](https://claude.com/claude-code) — la quasi-totalité du code a été générée par IA.

Application Android qui affiche une année de données de santé sous forme de grille façon GitHub :
plus la nuit est longue (ou la journée active), plus la case est verte ; plus elle est courte, plus elle tire vers le rouge.

Les données viennent de [Health Connect](https://health.google/health-connect-android/) et restent sur le téléphone : l'app ne demande pas la permission `INTERNET`, n'envoie rien nulle part et n'écrit dans aucun stockage externe.

Une seule nuance depuis les commentaires hors ligne : le modèle Gemini Nano est téléchargé
une fois par AICore, le service système d'Android, et pas par l'app. C'est du réseau sur le
téléphone, mais dans l'autre sens — rien de tes données ne part avec.

L'app refuse aussi la sauvegarde automatique d'Android (`allowBackup="false"` et
`data_extraction_rules.xml`), qui recopierait sinon l'historique de santé vers le Google Drive du
téléphone. Les données ne sortent que par l'export, quand on le demande.

## Fonctionnalités

- Grille annuelle pour cinq métriques — sommeil, pas, cœur au repos, poids, temps d'écran — navigation par année, détail d'une journée au toucher.
- Sommeil : sessions Health Connect, stades « éveillé » déduits, chevauchements entre montre et téléphone fusionnés, nuit rattachée à la date du réveil.
- Pas : agrégation quotidienne Health Connect. Cœur au repos et poids : moyenne des relevés du jour.
- Statistiques par métrique : moyenne, 7 derniers jours, record, tendance sur 30 jours.
- **Séries** : jours consécutifs au-dessus de l'objectif (celui des réglages pour le sommeil,
  10 000 pas, 58 bpm), série en cours et record de l'année. Un jour sans donnée coupe la série.
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
- **Commentaires hors ligne** (appareils compatibles) : deux phrases qui croisent tes chiffres,
  une barre pour interroger la grille en français (« mes nuits de janvier »), et le résumé du
  dimanche rédigé plutôt que gabarité. Tout est produit par [Gemini Nano](https://developers.google.com/ml-kit/genai)
  sur le téléphone, à partir des statistiques que l'app a déjà calculées — voir
  [Commentaires hors ligne](#commentaires-hors-ligne).
- **Widget** d'écran d'accueil : les dernières semaines de la métrique choisie dans les réglages,
  redimensionnable de 4x2 jusqu'à 2x1 (l'en-tête s'efface quand la tuile est trop plate).
- **Partage** : export de la grille de l'année en PNG, via le sélecteur de partage Android.
- **Métriques masquables** : pas, cœur au repos et poids se retirent du menu principal depuis les
  réglages ; leurs autorisations ne sont alors plus réclamées. Le sommeil reste toujours affiché.
- **Sauvegarde JSON** : export et import d'un fichier lisible tel quel, une ligne par jour
  (voir [Format de sauvegarde](#format-de-sauvegarde)), plus un export CSV pour les tableurs.
  L'app tient son propre historique, alimenté par ce qu'elle lit dans Health Connect et par ce qui
  est importé ; il survit donc à un changement de téléphone ou à la rétention de Health Connect.
- **Affichage réglable** : statistiques, séries, semaine type, légende, panneau « activité et
  sommeil » et commentaires s'activent séparément ; la taille des cases se choisit, et la grille
  peut remplir la hauteur de l'écran quand le téléphone passe à l'horizontale (elle défile alors
  latéralement).
- Mode démo si Health Connect n'est pas disponible.

## Échelles de couleurs

| Métrique | ← moins bien | | | | mieux → |
| --- | --- | --- | --- | --- | --- |
| **Sommeil** | < 5h | 5-6h | 6-7h | 7-8h | 8h+ |
| **Pas** | < 3k | 3-6k | 6-8k | 8-10k | 10k+ |
| **Cœur au repos** | 70+ | 64-70 | 58-64 | 52-58 | < 52 |
| **Temps d'écran** | 5h+ | 4-5h | 3-4h | 2-3h | < 2h |

Le poids n'a pas de « bon » côté : il reçoit un dégradé bleu neutre, calé sur les quintiles
de l'année affichée (du plus léger au plus lourd), avec les seuils réels en légende.

## Commentaires hors ligne

Trois fonctions s'appuient sur les [ML Kit GenAI APIs](https://developers.google.com/ml-kit/genai)
(Prompt API, `com.google.mlkit:genai-prompt`), qui font tourner Gemini Nano sur l'appareil :

| Fonction | Où |
| --- | --- |
| « Ce qui ressort » : deux phrases qui croisent moyenne, séries, semaine type et corrélation | sous les panneaux de la métrique affichée |
| « Poser une question » : une période en français, la grille saute dessus et estompe le reste | sous la grille |
| Résumé du dimanche rédigé | notification hebdomadaire |

**Le modèle rédige, il ne compte pas.** Tous les chiffres qu'il reçoit sont déjà calculés par
l'app, et la consigne lui interdit d'en inventer ou d'en recalculer. La barre de question va
plus loin : le modèle n'y voit aucune donnée de santé, seulement la date du jour et les années
disponibles, et ne renvoie qu'une métrique et deux dates que l'app applique elle-même. Une
réponse mal formée est rejetée plutôt qu'interprétée au jugé.

### Ce qui ne marche pas, et pourquoi

- **Tous les téléphones ne sont pas concernés.** Le Prompt API couvre une liste plus courte que
  les autres APIs ML Kit, et exclut les appareils au bootloader déverrouillé. Quand le modèle
  n'est pas disponible, ces trois fonctions disparaissent : l'app est alors exactement celle
  d'avant, sans bouton grisé ni message d'erreur. Liste à jour :
  [device support](https://developers.google.com/ml-kit/genai#device-support).
- **Pas d'inférence en arrière-plan.** AICore la refuse (`BACKGROUND_USE_BLOCKED`), ce qui exclut
  le widget et le `BroadcastReceiver` des rappels. Le résumé du dimanche est donc rédigé pendant
  que l'app est ouverte et relu au moment de notifier ; il se périme au bout de deux jours, après
  quoi la notification retombe sur le résumé calculé. Les réglages montrent l'état du cache et
  permettent de le refaire à la demande.
- **Le résumé n'est pas en Summarization.** Cette API ne parle qu'anglais, japonais et coréen.
  D'où le Prompt API partout, avec une consigne de langue.
- **APIs en Beta** : pas de SLA, et les signatures peuvent changer. Les
  [ML Kit GenAI API Additional Terms](https://developers.google.com/ml-kit/genai-terms)
  s'appliquent. Aucun coût : l'inférence tourne sur l'appareil, il n'y a pas d'endpoint facturé.
- **Pas de Structured Output.** La réponse de la barre de question est du JSON lu à la main plutôt
  qu'un objet `@Generable` : ce dernier est en Alpha et réclame le plugin KSP 2.3.6, donc Kotlin
  2.3, alors que le projet est en Kotlin 2.2. À rebasculer si le projet monte de version.
- Le tout se coupe dans **Réglages → Affichage → « Commentaires du modèle local »**.

## Format de sauvegarde

```json
{
  "app": "sommeil",
  "format": 1,
  "exportedAt": "2026-09-18",
  "days": [
    { "date": "2026-01-01", "sleepMinutes": 431, "steps": 8123, "restingHeartRate": 56.2, "weightKg": 72.4,
      "screenMinutes": 214 }
  ]
}
```

Chaque champ est facultatif : un fichier qui ne contient que `date` et `steps` s'importe très bien.
Les minutes de sommeil sont celles de la nuit **qui se termine** ce jour-là, comme dans la grille.

La lecture est volontairement tolérante, pour accepter ce que produisent d'autres applis :
`days` peut aussi être un objet indexé par date, `sleepHours` remplace `sleepMinutes`, et les noms
`heartRate`, `bpm`, `weight`, `step_count` sont reconnus. Un import complète l'historique existant
sans l'écraser ; il n'écrit rien dans Health Connect.

## Compiler

Nécessite un JDK 17+ et le SDK Android (plateforme 36). Indique le chemin du SDK dans `local.properties` :

```
sdk.dir=C:/chemin/vers/android-sdk
```

Puis :

```
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/`.

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
- `android.permission.health.READ_RESTING_HEART_RATE`
- `android.permission.health.READ_WEIGHT`
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
