package ui.screen.chat

import androidx.compose.ui.unit.dp
import ui.theme.OrbitSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The message text column.
 *
 * Discord puts every message body in a run in the same column: the first message
 * carries an avatar and a name, and the follow-on lines start exactly where that
 * first line of text starts. `DenseMessageBody` had `padding(start = 72.dp)`
 * hardcoded while `MessageBubbleFull` derived its gutter from the tokens, and
 * nothing connected the two -- so every grouped message sat 8dp to the right of
 * the message it belonged to. Visible on any run of two or more messages from one
 * person, which is most of a conversation.
 *
 * The arithmetic is now a named constant both sides read, which is the only part
 * that can actually be wrong twice. This pins the number so a future token change
 * that moves it is a visible test failure rather than a slow visual drift.
 */
class MessageTextGutterTest {

    @Test
    fun theGutterIsRowPaddingPlusAvatarPlusGap() {
        assertEquals(
            OrbitSpace.lg + MessageAvatarSize + OrbitSpace.md,
            messageTextGutter
        )
    }

    @Test
    fun theGutterIs64Dp() {
        // Stated as a literal on purpose. This is the value both composables are
        // expected to agree on; if a token changes and this fails, that is the
        // moment to look at every message row rather than discover it in a chat.
        assertEquals(64.dp, messageTextGutter)
    }

    @Test
    fun theTokensStillAddUpTheWayTheNumberAssumes() {
        // The literal above is only meaningful while the parts are these values.
        // If someone retunes the spacing scale, this says so explicitly instead of
        // leaving a stale expected value to be "fixed" by editing the test.
        assertEquals(16.dp, OrbitSpace.lg)
        assertEquals(36.dp, MessageAvatarSize)
        assertEquals(12.dp, OrbitSpace.md)
    }

    @Test
    fun theHoverTimestampLandsWhereTheAvatarIs() {
        // DenseMessageBody draws the hover timestamp in a 48dp box offset by
        // -56dp. With the gutter at 64 that puts the box at 8dp, which is where
        // the avatar starts -- so hovering swaps the avatar for the time in place
        // rather than shoving the timestamp somewhere else.
        //
        // This is why the -56 was already right while the padding was wrong, and
        // why the text looked wrong while the timestamp looked fine.
        assertEquals(8.dp, messageTextGutter - 56.dp)
    }
}