# Architecture — Galène Android

Ce document décrit l'organisation du code, les responsabilités de chaque package et les flux de données entre composants.

---

## Vue d'ensemble

```
┌──────────────────────────────────────────────────────────────┐
│                    UI  (Jetpack Compose)                     │
│  ConnectScreen           RoomScreen + VideoGrid              │
│  (saisie params)         (grille vidéo + contrôles)          │
└───────────────────────────┬──────────────────────────────────┘
                            │ StateFlow<RoomUiState>
┌───────────────────────────▼──────────────────────────────────┐
│                      RoomViewModel                           │
│  Orchestre le signaling et WebRTC · expose l'état UI         │
└──────────┬────────────────────────────────────┬──────────────┘
           │                                    │
┌──────────▼──────────┐            ┌────────────▼─────────────┐
│    GaleneClient     │            │      WebRTCManager       │
│  WebSocket OkHttp   │            │  PeerConnections libwebrtc│
│  protocole signaling│            │  uplink / downlinks      │
└──────────┬──────────┘            └────────────┬─────────────┘
           │ WS TLS                             │ DTLS/SRTP UDP
┌──────────▼──────────┐            ┌────────────▼─────────────┐
│   Serveur Galène    │◄──────────►│   Serveur Galène (SFU)   │
│   (signaling /ws)   │            │   (relai média)          │
└─────────────────────┘            └──────────────────────────┘
```

Galène est un SFU (*Selective Forwarding Unit*) : chaque client établit **une** connexion WebSocket de signaling et **une ou plusieurs** connexions WebRTC avec le serveur (une en upload, une par flux reçu).

---

## Structure des packages

```
app/src/main/java/fr/antoineassocies/galene/
│
├── GaleneApp.kt                   # Point d'entrée Application, initialise Koin
├── MainActivity.kt                # Unique Activity, monte GaleneNavHost
│
├── di/
│   └── AppModule.kt               # Graphe de dépendances Koin
│                                  # (OkHttpClient, Json, EglBase, PeerConnectionFactory,
│                                  #  GaleneClient, WebRTCManager, AudioRouter, RoomViewModel)
│
├── domain/                        # Couche métier pure (pas d'UI, pas d'Android UI)
│   ├── AudioRouter.kt             # Mode communication + haut-parleur pendant l'appel
│   ├── ConnectionState.kt         # Enum : Connecting | Connected | Disconnected
│   ├── GaleneClient.kt            # WebSocket + dispatch des messages du protocole
│   ├── SignalingEvent.kt          # Sealed class : événements internes du signaling
│   ├── WebRTCManager.kt           # PeerConnections (up/downlinks), micro, caméra,
│   │                              # pistes vidéo exposées à l'UI
│   │
│   ├── model/
│   │   ├── JoinRequest.kt         # Paramètres de connexion (URL, salle, nom, password/token)
│   │   └── Participant.kt         # Données d'un pair dans la salle
│   │
│   └── protocol/
│       └── Messages.kt            # Data classes @Serializable pour le protocole Galène
│                                  # (HandshakeMsg, JoinMsg, OfferMsg, AnswerMsg, …)
│
├── service/
│   └── CallForegroundService.kt   # Service de premier plan « microphone » :
│                                  # garde le micro actif quand l'app est en arrière-plan
│
└── ui/
    ├── GaleneNavHost.kt           # Graphe de navigation Compose (connect → room)
    │
    ├── components/
    │   └── VideoGrid.kt           # Grille de tuiles + VideoRenderer (SurfaceViewRenderer)
    │
    ├── screens/
    │   ├── ConnectScreen.kt       # Écran de connexion : saisie URL/salle/token
    │   └── RoomScreen.kt          # Écran de salle : grille, contrôles, permissions,
    │                              # premier plan / arrière-plan
    │
    ├── theme/
    │   ├── Color.kt
    │   ├── Theme.kt               # Material 3 GaleneTheme
    │   └── Type.kt
    │
    └── viewmodel/
        └── RoomViewModel.kt       # État UI + orchestration signaling/WebRTC
```

---

