package ui.screen.chat

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The message text column.
 *
 * Discord puts every message body in a run in the same column: the first message
 * carries an avatar and a name, and the follow-on lines start exactly where that
 * first line of text starts. `DenseMessageBody` had `padding(start = 72.dp)`
 * hardcoded while `MessageBubbleFull` derived its own gutter from the tokens, and
 * nothing connected the two -- so every grouped message sat 8dp to the right of
 * the message it belonged to. Visible on any run of two or more messages from one
 * person, which is most of a conversation.
 *
 * The arithmetic is now a named constant both sides read, which is the only part
 * that can actually be wrong twice. This pins the number so a future token change
 * that moves it is a visible test failure rather than a slow visual drift.
 *
 * The three parts are the concept's own, from `.message-item`
 * (`padding: 6px 12px`, `gap: 16px`) and `.message-avatar-wrap` (`40px`):
 *
 *     12 + 40 + 16 = 68dp
 *
 * It used to be `OrbitSpace.lg (16) + 36 + OrbitSpace.md (12) = 64dp`, which
 * matched nothing in the concept: 16 is the *stream's* padding rather than the
 * row's, and the avatar was 4dp under size. The value was self-consistent --
 * which is all it ever was, given the 8dp split described above.
 */
class MessageTextGutterTest {

    @Test
    fun theGutterIsRowPaddingPlusAvatarPlusGap() {
        assertEquals(
            messageRowInset + MessageAvatarSize + messageRowGap,
            messageTextGutter
        )
    }

    @Test
    fun theGutterIs68Dp() {
        // Stated as a literal on purpose. This is the value both composables are
        // expected to agree on; if a token changes and this fails, that is the
        // moment to look at every message row rather than discover it in a chat.
        assertEquals(68.dp, messageTextGutter)
    }

    @Test
    fun theTokensStillAddUpTheWayTheNumberAssumes() {
        // The literal above is only meaningful while the parts are these values.
        // If someone retunes the spacing scale, this says so explicitly instead of
        // leaving a stale expected value to be "fixed" by editing the test.
        assertEquals(12.dp, messageRowInset)
        assertEquals(40.dp, MessageAvatarSize)
        assertEquals(16.dp, messageRowGap)
    }

    @Test
    fun theStreamInsetAndTheRowInsetMakeTheStreamPadding() {
        // `.messages-stream { padding: … 16px }` combined with
        // `.message-item { margin: 0 -12px }`: the row's own box starts 4dp in
        // from the stream's content edge, and its content starts a further 12dp
        // in. 4 + 12 must be the 16 the stylesheet asks for, or every message
        // silently shifts left.
        assertEquals(16.dp, messageStreamInset + messageRowInset)
    }

    @Test
    fun theHoverTimestampLandsWhereTheAvatarIsWithoutReservingSpace() {
        // The timestamp is drawn in the parent Box at 8dp, which is where the
        // avatar starts, so hovering swaps the avatar for the time in place.
        assertEquals(8.dp, hoverTimestampInset)
    }

    @Test
    fun theTimestampInsetIsLeftOfTheGutterSoItCannotPushTheBody() {
        // The invariant that matters, and the one that was broken.
        //
        // The timestamp used to be a 48dp child of the body Row with
        // `offset(x = -56.dp)`. An offset moves a child visually and does NOT give
        // the space back, so the Row reserved 48dp and every grouped body started
        // at gutter + 48 -- 48dp to the right of the message above it.
        //
        // It is now a sibling of the Row in the parent Box, aligned and inset, so
        // it contributes nothing to the Row's layout. The body starts at the
        // gutter and only at the gutter.
        assertTrue(
            hoverTimestampInset < messageTextGutter,
            "the timestamp must sit left of the text column, not inside it"
        )
        assertEquals(messageTextGutter, messageRowInset + MessageAvatarSize + messageRowGap)
    }
}
