# Galène Android

[![Licence MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Sans Google Play Services](https://img.shields.io/badge/Google%20Play%20Services-non%20requis-success)
![Statut : prototype](https://img.shields.io/badge/statut-prototype-orange)

Client Android natif, léger et respectueux de la vie privée pour le serveur de
visioconférence libre [Galène](https://galene.org).

> **English summary** — A lightweight, privacy-friendly native Android client for the
> [Galène](https://galene.org) videoconferencing server. Kotlin, Jetpack Compose and
> libwebrtc; no Google Play Services, no telemetry. MIT licensed. Early prototype:
> audio and video calls work, see the roadmap below.

---

## Pourquoi

Galène n'a pas de client mobile officiel : sur téléphone, on passe par le navigateur,
qui coupe les flux dès que l'écran s'éteint ou que l'on change d'application, et
n'offre aucune intégration au système. Ce projet comble ce manque côté Android, dans
l'esprit de Galène :

- **Léger** : une seule Activity, peu de dépendances, démarrage rapide.
- **Respectueux de la vie privée** : aucune télémétrie, aucun appel réseau hors du serveur Galène choisi.
- **Sans Google** : fonctionne sur LineageOS, GrapheneOS, /e/OS, sans Google Play Services.
- **Auditable** : code Kotlin commenté, architecture simple (voir [ARCHITECTURE.md](ARCHITECTURE.md)).
- **Libre** : licence MIT ; publication visée sur F-Droid.

## Fonctionnalités

| | |
|---|---|
| ✅ Groupes publics, connexion par mot de passe, lien d'invitation ou serveur d'authentification (YunoHost…) | ✅ Audio bidirectionnel avec annulation d'écho |
| ✅ Vidéo bidirectionnelle, caméra avant / arrière | ✅ Grille adaptative des participants, plein écran d'un appui |
| ✅ Couper micro / caméra (la caméra s'éteint réellement) | ✅ Appel maintenu en arrière-plan (notification) |
| ✅ Caméra coupée automatiquement en arrière-plan, rallumée au retour | ✅ Relance automatique de la connexion en cas d'échec ICE |
| ⏳ Reconnexion après perte réseau | ⏳ Bluetooth / casque filaire |
| ⏳ Intégration aux appels Android (ConnectionService) | ⏳ Partage d'écran, Picture-in-Picture |

## Installation

Aucune version publiée pour l'instant : il faut construire l'APK (voir ci-dessous).
Une publication sur F-Droid est prévue une fois l'application stabilisée.

## Utilisation

Le parcours est le même que sur le client web de Galène.

1. **Serveur** — saisissez l'adresse du serveur (`galene.example.org`) : l'application
   affiche ses groupes publics. Un groupe non listé se rejoint par son nom. Le dernier
   serveur utilisé est retenu.
2. **Groupe** — choisissez un groupe : nom d'utilisateur, mot de passe, et ce que vous
   publiez en entrant (*Rien*, *Micro*, *Caméra + micro*). Le formulaire s'adapte au groupe :
   - mot de passe Galène classique ;
   - **serveur d'authentification** (`authServer`, par exemple les comptes YunoHost) :
     vos identifiants sont vérifiés par ce serveur, qui délivre un jeton d'accès ;
   - portail web (`authPortal`) : pas encore pris en charge, utilisez un lien d'invitation.
3. **Salle** — en cas de refus du serveur, vous revenez au formulaire avec la raison.

**Avec un lien d'invitation** — collez-le directement dans le champ du serveur :

```
https://galene.example.org/group/ma-salle/?token=ABCDEF
```

Le nom d'utilisateur devient facultatif ; il n'est demandé que si le lien l'exige.

L'application ne demande que les permissions nécessaires au choix fait (micro, caméra,
et notifications sur Android 13+). Le mot de passe n'est jamais enregistré.

### Côté serveur

Toute instance Galène parlant la version 2 du protocole convient. Pour les téléphones en
réseau mobile ou derrière un NAT, un **serveur TURN fonctionnel** est indispensable :
sans lui, la connexion directe échoue souvent. Voir la
[documentation Galène](https://galene.org) sur le TURN intégré ou coturn.

## Compiler

### Prérequis

| Outil | Version |
|-------|---------|
| JDK | 17 ou 21 |
| Android SDK | platform `android-35`, build-tools `35.0.0` |
| Gradle | wrapper inclus |

Android Studio n'est pas nécessaire : tout se fait en ligne de commande.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android
export PATH=$PATH:$ANDROID_HOME/platform-tools

git clone https://github.com/antoinelucas64/galeneAndroid.git
cd galeneAndroid
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

### Installer sur un téléphone

Activez le débogage USB sur le téléphone (l'émulateur gère mal WebRTC), puis :

```bash
adb devices                      # le téléphone doit apparaître
./gradlew installDebug
```

### Suivre les logs

```bash
adb logcat -s GaleneClient WebRTCManager
```

- `GaleneClient` : messages de signaling échangés avec le serveur (`→` envoyé, `←` reçu) ;
- `WebRTCManager` : serveurs ICE, candidats locaux, états de connexion de chaque flux.

En cas de plantage natif (libwebrtc), la cause est sous les tags `rtc`, `libc` et `DEBUG` :

```bash
adb logcat -b crash -d
```

### Version release

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk

# Signature (keystore à créer une seule fois)
keytool -genkey -v -keystore galene-release.jks -alias galene -keyalg RSA -keysize 4096 -validity 10000
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks galene-release.jks \
  --out app-release-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

### Tests

Les tests d'intégration valident le protocole de signaling contre un vrai serveur Galène
(sur la JVM, sans téléphone) :

```bash
./gradlew :app:testDebugUnitTest
```

## Architecture

```
UI (Jetpack Compose)  ServerScreen · GroupLoginScreen · RoomScreen · VideoGrid
        │
RoomViewModel         orchestre l'appel, expose l'état à l'UI
        │
Domaine               GaleneDirectory (HTTP) · GaleneClient (WebSocket) · WebRTCManager · AudioRouter
        │
Services Android      CallForegroundService (appel en arrière-plan)
```

| Composant | Choix |
|-----------|-------|
| Langage | Kotlin |
| UI | Jetpack Compose + Material 3 |
| WebRTC | [`io.github.webrtc-sdk:android`](https://github.com/webrtc-sdk/android) (libwebrtc sans Play Services) |
| Signaling | OkHttp WebSocket |
| JSON | kotlinx.serialization |
| Injection | Koin |

Le détail des packages, du protocole Galène implémenté et des flux de données est dans
[ARCHITECTURE.md](ARCHITECTURE.md).

## Feuille de route

- [x] **Phase 1 — MVP audio** : connexion, authentification, audio bidirectionnel
- [x] **Phase 2 — Vidéo et UX** : vidéo, permissions, grille, contrôles, cycle de vie
- [ ] **Phase 3 — Robustesse** : ~~appel en arrière-plan~~ ✓, reconnexion réseau, routage audio, appels entrants
- [ ] **Phase 4 — Intégration système** : ConnectionService, UnifiedPush, partage d'écran, PiP
- [ ] **Phase 5 — Distribution** : build reproductible, F-Droid, documentation utilisateur

## Contribuer

Les retours et contributions sont bienvenus : ouvrez une
[issue](https://github.com/antoinelucas64/galeneAndroid/issues) ou une pull request.
Merci de joindre les logs `adb logcat -s GaleneClient WebRTCManager` à tout rapport de bug
de connexion, en masquant les tokens et mots de passe.

Les discussions sur le protocole Galène lui-même ont lieu sur la
[liste de diffusion Galène](https://lists.galene.org).

## Auteur

**Antoine Lucas** — [Antoine & Associés](https://antoineassocies.fr)

Projet indépendant, non affilié au projet Galène. Galène est développé par
Juliusz Chroboczek (Université Paris Cité) et ses contributeurs.

## Licence

[MIT](LICENSE) © 2026 Antoine Lucas — Antoine & Associés
