package ui.screen.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions






import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.components.OrbitTextField
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.icons.OrbitIcons
import ui.model.ChannelType
import ui.model.OrbitServer
import ui.screen.orbitGlobalShortcuts
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptDark
import ui.theme.OrbitConceptTheme
import ui.theme.OrbitConceptOverlay
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*
import ui.icons.TablerIcons
import ui.icons.DeviceGamepad
import ui.icons.School
import ui.icons.Stars
import ui.icons.Users
import ui.icons.X
private enum class ServerDialogMode {
    Join,
    Create
}

// ── Concept modal geometry ────────────────────────────────────────────────
// CreateServerModal.jsx: a 440px card, `border-radius: 8px`, `padding: 24px`,
// sitting on `.modal-overlay` (rgba(0,0,0,0.75) + blur(6px)).
private val ConceptModalWidth = 440.dp

/** `border-radius: '8px'` on the modal card. */
private val ConceptModalShape = RoundedCornerShape(8.dp)

/**
 * `.menu-item` is 4px, but every template row overrides it inline with
 * `borderRadius: '8px'` (`padding: '12px'`, `border: 1px solid
 * var(--border-subtle)`). This is the override.
 */
private val ConceptMenuRowShape = RoundedCornerShape(8.dp)

/** `border-radius: '4px'` on the modal's filled Create button. */
private val ConceptButtonShape = RoundedCornerShape(4.dp)

/** `transition: all 0.12s ease` on `.menu-item`. */
private val ConceptHoverSpec = androidx.compose.animation.core.tween<androidx.compose.ui.graphics.Color>(120)

/** `--text-danger` (#fa777c). Not in OrbitConcept.kt; used for validation copy. */
private val ConceptDangerText = Color(0xFFFA777C)

/**
 * `#eb459e` — the literal on CreateServerModal's "Friends" template icon, and
 * the far stop of ExploreModal's hero gradient. Not in OrbitConcept.kt.
 */
private val ConceptPink = Color(0xFFEB459E)

/**
 * `--interactive-muted` (#4e5058) — the chevron on every `.menu-item` row.
 *
 * Its siblings are all in the token set already: `--interactive-normal` is
 * `--header-secondary` (#b5bac1) and `--interactive-hover` is `--text-normal`
 * (#dbdee1). This one has no equivalent, so it lives here.
 */
private val OrbitConceptInteractiveMuted = Color(0xFF4E5058)

private val DiscordRoomNameRegex = Regex("^[a-z]+(?:-[a-z]+)*$")

/** Hoisted: both were recompiled on every keystroke in the room-name field. */
private val WhitespaceRunRegex = Regex("\\s+")
private val RepeatedHyphenRegex = Regex("-{2,}")

private fun normalizeRoomNameInput(value: String): String {
    val lowered = value.lowercase().replace(WhitespaceRunRegex, "-")
    return buildString {
        lowered.forEach { ch ->
            if (ch in 'a'..'z' || ch == '-') append(ch)
        }
    }.replace(RepeatedHyphenRegex, "-")
}

private fun validateRoomName(value: String): String? {
    return when {
        value.isBlank() -> "Room name is required"
        !DiscordRoomNameRegex.matches(value) -> "Use letters and hyphens only, like general-chat"
        else -> null
    }
}

@Composable
private fun ChannelTypeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) tokens.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) tokens.Text_White else tokens.Text_Muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

