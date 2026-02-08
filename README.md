# RadioApp 📻

Application Android de streaming radio avec 43 stations internationales, **support Android Auto**, statistiques d'écoute détaillées, widget et **fonctions de réveil avancées**.

## 📱 Fonctionnalités

### ⏰ Réveil Intelligent (Nouveau)
- **Réveil France Culture** : Une fonction exclusive pour se réveiller avec France Culture.
- **Silence Pub** : Le réveil se déclenche **1 minute avant** l'heure prévue en mode "Mute" pour laisser passer les publicités de pré-roll, puis rétablit le son à l'heure exacte.
- **Fiabilité** : Le système de réveil est robuste et vérifie l'heure sur une fenêtre de 10 secondes pour ne jamais manquer le réveil.
- **Persistance** : L'heure et l'état du réveil sont sauvegardés et restaurés même si l'application est redémarrée.

### 🚗 Android Auto
- **Intégration native complète** : l'application apparaît automatiquement dans Android Auto
- **Navigation intuitive** : parcourez vos 36 stations directement depuis l'écran de votre voiture
- **Tri intelligent** : les stations sont classées par ordre d'utilisation
- **Contrôle complet** : lecture, pause, stop et changement de station en toute sécurité
- **Métadonnées en temps réel** : titre du morceau et logo de la station affichés
- **Gestion audio automatique** : focus audio géré intelligemment
- **Action personnalisée** : bouton "Passer pub" accessible depuis l'interface Android Auto

### 🎵 Stations de Radio (43)
- **France** : France Inter, France Culture, France Info, France Musique, FIP, RTL, Radio Nova, RFI, RAJE, Bide et Musique, Radio Meuh
- **BBC** : Radio 1, Radio 3, Radio 4, Radio 6 Music, Radio Scotland, World Service
- **Electronic/Underground** : NTS 1, NTS 2, dublab, Cashmere Radio, Rinse FM, Refuge Worldwide 1 & 2, Ibiza Global Radio, Ibiza Live Radio, Radio FG, Chicago House Radio
- **Alternative/Eclectic** : FluxFM, Le Mellotron, **KEXP** (Seattle)
- **International** : Radio Canada Première, So! Radio Oman, WWOZ (New Orleans), Radio Caroline, Ö1 (Austria), **KCRW** (Santa Monica), **CKUA** (Alberta), **4ZZZ** (Brisbane), **Alpha Radio** (Mexico)
- **Rock/Metal** : 97 Underground, Pink Unicorn Radio
- **Eclectic** : **Radio Paradise**

### 📊 Statistiques Complètes
- **Nombre de lectures** par station
- **Temps d'écoute total** par station (formaté en heures/minutes/secondes)
- **Volume de données consommées** (en MB avec formatage européen)
- **Tri intelligent** : par nombre de lectures, puis par durée d'écoute en cas d'égalité
- **Sauvegarde automatique** toutes les 10 secondes
- **Indicateur visuel** : stations avec fond coloré selon le type de connexion (IPv4=jaune, IPv6=violet clair)

### 🔔 Notification Enrichie
La notification en foreground affiche :
- Nom de la station avec logo
- **Titre du morceau en cours** (si métadonnées disponibles)
- Durée de la session en temps réel
- Volume de données consommées
- Débit moyen de connexion
- Codec audio détecté (MP3, AAC, etc.)
- Type de connexion (IPv4/IPv6 avec code couleur)
- Boutons : Play/Pause, Stop, Passer pub / **Spotify** (si métadonnées disponibles)

**Mode compact :**
```
France Inter • Miles Davis - So What
5m 23s • 2.34 MB
```

**Mode étendu :**
```
France Inter • Miles Davis - So What
🎵 Miles Davis - So What
⏱ Durée: 5m 23s
📊 Données: 2.34 MB
⚡ Débit: 128 kbps
🎼 Codec: MP3 (128 kbps)
🌐 Connexion: IPv4
```

### 📺 Google Cast
- Diffusion sur Chromecast et appareils compatibles Google Cast
- Contrôle du volume à distance (supporté même pour le réveil)
- Affichage des métadonnées et pochettes sur la TV