## Interactions entre packages

### 1. Démarrage de l'application

```
GaleneApp.onCreate()
    └─ Koin.start(appModule)        # instancie OkHttpClient, Json, PeerConnectionFactory
            │
MainActivity.onCreate()
    └─ setContent { GaleneNavHost() }
            │
GaleneNavHost
    └─ NavHost(startDestination = "connect")
            └─ ConnectScreen
```

### 2. Rejoindre une salle

```
ConnectScreen
    onJoin(JoinRequest)
        │
        ├─ joinRequestHolder.request = request
        └─ navController.navigate("room")
                │
        RoomScreen(request)
                │
                └─ koinViewModel<RoomViewModel>(params)
                        │
                RoomViewModel.init
                        ├─ galeneClient.connect(serverUrl)    # ouvre le WebSocket
                        ├─ observeSignaling()                 # collecte galeneClient.events
                        └─ observeConnectionState()           # collecte galeneClient.connectionState
```

### 3. Flux de signaling (GaleneClient → RoomViewModel)

```
GaleneClient                         RoomViewModel
    │                                    │
    │  WS onOpen                         │
    ├──► Connected event ────────────────► sendJoin(roomName, displayName, …)
    │                                    │
    │  "joined" kind=join                │
    ├──► Joined event ───────────────────► applyRtcConfiguration() + audioRouter.start()
    │                                    ├─ sendRequest({"" : ["audio", "video"]})
    │                                    └─ maybeStartUplink()  ────────────┐
    │                                       (attend joined ET permissions)   │
    │                                    WebRTCManager                      │
    │                                    ├─ piste micro (si autorisé)       │
    │                                    ├─ piste caméra (si autorisée)     │
    │                                    ├─ startUpstream(streamId, label)  │
    │                                    │    └─ PeerConnection.createOffer()│
    │                                    │                                   │
    │                                    └───────────────────────────────────┘
    │                                         sdpOffer
    │                                    galeneClient.sendOffer(streamId, label, sdpOffer)
    │                                         │
    │  "answer" (server réponse SFU)          │
    ├──► RemoteAnswer ───────────────────► webRTCManager.handleUpstreamAnswer()
    │                                         └─ pc.setRemoteDescription()
    │
    │  "ice" (candidats ICE du serveur)
    ├──► RemoteIce ──────────────────────► webRTCManager.addRemoteIceCandidate()
    │
    │  "offer" (flux d'un autre participant)
    ├──► RemoteOffer ────────────────────► webRTCManager.handleRemoteOffer()
    │                                         ├─ pc.setRemoteDescription()
    │                                         ├─ pc.createAnswer()
    │                                         └─ galeneClient.sendAnswer()
    │
    │  "user" kind=add/change/delete
    ├──► UserUpdate ─────────────────────► handleUserUpdate() → participants list
    │
    │  "renegotiate" (échec ICE vu par le serveur)
    └──► Renegotiate ────────────────────► restartUplinkIce() → offer kind=renegotiate
```

### 3 bis. Récupération des échecs ICE

Calquée sur le client web (`static/protocol.js`) : dès qu'une PeerConnection passe en
`IceConnectionState.FAILED`, `WebRTCManager.onIceFailed` prévient le ViewModel, qui

- pour le flux **montant**, refait une offre avec `IceRestart` (même `id`, `kind=renegotiate`) ;
- pour un flux **descendant**, envoie `{"type":"renegotiate","id":…}` ; le serveur
  répond par une nouvelle offre sur la même PeerConnection.

Le serveur envoie lui aussi `renegotiate` pour le flux montant après son propre délai ;
une seconde relance est sans conséquence.

### 3 ter. Arrière-plan et cycle de vie

| Événement | Effet |
|-----------|-------|
| Micro autorisé (app visible) | `CallForegroundService.start()` — service « microphone » + notification |
| `ON_STOP` (app masquée) | `onAppBackground()` : capture caméra arrêtée, le son continue |
| `ON_START` (retour) | `onAppForeground()` : caméra relancée si c'est l'arrière-plan qui l'avait coupée |
| Rotation d'écran | Activity non recréée (`configChanges`), rien à faire |
| `leave()` / fin du ViewModel | Libération WebRTC, audio restauré, service arrêté |
| App balayée des tâches récentes | `onTaskRemoved` → service arrêté, appel terminé |

