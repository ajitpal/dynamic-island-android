package com.ajit.dynamicisland.module.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadTest {
    @Test fun links() {
        val p = Payload.parse("https://www.example.com/menu?table=4") as Payload.Url
        assertEquals("www.example.com", p.host); assertEquals("example.com", p.title)
        assertTrue(Payload.parse("www.example.org") is Payload.Url)
        assertTrue(Payload.parse("not a link") is Payload.Text)
    }

    @Test fun wifiWithEscapes() {
        val w = Payload.parse("WIFI:T:WPA;S:Cafe;Guest;P:pa\\;ss\\:word;H:false;;") as Payload.Wifi
        assertEquals("Cafe", w.ssid)            // ';' ends a field unless escaped
        val e = Payload.parse("WIFI:S:My\\;Home;T:WPA;P:pa\\;ss\\:word;;") as Payload.Wifi
        assertEquals("My;Home", e.ssid); assertEquals("pa;ss:word", e.password); assertEquals("WPA", e.security)
        assertTrue(!e.detail.contains("pa;ss"))  // the password is never shown
    }

    @Test fun openAndWpa3Wifi() {
        val o = Payload.parse("WIFI:S:Lobby;T:nopass;;") as Payload.Wifi
        assertNull(o.password); assertEquals("Open", o.security)
        assertEquals("WPA3", (Payload.parse("WIFI:T:SAE;S:Home;P:12345678;;") as Payload.Wifi).security)
    }

    @Test fun upiPayment() {
        val u = Payload.parse("upi://pay?pa=shop.name@okaxis&pn=Corner%20Store&am=149.50&cu=INR&tn=Order%2042") as Payload.Upi
        assertEquals("shop.name@okaxis", u.vpa); assertEquals("Corner Store", u.name); assertEquals("149.50", u.amount)
        assertEquals("Pay Corner Store · ₹149.50", u.title)
        assertTrue(u.detail.contains("shop.name@okaxis"))  // who gets the money is always shown
    }

    @Test fun upiRejectsBadIdsAndAmounts() {
        assertTrue(Payload.parse("upi://pay?pa=not-an-id&pn=X") is Payload.Text)
        assertNull((Payload.parse("upi://pay?pa=a.b@ybl&am=1e9") as Payload.Upi).amount)
    }

    @Test fun phoneMailSmsGeo() {
        assertEquals("+911234567890", (Payload.parse("tel:+911234567890") as Payload.Phone).number)
        val m = Payload.parse("mailto:hello@example.com?subject=Hi%20there") as Payload.Email
        assertEquals("hello@example.com", m.to); assertEquals("Hi there", m.subject)
        val s = Payload.parse("SMSTO:12345:See you at 7") as Payload.Sms
        assertEquals("12345", s.number); assertEquals("See you at 7", s.body)
        val g = Payload.parse("geo:19.0760,72.8777?q=Gateway") as Payload.Geo
        assertEquals(19.076, g.lat, 1e-6); assertEquals("Gateway", g.query)
        assertTrue(Payload.parse("geo:200,0") is Payload.Text)
    }

    @Test fun contacts() {
        val v = Payload.parse("BEGIN:VCARD\nVERSION:3.0\nN:Sharma;Asha\nFN:Asha Sharma\nTEL;TYPE=CELL:+91 98765 43210\nEMAIL:asha@example.com\nORG:Acme\nEND:VCARD") as Payload.Contact
        assertEquals("Asha Sharma", v.name); assertEquals("+91 98765 43210", v.phone); assertEquals("Acme", v.org)
        val m = Payload.parse("MECARD:N:Rao,Vikram;TEL:+911112223334;EMAIL:v@example.com;;") as Payload.Contact
        assertEquals("Vikram Rao", m.name); assertEquals("+911112223334", m.phone)
    }

    @Test fun productBarcodes() {
        assertTrue(Payload.parse("8901234567890", "EAN_13") is Payload.Product)
        assertTrue(Payload.parse("8901234567890", "QR_CODE") is Payload.Text)
    }

    @Test fun actionsStartWithTheUsefulOne() {
        assertEquals("open", Actions.forPayload(Payload.parse("https://example.com")).first().id)
        assertEquals("wifi", Actions.forPayload(Payload.parse("WIFI:S:x;T:WPA;P:12345678;;")).first().id)
        assertEquals("Pay", Actions.forPayload(Payload.parse("upi://pay?pa=a.b@ybl")).first().label)
        assertTrue(Actions.forPayload(Payload.parse("hello")).size <= 3)
    }
}
