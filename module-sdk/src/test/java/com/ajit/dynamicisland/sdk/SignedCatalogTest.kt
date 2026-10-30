package com.ajit.dynamicisland.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class SignedCatalogTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    /** Same tool and flags CI uses: `openssl dgst -sha256 -sign key.pem`. */
    @Test fun acceptsAnOpensslSignature() {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(res("rsa.pub.der")))
        val data = res("catalog.json")
        assertTrue(SignedCatalog.verify(data, res("catalog.json.sig"), key))
        val c = SignedCatalog.parse(String(data))
        assertEquals(1793400000L, c.serial)
        assertEquals("com.example.demo", c.modules.single().pkg)
    }

    @Test fun rejectsAChangedFile() {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(res("rsa.pub.der")))
        val data = res("catalog.json").clone().also { it[20] = (it[20] + 1).toByte() }
        assertFalse(SignedCatalog.verify(data, res("catalog.json.sig"), key))
    }

    @Test fun rejectsAnotherKey() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().public
        assertFalse(SignedCatalog.verify(res("catalog.json"), res("catalog.json.sig"), other))
    }

    @Test fun acceptsEcKeys() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.genKeyPair()
        val data = "hello".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }
        assertTrue(SignedCatalog.verify(data, sig, kp.public))
    }

    @Test fun readsTheOldPlainList() {
        val c = SignedCatalog.parse("""[{"id":"x","name":"X","package":"a.b","summary":"s","download":"https://x","certSha256":"${"b".repeat(64)}"}]""")
        assertEquals(0L, c.serial); assertEquals("🧩", c.modules.single().icon)
    }

    @Test(expected = IllegalArgumentException::class) fun refusesPlainHttpDownloads() {
        SignedCatalog.parse("""{"format":1,"serial":1,"modules":[{"id":"x","name":"X","package":"a.b","summary":"s","download":"http://x","certSha256":"${"b".repeat(64)}"}]}""")
    }

    @Test fun newerNeverRollsBack() {
        val old = SignedCatalog.Catalog(5, emptyList()); val new = SignedCatalog.Catalog(9, emptyList())
        assertEquals(new, SignedCatalog.newer(old, new))
        assertEquals(new, SignedCatalog.newer(new, old))
        assertEquals(old, SignedCatalog.newer(old, null))
        assertNull(SignedCatalog.newer(null, null))
    }
}