## 🎯 Utilisation du Réveil France Culture

Le réveil est une fonctionnalité spéciale attachée à la station **France Culture** (mais le principe pourra être étendu).

1.  **Activer le Réveil** :
    -   Effectuez un **appui long** sur la case de la station **France Culture**.
    -   Une petite horloge apparaît sur la case, indiquant l'heure du réveil (par défaut 06:30).
    -   Un message "Réveil France Culture activé" confirme l'action.

2.  **Régler l'Heure** :
    -   Cliquez directement sur la **petite horloge** affichée sur la case France Culture.
    -   Une boîte de dialogue s'ouvre pour entrer la nouvelle heure (format HH:mm).
    -   Validez pour sauvegarder.

3.  **Fonctionnement** :
    -   Laissez l'application ouverte (au premier plan ou en arrière-plan).
    -   À **Heure - 1 minute** (ex: 06:29 si réglé à 06:30) :
        -   L'application coupe le volume (mode silencieux).
        -   Elle lance le flux de France Culture.
        -   Cela permet de "manger" la publicité de pré-roll en silence.
    -   À **l'Heure exacte** (ex: 06:30) :
        -   Le volume est rétabli progressivement.
        -   Vous entendez le début de l'émission pile à l'heure !

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
│   ├── cast/
│   │   ├── CastManager.kt             # Gestion Google Cast
│   │   └── CastOptionsProvider.kt     # Options Cast
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
│   ├── drawable/                      # Logos des stations (36)
│   ├── layout/                        # Layouts XML
│   └── xml/                           # Configurations
└── AndroidManifest.xml
```

## 🐛 Résolution de problèmes

### L'alarme ne se déclenche pas
- Assurez-vous que l'application n'est pas "tuée" par les économiseurs de batterie de votre téléphone.
- L'application doit être au moins en arrière-plan (ou minimisée), si vous la forcez à s'arrêter complètement via les paramètres, l'alarme ne pourra pas se lancer.

### Bouton Stop
- Le bouton Stop arrête désormais complètement la lecture, vide le cache et permet de relancer immédiatement la même station (ce qui n'était pas possible avant la v1.3).

## 📝 Notes Techniques

### Gestion du débit
Le débit affiché est le **débit moyen** depuis le début de la session, pas le débit instantané.

### IPv4 vs IPv6
- **IPv4** : Fond jaune (#FFFFEB3B)
- **IPv6** : Fond violet clair (#FFD090E0)

## 🙏 Crédits
- **Logos** : Propriété de leurs stations respectives
- **ExoPlayer** : Google / Android Open Source Project
- **Icônes** : Material Design Icons

## 📄 Licence
Ce projet a été créé à des fins éducatives et personnelles.

---

**Version actuelle** : 1.4
**Dernière mise à jour** : Février 2026

### 🆕 Nouveautés version 1.4
- ✅ **4 Nouvelles Stations** : KCRW, 4ZZZ, CKUA, Radio Paradise, Alpha Radio, BBC 6 Music, KEXP (Total 43)
- ✅ **Métadonnées Radio Paradise** : Affichage pochette/titre/artiste via API
- ✅ Optimisation des logos pour éviter les crashs (redimensionnement)
- ✅ Amélioration de la stabilité

### 🆕 Nouveautés version 1.3
- ✅ **Réveil France Culture** : Fonctionnalité d'alarme avec saut de publicité
- ✅ **Persistance** : Sauvegarde des heures de réveil et de l'état activé/désactivé
- ✅ **Bouton Stop amélioré** : Réinitialisation complète de l'état pour une meilleure ergonomie
- ✅ **Google Cast** : Contrôle du volume amélioré
- ✅ **Fiabilité** : Corrections de bugs mineurs sur la liste des stations

### Nouveautés version 1.2
- ✅ **Support Android Auto complet**
- ✅ **Gestion automatique du focus audio**
- ✅ Recherche iTunes pour les pochettes
- ✅ Bouton "Passer pub" accessible