package com.tfassbender.shoppinglist.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinksTest {

    private val token = "AbC_dEf-123456789012345678901234567890123"

    @Test
    fun `token from a full link`() {
        assertEquals(token, parseInviteToken("https://shop.example.com/invite/$token"))
    }

    @Test
    fun `token from a chat message containing the link`() {
        assertEquals(token, parseInviteToken("Join us: https://shop.example.com/invite/$token see you"))
    }

    @Test
    fun `raw token`() {
        assertEquals(token, parseInviteToken("  $token "))
    }

    @Test
    fun `nothing token-like`() {
        assertNull(parseInviteToken("hello there"))
        assertNull(parseInviteToken(""))
    }
}
