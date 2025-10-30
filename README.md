# RadioApp 📻

Application Android de streaming radio avec 22 stations internationales, statistiques d'écoute détaillées et widget.

## 📱 Fonctionnalités

### 🎵 Stations de Radio (22)
- **France** : France Inter, France Culture, France Info, France Musique, FIP, RTL, Radio Nova, RFI, RAJE, Bide et Musique, Radio Meuh
- **BBC** : Radio 1, Radio 3, Radio Scotland, World Service
- **International** : Radio Canada Première, So! Radio Oman, Ibiza Global Radio, WWOZ (New Orleans), Radio Caroline
- **Underground** : 97 Underground, Pink Unicorn Radio

### 📊 Statistiques Complètes
- **Nombre de lectures** par station
- **Temps d'écoute total** par station (formaté en heures/minutes/secondes)
- **Volume de données consommées** (en MB avec formatage européen)
- **Tri automatique** des stations par popularité
- **Sauvegarde automatique** toutes les 10 secondes

### 🔔 Notification Enrichie
La notification en foreground affiche :
- Nom de la station avec logo
- Durée de la session en temps réel
- Volume de données consommées
- Débit moyen de connexion
- Codec audio détecté (MP3, AAC, etc.)
- Type de connexion (IPv4/IPv6)
- Boutons : Play/Pause, Stop, Passer pub (fast-forward)

**Mode compact :**
```
France Inter
5m 23s • 2.34 MB
```

**Mode étendu :**
```
France Inter
⏱ Durée: 5m 23s
📊 Données: 2.34 MB
⚡ Débit: 128 kbps
🎵 Codec: MP3 (128 kbps)
🌐 Connexion: IPv4
```

### 🎛️ Contrôles Média
- **MediaSession** intégrée pour contrôles système
- Contrôles sur écran verrouillé
- Support centre de contrôle Android
- Boutons physiques du téléphone
- Casques et écouteurs Bluetooth

### 📱 Widget Android
- Affiche 4 stations en accès rapide
- Station en cours toujours visible en premier
- 3 stations les plus écoutées
- Lancement direct depuis l'écran d'accueil
- Mise à jour automatique

### ⚡ Optimisations
- **Cache des logos** : évite le décodage répété des images
- **Mise à jour partielle** : RecyclerView optimisé avec payloads
- **Sauvegarde intelligente** : données écrites toutes les 10s
- **Gestion mémoire** : cleanup automatique des ressources

## 🛠️ Technologies

- **Langage** : Kotlin
- **Audio** : ExoPlayer 2.19.1
- **Architecture** : Service en foreground + MediaSession
- **Streaming** : Support Icecast/Shoutcast, HLS, MP3
- **Stockage** : SharedPreferences pour les statistiques
- **Minimum SDK** : API 24 (Android 7.0)
- **Target SDK** : API 34 (Android 14)

## 📦 Installation

### Depuis l'APK
1. Télécharger `app-debug.apk` depuis les releases
2. Activer "Sources inconnues" dans les paramètres Android
3. Installer l'APK

### Compilation depuis les sources
```bash
# Cloner le repository
git clone https://github.com/ltn22/RadioApp.git
cd RadioApp

# Compiler (nécessite JDK 17+)
export JAVA_HOME="/path/to/jdk17"
./gradlew assembleDebug

# L'APK sera dans :
# app/build/outputs/apk/debug/app-debug.apk
```

## 🏗️ Structure du Projet

