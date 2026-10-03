package ui.screen.chat

import repository.MemberRoleInfo
import ui.model.OrbitUser
import ui.model.UserStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The duplicate-LazyColumn-key crash.
 *
 * A user in a group call reported a tick every three seconds. It was Windows'
 * error-dialog beep: Compose threw `Key "@user:server" was already used` out of
 * the lazy layout, and `MatrixCallsRepository`'s roster poller re-emits the
 * member list on `delay(3000)`, so the throw returned every three seconds for the
 * length of the call.
 *
 * The invariant these pin is the one that makes the dialog impossible: no input,
 * however malformed, may put the same user id in two rows, because `it.id` IS
 * the key. Both real feeds are meant to be unique already, and the duplicate got
 * through anyway -- so the guarantee has to live at the point the rows are built,
 * not in each source.
 */
class MemberListPanelSectionsTest {

    private fun user(id: String, status: UserStatus = UserStatus.ONLINE) =
        OrbitUser(
            id = id,
            username = id.removePrefix("@").substringBefore(':'),
            displayName = id,
            avatarColor = 0,
            status = status
        )

    private fun rows(sections: List<MemberSection>): List<String> =
        sections.flatMap { it.users.map { u -> u.id } }

    private fun role(userId: String, powerLevel: Long) = MemberRoleInfo(
        userId = userId,
        powerLevel = powerLevel,
        canKick = false,
        canBan = false,
        maxSettablePowerLevel = 100
    )

    @Test
    fun aRepeatedUserIdYieldsOneRow() {
        val result = buildMemberSections(
            members = listOf(user("@ahmad9812:matrix.org"), user("@ahmad9812:matrix.org")),
            memberRoles = emptyMap(),
            showRoleSections = false
        )

        assertEquals(1, rows(result).size, "a duplicate id must not survive into a keyed row")
        assertEquals(listOf("@ahmad9812:matrix.org"), rows(result))
    }

    /**
     * The real shape of the bug: the same person present in BOTH the role and
     * the plain bucket's input. Dedupe runs before the sectioning, so it does not
     * matter which bucket each copy would have landed in -- but a dedupe placed
     * inside one branch would still let the other through.
     */
    @Test
    fun duplicatesAreRemovedBeforeSectioning() {
        val ahmad = user("@ahmad9812:matrix.org")
        val ali = user("@ali:matrix.org")
        val result = buildMemberSections(
            members = listOf(ahmad, ali, ahmad, ahmad),
            memberRoles = mapOf("@ahmad9812:matrix.org" to role("@ahmad9812:matrix.org", 100)),
            showRoleSections = true
        )

        assertEquals(2, rows(result).size)
        assertEquals(2, rows(result).distinct().size, "keys must be unique across sections")
    }

    /** Keys are global to one LazyColumn, so two sections must not share an id. */
    @Test
    fun everyIdAcrossAllSectionsIsUnique() {
        val members = listOf(
            user("@a:matrix.org", UserStatus.ONLINE),
            user("@b:matrix.org", UserStatus.IDLE),
            user("@c:matrix.org", UserStatus.DO_NOT_DISTURB),
            user("@d:matrix.org", UserStatus.OFFLINE),
            user("@a:matrix.org", UserStatus.ONLINE),
            user("@b:matrix.org", UserStatus.IDLE)
        )
        val result = buildMemberSections(members, emptyMap(), showRoleSections = false)
        val ids = rows(result)

        assertEquals(ids.size, ids.distinct().size, "duplicate ids survived sectioning: $ids")
    }

    /**
     * First occurrence wins, so the display order the caller already chose is
     * preserved. Picking the last copy instead would silently move a person
     * between sections when their status differed between the two entries.
     */
    @Test
    fun firstOccurrenceWins() {
        val offline = user("@ahmad9812:matrix.org", UserStatus.OFFLINE)
        val online = user("@ahmad9812:matrix.org", UserStatus.ONLINE)

        val kept = dedupeByUserId(listOf(offline, online))

        assertEquals(1, kept.size)
        assertEquals(UserStatus.OFFLINE, kept.single().status)
    }

    @Test
    fun anAlreadyUniqueListIsReturnedUnchanged() {
        val members = listOf(user("@a:matrix.org"), user("@b:matrix.org"))
        assertEquals(members, dedupeByUserId(members))
    }

    /** A single-element list short-circuits, so the common case allocates nothing. */
    @Test
    fun asingleEntryListIsNotCopied() {
        val one = listOf(user("@a:matrix.org"))
        assertTrue(dedupeByUserId(one) === one, "the short-circuit should return the same instance")
    }

    @Test
    fun anEmptyListStaysEmpty() {
        assertTrue(buildMemberSections(emptyList(), emptyMap(), showRoleSections = false).isEmpty())
    }

    /**
     * The badge counts `rows.sumOf { it.users.size }`, so a duplicate must not
     * inflate it either. Counting the input instead is how "Members 5" sat above
     * four rows.
     */
    @Test
    fun theHeaderCountMatchesTheRowsRendered() {
        val result = buildMemberSections(
            members = listOf(
                user("@ahmad9812:matrix.org"),
                user("@ali:matrix.org"),
                user("@ahmad9812:matrix.org")
            ),
            memberRoles = emptyMap(),
            showRoleSections = false
        )

        assertEquals(rows(result).size, result.sumOf { it.users.size })
        assertEquals(2, result.sumOf { it.users.size })
    }

    /** Section keys are in the SAME namespace as row ids, so they must not collide. */
    @Test
    fun sectionKeysAreDistinctFromEachOther() {
        val members = listOf(
            user("@admin1:matrix.org"),
            user("@mod1:matrix.org"),
            user("@plain1:matrix.org"),
            user("@admin1:matrix.org")
        )
        val result = buildMemberSections(
            members,
            mapOf(
                "@admin1:matrix.org" to role("@admin1:matrix.org", 100),
                "@mod1:matrix.org" to role("@mod1:matrix.org", 50)
            ),
            showRoleSections = true
        )

        assertEquals(result.size, result.map { it.key }.distinct().size)
        assertEquals(
            listOf("@admin1:matrix.org", "@mod1:matrix.org", "@plain1:matrix.org"),
            rows(result).sorted()
        )
    }
}
