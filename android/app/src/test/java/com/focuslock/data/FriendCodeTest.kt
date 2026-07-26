package com.focuslock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendCodeTest {

    @Test
    fun `generate is deterministic for the same uid`() {
        val a = FriendCode.generate("uid-123")
        val b = FriendCode.generate("uid-123")
        assertEquals(a, b)
    }

    @Test
    fun `generate differs for different uids`() {
        val a = FriendCode.generate("uid-123")
        val b = FriendCode.generate("uid-456")
        assertFalse(a == b)
    }

    @Test
    fun `generate is 8 characters from the expected alphabet`() {
        val code = FriendCode.generate("some-uid")
        assertEquals(8, code.length)
        assertTrue(code.all { it.isLetterOrDigit() })
    }

    @Test
    fun `generated code is always valid`() {
        assertTrue(FriendCode.isValid(FriendCode.generate("another-uid")))
    }

    @Test
    fun `normalize uppercases and strips whitespace`() {
        assertEquals("ABCD1234", FriendCode.normalize(" abcd1234 "))
    }

    @Test
    fun `normalize strips non-alphanumeric characters`() {
        assertEquals("ABCD1234", FriendCode.normalize("abcd-1234"))
    }

    @Test
    fun `normalize folds ambiguous letters onto their canonical digit`() {
        assertEquals("A0AB11", FriendCode.normalize("aOabIL"))
    }

    @Test
    fun `isValid rejects wrong length`() {
        assertFalse(FriendCode.isValid("ABC123"))
    }

    @Test
    fun `isValid rejects characters outside the alphabet after normalization`() {
        // 'U' is excluded from the Crockford alphabet and has no fold rule.
        assertFalse(FriendCode.isValid("ABCDEFGU"))
    }

    @Test
    fun `isValid accepts a well-formed code regardless of case`() {
        val code = FriendCode.generate("uid-789")
        assertTrue(FriendCode.isValid(code.lowercase()))
    }
}