```
app/src/main/
├── java/com/radioapp/
│   ├── MainActivity.kt                 # Activité principale
│   ├── adapter/
│   │   └── RadioStationAdapter.kt     # Adapter RecyclerView optimisé
│   ├── data/
│   │   ├── StatsManager.kt            # Gestion des statistiques
│   │   └── MetadataService.kt         # Métadonnées ICY
│   ├── model/
│   │   └── RadioStation.kt            # Modèle de données
│   ├── service/
│   │   └── RadioService.kt            # Service de lecture en foreground
│   └── widget/
│       └── RadioWidgetProvider.kt     # Widget Android
├── res/
│   ├── drawable/                      # Logos des stations (22)
│   ├── layout/
│   │   ├── activity_main.xml          # Layout principal
│   │   ├── item_radio_station.xml     # Item de station
│   │   └── widget_layout.xml          # Layout du widget
│   └── xml/
│       └── radio_widget_info.xml      # Configuration widget
└── AndroidManifest.xml
```

## 🎯 Utilisation

### Lecture de base
1. Lancer l'application
2. Sélectionner une station dans la liste
3. La lecture démarre automatiquement
4. Les statistiques sont mises à jour en temps réel

### Notification
- **Réduire** : Voir les infos compactes
- **Déplier** : Voir tous les détails techniques
- **Play/Pause** : Contrôler la lecture
- **Stop** : Arrêter et fermer le service
- **Passer pub** : Fast-forward de 2 secondes (à 8x la vitesse)

### Widget
1. Appui long sur l'écran d'accueil
2. Sélectionner "Widgets"
3. Glisser le widget "RadioApp" sur l'écran
4. Les stations s'affichent automatiquement

### Statistiques
- Les stations sont **automatiquement triées** par nombre d'utilisations
- Les plus écoutées remontent en haut de la liste
- Les statistiques sont **sauvegardées automatiquement**

## 🔧 Configuration

### Ajouter une nouvelle station

Dans `MainActivity.kt`, ajouter dans la liste `stations` :

```kotlin
RadioStation(
    id = 23,
    name = "Nom de la Station",
    url = "https://stream.url.com/radio.mp3",
    genre = "Genre",
    logoResId = R.drawable.logo_ma_station
)
```

Puis ajouter le logo correspondant dans `res/drawable/` et mettre à jour le mapping dans `RadioWidgetProvider.kt`.

## 🐛 Résolution de problèmes

### La notification ne s'affiche pas
- Vérifier les autorisations de notification dans les paramètres Android
- Désinstaller complètement l'app et réinstaller (pour réinitialiser le canal de notification)

### Pas de son
- Vérifier le volume média (pas le volume sonnerie)
- Vérifier que l'URL du flux est accessible
- Certains flux nécessitent une connexion stable

### Crash au démarrage (Android 12+)
- Le code gère déjà le `ForegroundServiceStartNotAllowedException`
- Vérifier les permissions `FOREGROUND_SERVICE` et `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

### Widget ne se met pas à jour
- Le widget utilise des broadcasts pour les mises à jour
- Redémarrer le téléphone si nécessaire

## 📝 Notes Techniques

### Gestion du débit
Le débit affiché est le **débit moyen** depuis le début de la session, pas le débit instantané. Cela donne une mesure plus stable et représentative.

### Détection du codec
Le codec est détecté via `ExoPlayer.onTracksChanged()`. Si "N/A" est affiché, cela signifie que le flux n'a pas encore fourni les informations de format.

### IPv4 vs IPv6
La détection se fait en résolvant le DNS du hostname. La première adresse retournée est considérée comme celle utilisée par la connexion.

### Fast-forward (Passer pub)
Le bouton "Passer pub" accélère la lecture à 8x pendant 2 secondes, permettant de sauter environ 16 secondes de contenu.

## 🙏 Crédits

- **Logos** : Propriété de leurs stations respectives
- **ExoPlayer** : Google / Android Open Source Project
- **Icônes** : Material Design Icons

## 📄 Licence

Ce projet a été créé à des fins éducatives et personnelles. Les logos et noms de stations appartiennent à leurs propriétaires respectifs.

## 🤖 Développement

Application développée avec l'assistance de **Claude Code** (Anthropic).

---

**Version actuelle** : 1.0
**Dernière mise à jour** : Octobre 2025