/**
 * Add Server: join an existing one, or create a new one.
 *
 * The visual implementation is the concept's `CreateServerModal.jsx` +
 * `.modal-overlay` / `.menu-item`, translated value for value:
 *
 *  - card `width: 440px`, `border-radius: 8px`, `padding: 24px`,
 *    `background-color: var(--bg-chat)`, `box-shadow: var(--elevation-floating)`
 *  - the close X is `position: absolute; top: 16px; right: 16px`, `--interactive-normal`, 20px
 *  - headings are 24px / 800 on `--header-primary`, centred, 8px below the top
 *  - the subtitle is 14px on `--text-muted`, centred
 *  - every option is a `.menu-item`: `padding: 12px`, `border: 1px solid
 *    var(--border-subtle)`, `border-radius: 8px`, a 12px gap inside, a 15px /
 *    700 label and a `--interactive-muted` chevron — and it floods to
 *    `--brand-blurple` with white text on hover, over 0.12s
 *  - the field is `--bg-secondary` on a `--border-subtle` hairline, 6px corners,
 *    `padding: 10px 12px`, 15px
 *  - the primary button is `--brand-blurple` with white text, 4px corners,
 *    `padding: 10px 24px`, 14px / 700
 *
 * ## What changed structurally
 *
 * A `TabRow` and a single combined field became the concept's two-step create
 * flow: choose a template, then name the server. The templates are functional
 * rather than decorative — selecting one presets the name exactly as the concept
 * does (`setServerName(\`${template}'s Community\`)`) and advances to step two.
 *
 * The create and join calls below are untouched: same repository methods, same
 * `#general` room, same error handling.
 *
 * ## The icon dropzone is not here
 *
 * The concept's step two puts an 80px dashed `--brand-blurple` upload circle
 * above the name field. Orbit has no server-icon upload (`OrbitRepository` has
 * `uploadAvatar`/`uploadBanner` for *users* and nothing for spaces), so a
 * dropzone here would be a control that cannot do anything. It is omitted
 * rather than shipped as a lie.
 *
 * ## About the backdrop
 *
 * `.modal-overlay` is `rgba(0, 0, 0, 0.75)` plus `backdrop-filter: blur(6px)`
 * over the whole viewport. This stays a real `Dialog` window on purpose — the
 * overlay version took no keyboard focus (keystrokes reached the composer
 * behind it), ignored Escape and ignored outside clicks, so it could not be
 * dismissed at all. A Compose `Dialog` is its own native window sized to its
 * content, so a viewport-wide scrim cannot be drawn inside it; what is drawn
 * here is the concept's scrim colour around the card. `backdrop-filter` has no
 * Compose equivalent either.
 */
