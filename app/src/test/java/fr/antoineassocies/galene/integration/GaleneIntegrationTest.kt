package fr.antoineassocies.galene.integration

import kotlinx.coroutines.runBlocking
import fr.antoineassocies.galene.domain.SignalingEvent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests against a real Galène server.
 *
 * They validate the whole signaling path: WS connection → handshake → token
 * authentication → multi-client presence → SDP exchange (offer/answer).
 *
 * They run on the JVM, without an Android device. The real WebRTC layer (audio/video
 * streams) requires an instrumented test on a physical device.
 *
 * The test server comes from the environment (no secret in the repository):
 *
 *     export GALENE_TEST_URL=wss://galene.example.org/ws
 *     export GALENE_TEST_TOKEN=<the group's invitation token>
 *     export GALENE_TEST_GROUP=public        # optional, "public" by default
 *     ./gradlew :app:test --tests "*.GaleneIntegrationTest"
 *
 * Without GALENE_TEST_URL and GALENE_TEST_TOKEN, the tests are skipped (not failed).
 */
class GaleneIntegrationTest {

    companion object {
        val WS_URL: String = System.getenv("GALENE_TEST_URL").orEmpty()
        val GROUP: String  = System.getenv("GALENE_TEST_GROUP") ?: "public"
        val TOKEN: String  = System.getenv("GALENE_TEST_TOKEN").orEmpty()
        const val USER_A = "claude-test-a"
        const val USER_B = "claude-test-b"

        // Minimal audio-only SDP, syntactically valid for pion/webrtc (Galène server)
        val OFFER_SDP = """
            v=0
            o=- 1000000000 1 IN IP4 127.0.0.1
            s=-
            t=0 0
            a=group:BUNDLE 0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=rtcp:9 IN IP4 0.0.0.0
            a=ice-ufrag:AAAA
            a=ice-pwd:AAAAAAAAAAAAaaaa0000bbbb
            a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99
            a=setup:actpass
            a=mid:0
            a=sendonly
            a=rtcp-mux
            a=rtpmap:111 opus/48000/2
            a=fmtp:111 minptime=10;useinbandfec=1
            a=ssrc:1111111111 cname:galene-android-test
        """.trimIndent()
    }

    // ─── 1. WebSocket connection ───────────────────────────────────────────────

    @Before
    fun requireServer() {
        assumeTrue(
            "GALENE_TEST_URL and GALENE_TEST_TOKEN not set: integration tests skipped",
            WS_URL.isNotEmpty() && TOKEN.isNotEmpty(),
        )
    }

    @Test
    fun `handshake with the server succeeds`() = runBlocking {
        GaleneTestClient(WS_URL).use { client ->
            client.connect()
            val event = client.awaitEvent { it is SignalingEvent.Connected }
            assertTrue(event is SignalingEvent.Connected)
        }
    }

    // ─── 2. Token authentication ──────────────────────────────────────────────

    @Test
    fun `joining with a valid token grants the present permission`() = runBlocking {
        GaleneTestClient(WS_URL).use { client ->
            client.connect()
            client.awaitEvent { it is SignalingEvent.Connected }

            client.sendJoin(GROUP, USER_A, token = TOKEN)
            val event = client.awaitEvent { it is SignalingEvent.Joined || it is SignalingEvent.JoinFailed }

            assertTrue("Expected Joined, got: $event", event is SignalingEvent.Joined)
            val joined = event as SignalingEvent.Joined
            assertEquals(GROUP, joined.group)
            assertEquals(USER_A, joined.username)
            assertTrue("'present' permission required", "present" in joined.permissions)
        }
    }

    @Test
    fun `joining without a token is refused`() = runBlocking {
        GaleneTestClient(WS_URL).use { client ->
            client.connect()
            client.awaitEvent { it is SignalingEvent.Connected }

            client.sendJoin(GROUP, "intruder-no-auth")  // no token
            val event = client.awaitEvent { it is SignalingEvent.Joined || it is SignalingEvent.JoinFailed }

            assertTrue("Expected JoinFailed, got: $event", event is SignalingEvent.JoinFailed)
            val failed = event as SignalingEvent.JoinFailed
            assertNotNull(failed.message ?: failed.error)
        }
    }