Android 14 n'autorise le démarrage d'un service « microphone » que si l'app est visible
et la permission RECORD_AUDIO accordée : c'est pourquoi il est lancé dans
`onPermissionsResult`, jamais depuis l'arrière-plan.

### 4. WebRTCManager — uplink vs downlinks

```
WebRTCManager
│
├── upStream: UpStream?                    # notre flux vers le SFU (label "camera")
│   └── PeerConnection (sendonly, BUNDLE unique)
│       ├─ localAudioTrack (micro)
│       ├─ localVideoTrack (caméra 640×480@30, Camera2)
│       └─ ICE ──► (TURN) ──► SFU
│
└── downStreams: Map<streamId, DownStream> # un par flux publié par les autres
    └── PeerConnection (recvonly)
        └─ ICE ──► (TURN) ──► SFU ──► pistes audio + vidéo reçues

Vers l'UI (StateFlow, alimentés depuis le thread signaling de libwebrtc) :
├── localVideoTrack: VideoTrack?             # aperçu local
└── remoteVideoTracks: Map<streamId, VideoTrack>
```

L'audio reçu est joué directement par libwebrtc (module audio Java) ; `AudioRouter`
passe le téléphone en mode communication sur haut-parleur pendant l'appel.

La vidéo est rendue par des `SurfaceViewRenderer` (dans `VideoGrid`) partageant le
contexte EGL de la factory, ce qui permet l'encodage et le décodage matériels.

Les candidats ICE émis par les `PeerConnection` sont interceptés via `onLocalIceCandidate`  
et transmis immédiatement à `GaleneClient.sendIce()`.

Les candidats du serveur arrivant avant que la `remoteDescription` soit posée, **ou pendant
qu'elle est en cours d'application** (`settingRemote`), sont mis en buffer dans
`pendingCandidates` et drainés juste après. Sans cela, les candidats qui suivent de près une
answer ou un ICE restart étaient perdus ou rattachés à l'ancienne génération ICE.

### 5. Injection de dépendances (Koin)

```
appModule
│
├── single<OkHttpClient>          # partagé entre GaleneClient instances
├── single<Json>                  # encodeDefaults=true, ignoreUnknownKeys=true
├── single<EglBase>               # contexte EGL partagé capture / codecs / rendu
├── single<PeerConnectionFactory> # codecs vidéo matériels ; initialisé une seule fois
│
├── factory<GaleneClient>         # nouvelle instance par session
│                                 # (évite les leaks d'état entre appels)
├── factory<WebRTCManager>
├── factory<AudioRouter>
│
└── viewModel<RoomViewModel>(params)   # paramétré : JoinRequest
```

`factory` (et non `single`) pour `GaleneClient` et `WebRTCManager` : chaque appel  
dans une nouvelle `RoomScreen` reçoit une instance fraîche, sans état résiduel.

---

## Protocole Galène — messages implémentés

Définis dans `domain/protocol/Messages.kt` et dispatché dans `GaleneClient.handleMessage()`.

### Client → Serveur

| Type | Classe | Moment d'envoi |
|------|--------|----------------|
| `handshake` | `HandshakeMsg` | À l'ouverture du WebSocket |
| `join` kind=join | `JoinMsg` | Après handshake confirmé |
| `join` kind=leave | `LeaveMsg` | À la déconnexion |
| `request` | `RequestMsg` | Après joined, pour demander les flux |
| `offer` | `OfferMsg` | Quand l'uplink SDP est prêt ; `kind=renegotiate` pour un ICE restart |
| `answer` | `AnswerMsg` | En réponse à un offer du serveur |
| `ice` | `IceMsg` | À chaque candidat ICE local |
| `renegotiate` | `RenegotiateMsg` | Échec ICE sur un flux descendant |
| `pong` | `PongMsg` | En réponse à un `ping` serveur |

