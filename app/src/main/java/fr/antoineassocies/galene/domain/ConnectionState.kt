package fr.antoineassocies.galene.domain

/**
 * State of the signaling connection (WebSocket + handshake), independent of media.
 *
 * [Connected] means the handshake succeeded; joining the group is reported separately
 * by [SignalingEvent.Joined].
 */
enum class ConnectionState { Connecting, Connected, Disconnected }