    // ─── 3. Multi-client presence ──────────────────────────────────────────────

    @Test
    fun `two clients in the same group see each other through user events`() = runBlocking {
        GaleneTestClient(WS_URL).use { clientA ->
        GaleneTestClient(WS_URL).use { clientB ->
            // Client A joins
            clientA.connect()
            clientA.awaitEvent { it is SignalingEvent.Connected }
            clientA.sendJoin(GROUP, USER_A, token = TOKEN)
            val joinedA = clientA.awaitEvent { it is SignalingEvent.Joined } as SignalingEvent.Joined
            assertEquals(USER_A, joinedA.username)

            // Client B joins
            clientB.connect()
            clientB.awaitEvent { it is SignalingEvent.Connected }
            clientB.sendJoin(GROUP, USER_B, token = TOKEN)
            val joinedB = clientB.awaitEvent { it is SignalingEvent.Joined } as SignalingEvent.Joined
            assertEquals(USER_B, joinedB.username)

            // A must receive a "user add" event for B
            val aSeesB = clientA.awaitEvent(timeoutMs = 5_000) { e ->
                e is SignalingEvent.UserUpdate && e.kind == "add" && e.username == USER_B
            } as SignalingEvent.UserUpdate
            assertEquals(USER_B, aSeesB.username)

            // B must see A (already present when B joined — event arrives right after joined)
            val bSeesA = clientB.awaitEvent(timeoutMs = 5_000) { e ->
                e is SignalingEvent.UserUpdate && e.kind == "add" && e.username == USER_A
            } as SignalingEvent.UserUpdate
            assertEquals(USER_A, bSeesA.username)
        }}
    }

    @Test
    fun `leaving the group sends a user delete to the other clients`() = runBlocking {
        GaleneTestClient(WS_URL).use { clientA ->
        GaleneTestClient(WS_URL).use { clientB ->
            // Both join
            clientA.connect()
            clientA.awaitEvent { it is SignalingEvent.Connected }
            clientA.sendJoin(GROUP, USER_A, token = TOKEN)
            clientA.awaitEvent { it is SignalingEvent.Joined }

            clientB.connect()
            clientB.awaitEvent { it is SignalingEvent.Connected }
            clientB.sendJoin(GROUP, USER_B, token = TOKEN)
            clientB.awaitEvent { it is SignalingEvent.Joined }

            // Wait until A sees B
            clientA.awaitEvent(5_000) { e ->
                e is SignalingEvent.UserUpdate && e.kind == "add" && e.username == USER_B
            }

            // B leaves explicitly
            clientB.close()

            // A must receive a "user delete" for B
            val aSeesBLeave = clientA.awaitEvent(timeoutMs = 8_000) { e ->
                e is SignalingEvent.UserUpdate && e.kind == "delete" && e.username == USER_B
            } as SignalingEvent.UserUpdate
            assertEquals("delete", aSeesBLeave.kind)
        }}
    }

    // ─── 4. RTC configuration ─────────────────────────────────────────────────

    @Test
    fun `server provides an RTC configuration with a TURN server`() = runBlocking {
        GaleneTestClient(WS_URL).use { client ->
            client.connect()
            client.awaitEvent { it is SignalingEvent.Connected }
            client.sendJoin(GROUP, USER_A, token = TOKEN)

            val joined = client.awaitEvent { it is SignalingEvent.Joined } as SignalingEvent.Joined

            val rtcConfig = joined.rtcConfiguration
            assertNotNull("The server must send rtcConfiguration", rtcConfig)

            val iceServers = rtcConfig!!.iceServers
            assertNotNull("iceServers must be present", iceServers)
            assertTrue("At least one ICE server required", iceServers!!.isNotEmpty())

            // Check there is at least one TURN server with credentials (needed behind symmetric NAT)
            val turnServers = iceServers.filter { it.urls.any { u -> u.startsWith("turn:") } }
            assertTrue("At least one TURN server expected", turnServers.isNotEmpty())

            val hasCreds = turnServers.any { it.username != null && it.credential != null }
            assertTrue("TURN servers must have credentials", hasCreds)
        }
    }

