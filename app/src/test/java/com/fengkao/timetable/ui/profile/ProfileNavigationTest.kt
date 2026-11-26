package com.fengkao.timetable.ui.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileNavigationTest {
    @Test fun nestedBackReturnsToActualCaller() {
        val root = listOf(ProfilePage.HOME.name)
        val sync = pushProfilePage(root, ProfilePage.SYNC)
        val settings = pushProfilePage(sync, ProfilePage.TEACHING_SYNC)
        assertEquals(sync, popProfilePage(settings))
        val advanced = pushProfilePage(pushProfilePage(root, ProfilePage.APP), ProfilePage.ADVANCED)
        assertEquals(advanced, popProfilePage(pushProfilePage(advanced, ProfilePage.TEACHING_SYNC)))
    }
    @Test fun repeatedTapDoesNotAddDuplicatePage() {
        val stack = listOf(ProfilePage.HOME.name, ProfilePage.ACCOUNT.name)
        assertEquals(stack, pushProfilePage(stack, ProfilePage.ACCOUNT))
    }
    @Test fun backAtRootKeepsRoot() { assertEquals(listOf("HOME"), popProfilePage(listOf("HOME"))) }
    @Test fun savedRouteNamesRestoreAllDestinations() {
        ProfilePage.entries.forEach { page -> assertEquals(page, ProfilePage.valueOf(page.name)) }
    }
    @Test fun studentAndPhoneAccountsAreMasked() {
        assertEquals("0000****0000", maskAccount("000000000000"))
        assertEquals("000****0000", maskAccount("00000000000"))
        assertEquals("ab****ef", maskAccount("abcdef"))
        assertEquals("****", maskAccount("1234"))
        assertEquals("", maskAccount(""))
    }
}
