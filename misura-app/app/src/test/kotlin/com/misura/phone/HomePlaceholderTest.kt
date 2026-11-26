package com.misura.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** The request field's placeholder (spec Home 2): Home with lines asks, empty Home invites typing, the app search says so. */
class HomePlaceholderTest {
    @Test fun emptyHomeInvitesTyping() = assertEquals("Type a request…", homePlaceholder(searchingApps = false, homeHasLines = false))
    @Test fun homeWithLinesAsks() = assertEquals("What should we do?", homePlaceholder(searchingApps = false, homeHasLines = true))
    @Test fun appSearchWinsOverLines() = assertEquals("Search apps", homePlaceholder(searchingApps = true, homeHasLines = true))
}