### Serveur → Client

| Type | Classe | Action dans RoomViewModel |
|------|--------|--------------------------|
| `handshake` | `ServerHandshakeMsg` | Vérifie version=["2"], émet `Connected` |
| `joined` kind=join | `JoinedMsg` | Émet `Joined`, démarre uplink |
| `joined` kind=fail | `JoinedMsg` | Émet `JoinFailed`, affiche erreur |
| `joined` kind=leave | `JoinedMsg` | Émet `JoinLeft`, vide participants |
| `user` | `UserMsg` | Émet `UserUpdate`, met à jour la liste |
| `offer` | `ServerOfferMsg` | Émet `RemoteOffer`, crée (ou renégocie) un downlink |
| `answer` | `ServerAnswerMsg` | Émet `RemoteAnswer`, finalise uplink |
| `ice` | `ServerIceMsg` | Émet `RemoteIce`, ajoute candidat ICE |
| `close` | `CloseMsg` | Émet `StreamClose`, ferme downlink |
| `abort` | `AbortMsg` | Émet `StreamAbort`, ferme uplink |
| `renegotiate` | `RenegotiateMsg` | Émet `Renegotiate`, ICE restart de l'uplink |
| `ping` | — | Répond `pong` directement dans GaleneClient |

---

## Navigation UI

```
GaleneNavHost                      JoinRequestHolder (ViewModel scope Activity)
│                                      ▲ request: JoinRequest?
├── "connect"  →  ConnectScreen ───────┘ onJoin(JoinRequest)
│                 Parse optionnel d'un lien d'invitation
│                 https://host/group/<room>/?token=XXX
│                 → extrait serverUrl (wss://host/ws), roomName, token
│
└── "room"     →  RoomScreen(request)
                     ViewModel injecté par Koin : parametersOf(request)
```

Les paramètres de connexion **ne passent pas par la route** : un argument de chemin
vide (password et token sont exclusifs, l'un est donc toujours vide) ne matche pas
la route Navigation et provoquait un crash, et le mot de passe se retrouvait dans
l'état sauvegardé du back stack.

`JoinRequestHolder` survit aux changements de configuration mais pas à la mort du
process. Dans ce cas, la destination `room` revient à `connect`.

---

## Tests

```
app/src/test/java/fr/antoineassocies/galene/
└── integration/
    ├── GaleneTestClient.kt     # Client signaling pur JVM (sans android.util.Log)
    │                           # Réutilise les types de domain/protocol/
    └── GaleneIntegrationTest.kt # 8 tests contre le serveur réel
```

Les tests JVM (`src/test/`) peuvent réutiliser les classes `domain/protocol/Messages.kt`  
et `domain/SignalingEvent.kt` car elles n'ont aucune dépendance Android.  
`GaleneClient` lui-même ne peut pas être réutilisé (`android.util.Log`) — d'où  
`GaleneTestClient` qui en est un équivalent pur JVM.

Les tests instrumentés (couche WebRTC réelle, flux audio/vidéo) nécessitent un device
physique et seront dans `src/androidTest/` — non implémentés à ce stade.

---

## Pièges connus (et pourquoi le code est comme il est)

| Symptôme | Cause | Parade |
|----------|-------|--------|
| `SIGABRT` sur `network_thread` à la création d'une PeerConnection | Le NetworkMonitor de libwebrtc lève une `SecurityException` sans `ACCESS_NETWORK_STATE` | Permission déclarée au manifeste |
| Son distant inaudible | Android joue l'audio d'appel dans l'écouteur | `AudioRouter` : mode communication + haut-parleur |
| Micro muet en arrière-plan | Android 11+ coupe le micro des apps non visibles | `CallForegroundService` de type `microphone` |
| Connexion qui n'aboutit jamais après un ICE restart | Candidats appliqués à l'ancienne remote description | Buffer `settingRemote` dans `WebRTCManager` |
| Crash au `removeSink` d'une tuile vidéo | La PeerConnection a déjà libéré la piste | `runCatching` dans `VideoRenderer` ; UI prévenue avant `dispose` |
