package fr.antoineassocies.galene.domain

import fr.antoineassocies.galene.domain.model.GaleneLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interprétation de ce que l'utilisateur saisit ou colle sur l'écran d'accueil. */
class GaleneLinkTest {

    private fun group(input: String) = GaleneLink.parse(input) as GaleneLink.Group

    @Test
    fun `nom d'hôte seul - serveur en https`() {
        val link = GaleneLink.parse("galene.example.org")
        assertTrue(link is GaleneLink.Server)
        assertEquals("https://galene.example.org/", (link as GaleneLink.Server).base.toString())
    }

    @Test
    fun `adresse avec chemin quelconque - serveur sans chemin`() {
        val link = GaleneLink.parse("https://galene.example.org:8443/foo") as GaleneLink.Server
        assertEquals("https://galene.example.org:8443/", link.base.toString())
    }

    @Test
    fun `lien de groupe`() {
        val link = group("https://galene.example.org/group/ma-salle/")
        assertEquals("https://galene.example.org/group/ma-salle/", link.target.location)
        assertEquals("https://galene.example.org/group/ma-salle/.status", link.target.statusUrl)
        assertNull(link.target.token)
    }

    @Test
    fun `lien d'invitation avec token`() {
        val link = group("https://galene.example.org/group/ma-salle/?token=ABC123")
        assertEquals("https://galene.example.org/group/ma-salle/", link.target.location)
        assertEquals("ABC123", link.target.token)
        assertEquals("https://galene.example.org/", link.base.toString())
    }

    @Test
    fun `sous-groupe et lien sans barre finale`() {
        val link = group("galene.example.org/group/cours/td1")
        assertEquals("https://galene.example.org/group/cours/td1/", link.target.location)
    }

    @Test
    fun `segment group sans nom - serveur`() {
        assertTrue(GaleneLink.parse("https://galene.example.org/group/") is GaleneLink.Server)
    }

    @Test
    fun `saisie vide ou invalide`() {
        assertNull(GaleneLink.parse("   "))
        assertNull(GaleneLink.parse("pas une url"))
    }

    @Test
    fun `le token n'apparaît pas dans les logs`() {
        val link = group("https://galene.example.org/group/s/?token=SECRET")
        assertTrue("SECRET" !in link.target.toString())
    }
}
