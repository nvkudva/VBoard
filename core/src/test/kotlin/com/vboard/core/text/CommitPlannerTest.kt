package com.vboard.core.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommitPlannerTest {

    @Test
    fun `adds space between words`() {
        assertEquals(" world", CommitPlanner.joinForInsertion("hello", "world"))
    }

    @Test
    fun `no space at field start`() {
        assertEquals("world", CommitPlanner.joinForInsertion("", "world"))
    }

    @Test
    fun `no space after existing space or newline`() {
        assertEquals("world", CommitPlanner.joinForInsertion("hello ", "world"))
        assertEquals("world", CommitPlanner.joinForInsertion("hello\n", "world"))
    }

    @Test
    fun `space added after sentence punctuation`() {
        assertEquals(" World", CommitPlanner.joinForInsertion("Hi.", "World"))
    }

    @Test
    fun `no space before punctuation insert`() {
        assertEquals(", right", CommitPlanner.joinForInsertion("hello", ", right"))
    }

    @Test
    fun `no space after open bracket or quote`() {
        assertEquals("quoted", CommitPlanner.joinForInsertion("he said \"", "quoted"))
        assertEquals("inner", CommitPlanner.joinForInsertion("(", "inner"))
    }

    @Test
    fun `double space period applies after word`() {
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("hello "))
        assertTrue(CommitPlanner.doubleSpacePeriodApplies("it's over) "))
    }

    @Test
    fun `double space period does not apply after punctuation or space`() {
        assertFalse(CommitPlanner.doubleSpacePeriodApplies("hello. "))
        assertFalse(CommitPlanner.doubleSpacePeriodApplies("hello  "))
        assertFalse(CommitPlanner.doubleSpacePeriodApplies(""))
        assertFalse(CommitPlanner.doubleSpacePeriodApplies("a"))
    }
}

class TextDiffTest {

    @Test
    fun `identical strings are noop`() {
        val r = TextDiff.replacement("hello", "hello")
        assertTrue(r.isNoop)
        assertEquals(5, r.keepPrefixLength)
    }

    @Test
    fun `appending only inserts`() {
        val r = TextDiff.replacement("hel", "hello")
        assertEquals(3, r.keepPrefixLength)
        assertEquals(0, r.deleteCount)
        assertEquals("lo", r.insertText)
    }

    @Test
    fun `tail change deletes and inserts`() {
        val r = TextDiff.replacement("send it to jon", "send it to mary")
        assertEquals("send it to ".length, r.keepPrefixLength)
        assertEquals(3, r.deleteCount)
        assertEquals("mary", r.insertText)
    }

    @Test
    fun `complete replacement`() {
        val r = TextDiff.replacement("abc", "xyz")
        assertEquals(0, r.keepPrefixLength)
        assertEquals(3, r.deleteCount)
        assertEquals("xyz", r.insertText)
    }

    @Test
    fun `empty current just inserts`() {
        val r = TextDiff.replacement("", "hi")
        assertEquals(0, r.keepPrefixLength)
        assertEquals(0, r.deleteCount)
        assertEquals("hi", r.insertText)
    }

    @Test
    fun `never splits surrogate pairs`() {
        val current = "hi 😀"
        val target = "hi 😁"
        val r = TextDiff.replacement(current, target)
        assertEquals(3, r.keepPrefixLength)
        assertEquals(2, r.deleteCount)
        assertEquals("😁", r.insertText)
    }

    @Test
    fun `applying replacement reproduces target`() {
        val cases = listOf(
            "" to "hello",
            "hello" to "",
            "the quick brown" to "the quick red fox",
            "abc" to "abcd",
            "same" to "same",
        )
        for ((current, target) in cases) {
            val r = TextDiff.replacement(current, target)
            val applied = current.substring(0, r.keepPrefixLength) + r.insertText
            assertEquals(target, applied, "case: '$current' -> '$target'")
            assertEquals(current.length - r.keepPrefixLength, r.deleteCount)
        }
    }
}