@Composable
fun JoinServerScreen(
    repository: OrbitRepository,
    onClose: () -> Unit,
    onServerCreated: (String) -> Unit = {},
    onServerJoined: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(ServerDialogMode.Join) }
    var input by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isCreateMode = mode == ServerDialogMode.Create
    // The concept's step: 1 = pick a template, 2 = name the server. Only the
    // create path has two steps; joining is one field and one button.
    var createStep by remember { mutableStateOf(1) }

    // A real Dialog window, not a Box with a scrim: the overlay version took no
    // keyboard focus (keystrokes reached the composer behind it), ignored Esc and
    // ignored clicks outside, and so could not be dismissed at all.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = modifier
                // width: 440px
                .width(ConceptModalWidth)
                // border-radius: 8px + box-shadow: var(--elevation-floating)
                // (0 16px 32px rgba(0,0,0,0.4)), which is as close as
                // Compose's elevation model gets — the shape is passed to
                // `shadow` so the shadow follows the radius instead of drawing
                // a rectangle behind a rounded card.
                .shadow(16.dp, ConceptModalShape)
                .clip(ConceptModalShape)
                // background-color: var(--bg-chat)
                .background(OrbitConceptTheme.chat)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // padding: 24px
                    .padding(OrbitSpace.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Join / Create switch ────────────────────────────
                //
                // Two `.menu-item` halves rather than a `TabRow`: the concept
                // has no tab component, and a Material tab strip drew an
                // animated underline and a full-width ripple that appear
                // nowhere in it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                ) {
                    ConceptMenuItem(
                        label = "Join a Server",
                        selected = !isCreateMode,
                        onClick = {
                            mode = ServerDialogMode.Join
                            input = ""
                            errorMsg = null
                        },
                        enabled = !isWorking,
                        modifier = Modifier.weight(1f)
                    )
                    ConceptMenuItem(
                        label = "Create My Own",
                        selected = isCreateMode,
                        onClick = {
                            mode = ServerDialogMode.Create
                            createStep = 1
                            input = ""
                            errorMsg = null
                        },
                        enabled = !isWorking,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(OrbitSpace.md))

                if (!isCreateMode) {
                    // ── Join ────────────────────────────────────────
                    Text(
                        text = "Join a Server",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = OrbitConceptTheme.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp,
                            lineHeight = 30.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = OrbitSpace.sm)
                    )
                    Text(
                        text = "Paste a room alias or an invite to join a server you are not in yet.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = OrbitConceptTheme.textMuted,
                            fontSize = 14.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = OrbitSpace.xl)
                    )

                    ConceptInputField(
                        value = input,
                        onValueChange = { input = it; errorMsg = null },
                        placeholder = "#room:server.com",
                        modifier = Modifier.fillMaxWidth().padding(bottom = OrbitSpace.lg)
                    )

                    if (errorMsg != null) {
                        Text(
                            text = errorMsg!!,
                            color = ConceptDangerText,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = OrbitSpace.md)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConceptTextButton(
                            text = "Cancel",
                            onClick = onClose,
                            enabled = !isWorking
                        )
                        Spacer(Modifier.weight(1f))
                        ConceptFilledButton(
                            text = "Join",
                            onClick = {
                                isWorking = true
                                errorMsg = null
                                launchJoinOrCreate(
                                    repository, scope, mode, input.trim(),
                                    onServerCreated, onServerJoined,
                                    { errorMsg = it; isWorking = false },
                                    onClose
                                )
                            },
                            enabled = !isWorking && input.isNotBlank(),
                            loading = isWorking
                        )
                    }
                } else if (createStep == 1) {
                    // ── Step 1: templates ─────────────────────────────
                    Text(
                        text = "Create Your Server",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = OrbitConceptTheme.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp,
                            lineHeight = 30.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = OrbitSpace.sm)
                    )
                    Text(
                        text = "Your server is where you and your friends hang out. Make yours and start talking.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = OrbitConceptTheme.textMuted,
                            fontSize = 14.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = OrbitSpace.xl)
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                    ) {
                        ConceptMenuItem(
                            label = "Create My Own",
                            icon = { Icon(with(TablerIcons) { Stars }, null, tint = OrbitConceptDark.brand, modifier = Modifier.size(20.dp)) },
                            onClick = { input = "My Server's Community"; createStep = 2 },
                            enabled = !isWorking
                        )

                        // "START FROM A TEMPLATE" — font-size 12px, 700,
                        // --text-muted, uppercase, margin 8px 0 4px. The 8px of
                        // top margin lands on top of the 8px column gap, so the
                        // extra space above the label is 8dp and the run-off
                        // below it is 4dp.
                        Spacer(Modifier.height(OrbitSpace.sm))
                        Text(
                            text = "START FROM A TEMPLATE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = OrbitConceptTheme.textMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        )

                        ConceptMenuItem(
                            label = "Gaming",
                            icon = { Icon(with(TablerIcons) { DeviceGamepad }, null, tint = OrbitConceptStatus.online, modifier = Modifier.size(20.dp)) },
                            onClick = { input = "Gaming Squad's Community"; createStep = 2 },
                            enabled = !isWorking
                        )
                        ConceptMenuItem(
                            label = "School Club",
                            icon = { Icon(with(TablerIcons) { School }, null, tint = OrbitConceptStatus.idle, modifier = Modifier.size(20.dp)) },
                            onClick = { input = "Study Group's Community"; createStep = 2 },
                            enabled = !isWorking
                        )
                        ConceptMenuItem(
                            label = "Friends",
                            icon = { Icon(with(TablerIcons) { Users }, null, tint = ConceptPink, modifier = Modifier.size(20.dp)) },
                            onClick = { input = "Friends Hangout's Community"; createStep = 2 },
                            enabled = !isWorking
                        )
                    }
                } else {
                    // ── Step 2: name it ───────────────────────────────
                    Text(
                        text = "Customize Your Server",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = OrbitConceptTheme.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp,
                            lineHeight = 30.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = OrbitSpace.sm)
                    )
                    Text(
                        text = "Give your new server a personality with a name.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = OrbitConceptTheme.textMuted,
                            fontSize = 14.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = OrbitSpace.xxl)
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                    ) {
                        Text(
                            text = "SERVER NAME",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = OrbitConceptTheme.textMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                        ConceptInputField(
                            value = input,
                            onValueChange = { input = it; errorMsg = null },
                            placeholder = "My Server's Community",
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (errorMsg != null) {
                            Text(
                                text = errorMsg!!,
                                color = ConceptDangerText,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(OrbitSpace.xxl))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConceptTextButton(
                            text = "Back",
                            onClick = { createStep = 1; errorMsg = null },
                            enabled = !isWorking
                        )
                        Spacer(Modifier.weight(1f))
                        ConceptFilledButton(
                            text = "Create",
                            onClick = {
                                isWorking = true
                                errorMsg = null
                                launchJoinOrCreate(
                                    repository, scope, mode, input.trim(),
                                    onServerCreated, onServerJoined,
                                    { errorMsg = it; isWorking = false },
                                    onClose
                                )
                            },
                            enabled = !isWorking && input.isNotBlank(),
                            loading = isWorking
                        )
                    }
                }
            }

            // position: absolute; top: 16px; right: 16px
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = OrbitSpace.lg, end = OrbitSpace.lg)
                    .size(28.dp)
                    .clip(OrbitRadius.xsShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Close",
                        onClick = onClose
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = with(TablerIcons) { X },
                    contentDescription = "Close",
                    // --interactive-normal
                    tint = OrbitConceptDark.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * The one place the create/join Matrix calls live.
 *
 * Extracted verbatim out of the button's onClick so both the Join footer and
 * the Create footer reach the same code. Nothing about the flow changed: create
 * makes the space and then `#general`, join joins and reports the room id.
 */
private fun launchJoinOrCreate(
    repository: OrbitRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    mode: ServerDialogMode,
    value: String,
    onServerCreated: (String) -> Unit,
    onServerJoined: (String) -> Unit,
    onFailure: (String) -> Unit,
    onSuccess: () -> Unit
) {
    if (value.isBlank()) return
    scope.launch {
        val isCreate = mode == ServerDialogMode.Create
        val result = if (isCreate) {
            val spaceResult = repository.createSpace(value)
            val spaceId = spaceResult.getOrNull()
            if (spaceId != null) {
                val channelResult = repository.createChannel(spaceId, "general", ChannelType.TEXT)
                if (channelResult.isSuccess) {
                    onServerCreated(spaceId)
                    spaceResult
                } else {
                    Result.failure(channelResult.exceptionOrNull() ?: Exception("Failed to create #general"))
                }
            } else {
                spaceResult
            }
        } else {
            repository.joinSpace(value).onSuccess { roomId ->
                onServerJoined(roomId)
            }
        }
        if (result.isSuccess) {
            onSuccess()
        } else {
            onFailure(result.exceptionOrNull()?.message
                ?: if (isCreate) "Failed to create server" else "Failed to join")
        }
    }
}

/**
 * A `.menu-item` as the modal overrides it.
 *
 * `.menu-item` alone is `padding: 8px 10px` / 4px corners / 13px / 500; the
 * concept's rows add `padding: '12px'`, `border: '1px solid
 * var(--border-subtle)'` and `borderRadius: '8px'` inline, plus a 12px gap
 * between the icon and the label and a chevron at the far end.
 * `.menu-item:hover` floods the row with `--brand-blurple` and turns the text
 * white; the icon keeps its own tint in the concept, so it does here too.
 */
@Composable
private fun ConceptMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    icon: (@Composable () -> Unit)? = null
) {
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()
    val flooded = hovered || pressed

    val bg by animateColorAsState(
        targetValue = if (flooded) OrbitConceptDark.brand else Color.Transparent,
        animationSpec = ConceptHoverSpec,
        label = "conceptMenuBg"
    )
    // `.menu-item:hover { color: #ffffff }`
    val fg by animateColorAsState(
        targetValue = when {
            flooded -> Color.White
            selected -> OrbitConceptDark.brand
            else -> OrbitConceptTheme.textNormal
        },
        animationSpec = ConceptHoverSpec,
        label = "conceptMenuFg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            flooded -> OrbitConceptDark.brand
            selected -> OrbitConceptDark.brand.copy(alpha = OrbitConceptOverlay.BRAND_SUBTLE * 2f)
            else -> OrbitConceptBorder.subtle()
        },
        animationSpec = ConceptHoverSpec,
        label = "conceptMenuBorder"
    )

    Row(
        modifier = modifier
            .clip(ConceptMenuRowShape)
            .background(bg)
            .border(1.dp, borderColor, ConceptMenuRowShape)
            .hoverable(interaction.hoverSource, enabled)
            .orbitClickable(interaction, enabled, Role.Button, onClickLabel = label, onClick = onClick)
            // padding: 12px
            .padding(OrbitSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            // gap: 12px
            Spacer(Modifier.width(OrbitSpace.md))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        if (icon != null) {
            // --interactive-muted, 18px
            Icon(
                imageVector = OrbitIcons.ChevronRight,
                contentDescription = null,
                tint = OrbitConceptInteractiveMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * The modal's text field.
 *
 * `background-color: var(--bg-secondary)`,
 * `border: 1px solid var(--border-subtle)`, `border-radius: 6px`,
 * `padding: 10px 12px`, `font-size: 15px`, `color: var(--text-normal)`.
 *
 * One deviation, on purpose: the concept sets `outline: 'none'` on this input,
 * which removes the focus indicator entirely. The frame is drawn on
 * `--brand-blurple` while focused instead — the same hairline, still 1dp, just
 * coloured, so a keyboard user can still see where they are.
 */
@Composable
private fun ConceptInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onImeAction: (() -> Unit)? = null
) {
    val interaction = rememberOrbitInteraction()
    val focused by interaction.focusSource.collectIsFocusedAsState()
    val frame by animateColorAsState(
        targetValue = if (focused) OrbitConceptDark.brand else OrbitConceptBorder.subtle(),
        animationSpec = ConceptHoverSpec,
        label = "conceptFieldFrame"
    )
    val fieldStyle = MaterialTheme.typography.bodyMedium.copy(
        color = OrbitConceptTheme.textNormal,
        fontSize = 15.sp
    )

    Box(
        modifier = modifier
            // border-radius: 6px
            .clip(OrbitRadius.smShape)
            // background-color: var(--bg-secondary)
            .background(OrbitConceptTheme.secondary)
            // border: 1px solid var(--border-subtle)
            .border(1.dp, frame, OrbitRadius.smShape)
            // padding: 10px 12px
            .padding(horizontal = OrbitSpace.md, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = fieldStyle.copy(color = OrbitConceptTheme.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = fieldStyle,
                interactionSource = interaction.focusSource,
                cursorBrush = SolidColor(OrbitConceptDark.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onSend = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() }
                )
            )
        }
    }
}

/**
 * `background-color: var(--brand-blurple)`, `color: #fff`,
 * `border-radius: 4px`, `padding: 10px 24px`, `font-size: 14px`,
 * `font-weight: 700`.
 *
 * Hover and press come from the concept's `--brand-blurple-hover`
 * (#4752c4) and `--brand-blurple-active` (#3c45a5). This is not
 * `OrbitButton(Primary)`, whose hover is `t.AccentLight` — a different colour,
 * and one that has to follow whichever of the app's eleven accent themes is
 * active.
 */
@Composable
private fun ConceptFilledButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false
) {
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> OrbitConceptDark.brand.copy(alpha = 0.5f)
            pressed -> OrbitConceptDark.brandActive
            hovered -> OrbitConceptDark.brandHover
            else -> OrbitConceptDark.brand
        },
        animationSpec = ConceptHoverSpec,
        label = "conceptFilledBg"
    )

    Row(
        modifier = modifier
            .clip(ConceptButtonShape)
            .background(bg)
            .hoverable(interaction.hoverSource, enabled)
            .orbitClickable(interaction, enabled, Role.Button, onClickLabel = text, onClick = onClick)
            // padding: 10px 24px
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        )
    }
}