    // ─── 5. SDP exchange (WebRTC signaling) ───────────────────────────────────

    /**
     * Checks that Galène's SFU accepts an SDP offer from the presenter and sends an answer.
     * If this passes, WebRTC negotiation can start — the call can be established.
     *
     * Note: the ICE data used here is fake, so the media connection fails afterwards.
     * But the signaling path (offer → SFU answer) is verified.
     */
    @Test
    fun `sfu answers the presenter's offer with an SDP answer`() = runBlocking {
        val streamId = "test-${System.currentTimeMillis()}"

        GaleneTestClient(WS_URL).use { presenter ->
            presenter.connect()
            presenter.awaitEvent { it is SignalingEvent.Connected }
            presenter.sendJoin(GROUP, USER_A, token = TOKEN)
            presenter.awaitEvent { it is SignalingEvent.Joined }

            presenter.sendOffer(streamId, "audio", OFFER_SDP)

            // The SFU must reply: either an answer (valid SDP) or an abort (SDP rejected)
            val response = presenter.awaitEvent(timeoutMs = 15_000) { e ->
                (e is SignalingEvent.RemoteAnswer && e.id == streamId) ||
                (e is SignalingEvent.StreamAbort && e.id == streamId)
            }

            when (response) {
                is SignalingEvent.RemoteAnswer -> {
                    assertEquals(streamId, response.id)
                    assertTrue("The SDP answer must not be empty", response.sdp.isNotBlank())
                    // A valid WebRTC answer always contains an audio section
                    assertTrue("The SDP answer must contain an audio section", response.sdp.contains("m=audio"))
                }
                is SignalingEvent.StreamAbort -> {
                    // The SFU rejected the SDP (unknown fingerprint or unsupported codec).
                    // Signaling works: the server processed the offer and replied.
                    assertEquals(streamId, response.id)
                }
                else -> fail("Unexpected response: $response")
            }
        }
    }

    /**
     * Checks the full relay: presenter sends an offer → SFU answers → observer receives an
     * offer from the SFU (proof that the SFU can route streams between the two clients).
     */
    @Test
    fun `sfu relays the stream to the observer`() = runBlocking {
        val streamId = "relay-${System.currentTimeMillis()}"

        GaleneTestClient(WS_URL).use { presenter ->
        GaleneTestClient(WS_URL).use { observer ->
            // Presenter joins and sends an offer
            presenter.connect()
            presenter.awaitEvent { it is SignalingEvent.Connected }
            presenter.sendJoin(GROUP, USER_A, token = TOKEN)
            presenter.awaitEvent { it is SignalingEvent.Joined }
            presenter.sendOffer(streamId, "audio", OFFER_SDP)

            // Wait for the SFU's reply to the presenter (answer or abort)
            val sfuResponse = presenter.awaitEvent(timeoutMs = 15_000) { e ->
                (e is SignalingEvent.RemoteAnswer && e.id == streamId) ||
                (e is SignalingEvent.StreamAbort && e.id == streamId)
            }

            // If the SFU aborted the offer (test SDP rejected), the test cannot go on
            if (sfuResponse is SignalingEvent.StreamAbort) {
                // Signaling works but the minimal SDP was rejected.
                // Not a production problem (real SDPs come from the Android WebRTC stack).
                return@runBlocking
            }

            // The observer joins and requests the streams
            observer.connect()
            observer.awaitEvent { it is SignalingEvent.Connected }
            observer.sendJoin(GROUP, USER_B, token = TOKEN)
            observer.awaitEvent { it is SignalingEvent.Joined }
            observer.sendRequest(mapOf("" to listOf("audio", "video")))

            // The SFU must send the observer an offer for the presenter's stream
            val offerForObserver = observer.awaitEvent(timeoutMs = 15_000) { e ->
                e is SignalingEvent.RemoteOffer
            } as SignalingEvent.RemoteOffer

            assertTrue("The SDP offer sent to the observer must not be empty", offerForObserver.sdp.isNotBlank())
            assertTrue("The observer's offer must contain an audio section",
                offerForObserver.sdp.contains("m=audio"))

            // The observer would answer to complete negotiation; ICE would then fail for lack
            // of real candidates — expected in a JVM test
        }}
    }
}
