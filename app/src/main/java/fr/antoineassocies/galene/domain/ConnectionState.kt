package fr.antoineassocies.galene.domain

/**
 * État de la connexion de signaling (WebSocket + handshake), indépendant des médias.
 *
 * [Connected] signifie que le handshake a réussi ; l'entrée dans le groupe (join) est
 * signalée séparément par [SignalingEvent.Joined].
 */
enum class ConnectionState { Connecting, Connected, Disconnected }
