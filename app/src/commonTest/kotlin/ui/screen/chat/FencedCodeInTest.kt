package ui.screen.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `fencedCodeIn` — the plain-body reader behind the `.code-block` card.
 *
 * This is a text-parsing function with no composable and no theme in it, which is
 * exactly why it is worth testing: everything else about the code block is
 * drawing, and a drawing that is wrong still looks like a code block. The parsing
 * has to be right or a code message renders as a paragraph containing backticks.
 *
 * The cases below are the ones that produce a *wrong* answer rather than a
 * missing one. Returning null when a body is not a lone fenced block is the safe
 * failure — it falls back to the plain body — so the tests that matter are the
 * ones about which block gets picked and what comes back.
 */
class FencedCodeInTest {

    @Test
    fun aLoneFenceIsFoundWithItsLanguage() {
        val result = fencedCodeIn("```kotlin\nval x = 1\n```")
        assertEquals("kotlin", result?.first)
        assertEquals("val x = 1", result?.second)
    }

    @Test
    fun anUnlabelledFenceFallsBackToCode() {
        // `.code-block-header` has a language label and a body that is not a
        // language still has to render *something* there rather than a blank.
        val result = fencedCodeIn("```\nplain\n```")
        assertEquals("code", result?.first)
        assertEquals("plain", result?.second)
    }

    @Test
    fun anUnterminatedFenceIsNotACodeBlock() {
        // The dangerous direction: half a fence has no closing line, and treating
        // the rest of the message as code would swallow whatever the sender wrote
        // after it.
        assertNull(fencedCodeIn("```kotlin\nval x = 1"))
    }

    @Test
    fun proseAfterTheClosingFenceIsNotACodeBlock() {
        // A message that quotes a snippet inside a sentence is a message, not a
        // snippet. Rendering it as a card would drop the sentence.
        assertNull(fencedCodeIn("```\ncode\n```\nand then I ran it"))
    }

    @Test
    fun proseBeforeTheFenceIsAllowed() {
        // The leading line is part of the message text, so the code is still the
        // only fenced thing in it.
        val result = fencedCodeIn("Here is the fix:\n```js\nfix()\n```")
        assertEquals("js", result?.first)
        assertEquals("fix()", result?.second)
    }

    @Test
    fun aBodyWithNoFenceIsNotACodeBlock() {
        assertNull(fencedCodeIn("just a normal message"))
        assertNull(fencedCodeIn(""))
    }

    @Test
    fun anUnrelatedBacktickRunIsNotAFence() {
        // `inline code` uses one backtick. Two of them in a row are not a fence
        // and must not open one.
        assertNull(fencedCodeIn("use `code` like this"))
    }

    @Test
    fun theClosingFenceIsNotMistakenForTheOpeningOne() {
        // A single fence with nothing after it has no pair. If the search for the
        // close ever matched the *open* line, this would return an empty code
        // block rather than null.
        assertNull(fencedCodeIn("```\n"))
    }

    @Test
    fun anIndentedFenceStillCountsAndItsIndentationIsKept() {
        // Matrix clients indent a fenced block inside a quoted or bulleted body,
        // so the fence is matched on the trimmed line. The code's own leading
        // whitespace is NOT trimmed away: inside a code block, indentation is
        // content, and a body of `  if (x) {` has to render as two levels of
        // indent or the snippet is wrong.
        val result = fencedCodeIn("  ```\n  if (x) {\n    y()\n  }\n  ```")
        assertEquals("code", result?.first)
        assertEquals("  if (x) {\n    y()\n  }", result?.second)
    }

    @Test
    fun aVeryLongLanguageTokenIsTruncated() {
        // The label is a UI string in an 11px uppercase header, so an unbounded
        // one would stretch the header across the message.
        val result = fencedCodeIn("```" + "x".repeat(80) + "\nbody\n```")
        assertEquals(24, result?.first?.length)
    }

    @Test
    fun multipleLinesOfCodeArePreserved() {
        val result = fencedCodeIn("```\none\ntwo\nthree\n```")
        assertEquals("one\ntwo\nthree", result?.second)
        assertTrue((result?.second?.lines()?.size ?: 0) == 3)
    }
}
