package com.inkwell.notes.export.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to the saved vault password when the saved link changes: it is never forgotten,
 * stays tied to its link ([VaultPassword.boundAcross]) and applies only there ([VaultPassword.fits]).
 */
class VaultPasswordTest {
    private val a = "https://yourpitbox.com/notes/Lk7xQ2pLmn0"
    private val aWww = "https://www.yourpitbox.com/notes/Lk7xQ2pLmn0"
    private val typo = "https://yourpitbox.com/notes/Lk7xQ2pLmn"
    private val b = "https://yourpitbox.com/notes/Zz9yW8vUts1"
    private fun fp(link: String) = VaultUrl.endpointOrNull(link)!!.fingerprint

    /** Whether a password tied to [boundTo] is shown and sent once the saved link went [oldLink] -> [newLink]. */
    private fun appliesAfter(boundTo: String?, oldLink: String, newLink: String): Boolean =
        VaultPassword.fits(VaultPassword.boundAcross(boundTo, oldLink), VaultUrl.endpointOrNull(newLink)!!, savedLink = newLink)

    @Test
    fun `a link to another vault never receives the password`() {
        assertFalse("tied to the old link", appliesAfter(fp(a), oldLink = a, newLink = b))
        assertFalse("untied: belongs to the old link", appliesAfter(null, oldLink = a, newLink = b))
        assertFalse("tied to a mistyped link that was corrected", appliesAfter(fp(typo), oldLink = typo, newLink = a))
    }

    @Test
    fun `a half-edited link keeps the password, and going back restores it`() {
        // The link field is saved on every pause: A -> a truncated token -> A again.
        val bound = VaultPassword.boundAcross(null, oldLink = a)
        assertEquals(fp(a), bound)
        assertFalse(VaultPassword.fits(bound, VaultUrl.endpointOrNull(typo)!!, savedLink = typo))
        val back = VaultPassword.boundAcross(bound, oldLink = typo)
        assertEquals("still tied to A, not to the typo", fp(a), back)
        assertTrue(VaultPassword.fits(back, VaultUrl.endpointOrNull(a)!!, savedLink = a))
    }

    @Test
    fun `the same vault on its www host keeps the password`() {
        assertTrue(appliesAfter(fp(a), oldLink = a, newLink = aWww))
        assertTrue(appliesAfter(null, oldLink = aWww, newLink = a))
    }

    @Test
    fun `a password entered for the new link applies to it`() {
        // Typed while the new link was still in the field, before that link was saved.
        assertTrue(appliesAfter(fp(b), oldLink = a, newLink = b))
        // Typed while no link was set: it stays untied and is for the first link entered.
        assertNull(VaultPassword.boundAcross(null, oldLink = ""))
        assertTrue(appliesAfter(null, oldLink = "", newLink = b))
    }

    @Test
    fun `clearing the link keeps the password for its vault`() {
        assertEquals(fp(a), VaultPassword.boundAcross(fp(a), oldLink = a))
        assertEquals(fp(a), VaultPassword.boundAcross(null, oldLink = a))
        // Entering the same vault again brings it back; another vault never receives it.
        assertTrue(appliesAfter(fp(a), oldLink = "", newLink = a))
        assertFalse(appliesAfter(fp(a), oldLink = "", newLink = b))
    }
}
