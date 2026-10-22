package fr.antoineassocies.galene.domain

import fr.antoineassocies.galene.domain.model.GaleneLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interpretation of what the user types or pastes on the home screen. */
class GaleneLinkTest {

    private fun group(input: String) = GaleneLink.parse(input) as GaleneLink.Group

    @Test
    fun `bare host name - https server`() {
        val link = GaleneLink.parse("galene.example.org")
        assertTrue(link is GaleneLink.Server)
        assertEquals("https://galene.example.org/", (link as GaleneLink.Server).base.toString())
    }

    @Test
    fun `address with arbitrary path - server without path`() {
        val link = GaleneLink.parse("https://galene.example.org:8443/foo") as GaleneLink.Server
        assertEquals("https://galene.example.org:8443/", link.base.toString())
    }

    @Test
    fun `group link`() {
        val link = group("https://galene.example.org/group/ma-salle/")
        assertEquals("https://galene.example.org/group/ma-salle/", link.target.location)
        assertEquals("https://galene.example.org/group/ma-salle/.status", link.target.statusUrl)
        assertNull(link.target.token)
    }

    @Test
    fun `invitation link with token`() {
        val link = group("https://galene.example.org/group/ma-salle/?token=ABC123")
        assertEquals("https://galene.example.org/group/ma-salle/", link.target.location)
        assertEquals("ABC123", link.target.token)
        assertEquals("https://galene.example.org/", link.base.toString())
    }

    @Test
    fun `subgroup and link without trailing slash`() {
        val link = group("galene.example.org/group/cours/td1")
        assertEquals("https://galene.example.org/group/cours/td1/", link.target.location)
    }

    @Test
    fun `group segment without a name - server`() {
        assertTrue(GaleneLink.parse("https://galene.example.org/group/") is GaleneLink.Server)
    }

    @Test
    fun `empty or invalid input`() {
        assertNull(GaleneLink.parse("   "))
        assertNull(GaleneLink.parse("not a url"))
    }

    @Test
    fun `token never appears in logs`() {
        val link = group("https://galene.example.org/group/s/?token=SECRET")
        assertTrue("SECRET" !in link.target.toString())
    }

    @Test
    fun `group link found in shared text`() {
        val link = GaleneLink.findGroup("Join me in “Room” on Galène\nhttps://galene.example.org/group/room/?token=ABC")
        assertEquals("https://galene.example.org/group/room/", link?.target?.location)
        assertEquals("ABC", link?.target?.token)
    }

    @Test
    fun `shared text without group link`() {
        assertNull(GaleneLink.findGroup("see https://galene.example.org/ tomorrow"))
        assertNull(GaleneLink.findGroup("no link here"))
    }
}