/**
 * The modal's quiet button — `background: transparent`, `border: none`,
 * `color: var(--text-normal)`, `font-size: 14px`, `font-weight: 600`.
 *
 * The concept declares no hover here; the row brightens to
 * `--interactive-active` (#ffffff) so the cursor still gets an answer.
 */
@Composable
private fun ConceptTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()
    val t = rememberCosmicTokens()

    val fg by animateColorAsState(
        targetValue = if (hovered || pressed) t.Text_White else OrbitConceptTheme.textNormal,
        animationSpec = ConceptHoverSpec,
        label = "conceptTextBtnFg"
    )

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        ),
        modifier = modifier
            .clip(OrbitRadius.xsShape)
            .hoverable(interaction.hoverSource, enabled)
            .orbitClickable(interaction, enabled, Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.xs)
    )
}

@Composable
fun CreateRoomDialog(
    repository: OrbitRepository,
    server: OrbitServer,
    onClose: () -> Unit,
    onRoomCreated: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    var input by remember { mutableStateOf("") }
    var channelType by remember { mutableStateOf(ChannelType.TEXT) }
    var isWorking by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validationMsg = remember(input) {
        if (input.isBlank()) null else validateRoomName(input)
    }

    // Real Dialog window, matching JoinServerScreen: focus capture, Esc and
    // scrim dismissal, instead of an inert Box overlay.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = modifier
                .width(400.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.Bg)
                .orbitGlobalShortcuts()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OrbitEmptyIllustration(
                scene = OrbitEmptyScene.CreateRoom,
                modifier = Modifier.width(210.dp).height(130.dp)
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = "Create Room",
                color = tokens.Text_White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = server.name,
                color = tokens.Text_Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.CardBg)
                    .border(1.dp, tokens.Separator.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                ChannelTypeChoice(
                    label = "Text",
                    selected = channelType == ChannelType.TEXT,
                    onClick = { channelType = ChannelType.TEXT },
                    modifier = Modifier.weight(1f)
                )
                ChannelTypeChoice(
                    label = "Voice",
                    selected = channelType == ChannelType.VOICE,
                    onClick = { channelType = ChannelType.VOICE },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))

            OrbitTextField(
                value = input,
                onValueChange = {
                    input = normalizeRoomNameInput(it)
                    errorMsg = null
                },
                placeholder = if (channelType == ChannelType.VOICE) "general" else "general-chat",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = validationMsg ?: errorMsg ?: if (channelType == ChannelType.VOICE) {
                    "Voice channels appear in the separate voice section."
                } else {
                    "Only letters and hyphens are allowed."
                },
                color = if (validationMsg != null || errorMsg != null) Color(0xFFF87171) else tokens.Text_Muted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onClose,
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel", color = tokens.Text_Muted)
                }

                Button(
                    onClick = {
                        val value = input.trim()
                        val validation = validateRoomName(value)
                        if (validation != null) {
                            errorMsg = validation
                            return@Button
                        }

                        isWorking = true
                        errorMsg = null
                        scope.launch {
                            repository.createChannel(
                                spaceId = server.id,
                                name = value,
                                type = channelType,
                                topic = if (channelType == ChannelType.VOICE) "Voice channel" else ""
                            ).onSuccess { channelId ->
                                onRoomCreated(channelId)
                                onClose()
                            }.onFailure { error ->
                                errorMsg = error.message ?: "Failed to create room"
                                isWorking = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent),
                    enabled = !isWorking && input.isNotBlank() && validationMsg == null,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(if (channelType == ChannelType.VOICE) "Create Voice" else "Create Text", color = Color.White)
                    }
                }
            }
        }
    }
}
