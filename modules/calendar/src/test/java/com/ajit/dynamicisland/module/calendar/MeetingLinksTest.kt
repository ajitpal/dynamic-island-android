package com.ajit.dynamicisland.module.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingLinksTest {
    @Test fun findsCommonProviders() {
        assertEquals("https://meet.google.com/abc-defg-hij", MeetingLinks.find(null, "Join with Google Meet: https://meet.google.com/abc-defg-hij\nOr dial…"))
        assertEquals("https://us02web.zoom.us/j/8123456789?pwd=xyz", MeetingLinks.find("https://us02web.zoom.us/j/8123456789?pwd=xyz.", null))
        assertEquals("Zoom", MeetingLinks.provider("https://us02web.zoom.us/j/1"))
        assertEquals("Microsoft Teams", MeetingLinks.provider(MeetingLinks.find("<https://teams.microsoft.com/l/meetup-join/19%3ameeting_x/0?context=1>")!!))
    }

    @Test fun ignoresOtherLinks() {
        assertNull(MeetingLinks.find("Agenda: https://docs.google.com/document/d/123", "Room 4B"))
        assertNull(MeetingLinks.find(null, null))
    }
}
