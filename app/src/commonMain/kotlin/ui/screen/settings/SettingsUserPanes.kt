package ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import repository.OrbitRepository
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitNotices
import ui.components.OrbitNotices.onFailureOrNotify
import ui.components.OrbitTextField
import ui.model.OrbitProfileExtras
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// Privacy, Family Centre, Authorized Apps and Connections were pure mock-ups:
// no settings category reaches them, and they persisted invented app
// authorisations and social links into account data. They have been deleted.

private const val PROFILE_EXTRAS_KEY = "im.orbit.profile_extras"

@Composable
internal fun ProfilesPane(repository: OrbitRepository) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()

    val profileExtras by repository
        .observeAccountData(PROFILE_EXTRAS_KEY, OrbitProfileExtras.serializer())
        .collectAsState(null)
    val currentExtras = profileExtras ?: OrbitProfileExtras()

    // One draft for the whole pane. Each field used to write
    // `currentExtras.copy(field = …)` from the snapshot captured at composition,
    // so saving "About Me" wiped the pronouns saved a second earlier.
    var draft by remember(currentExtras) { mutableStateOf(currentExtras) }
    var savedNotice by remember { mutableStateOf(false) }

    fun save(updated: OrbitProfileExtras) {
        draft = updated
        // Clear the acknowledgement as soon as anything is edited, so the button
        // does not keep claiming a save that has not happened.
        savedNotice = false
        scope.launch {
            // `savedNotice = true` used to be unconditional, so a failed write
            // showed a "Saved" confirmation. The setting was not saved and the
            // app had told the user it was.
            repository.setAccountData(PROFILE_EXTRAS_KEY, updated, OrbitProfileExtras.serializer())
                .onFailureOrNotify("Profile save", "Settings")
                .onSuccess { savedNotice = true }
        }
    }

    var avatarBusy by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf<ui.model.OrbitUser?>(null) }
    LaunchedEffect(Unit) { me = repository.getCurrentUser() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF6E67DE), Color(0xFF7B74E8), Color(0xFF928CF0))))
            ) {
                if (!draft.bannerUrl.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = draft.bannerUrl,
                        contentDescription = "Profile Banner",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Avatar, wired to the same upload flow My Account uses. It was a
            // 120dp tile with a camera icon and no clickable at all.
            Box(
                modifier = Modifier.align(Alignment.BottomStart).offset(x = 24.dp, y = 60.dp)
                    .size(120.dp).clip(CircleShape).background(t.Bg).padding(6.dp)
                    .clickable(enabled = !avatarBusy) {
                        scope.launch {
                            avatarBusy = true
                            val bytes = util.pickImageFile()
                            // `me` was re-read unconditionally, so a failed
                            // upload got the unchanged user back and the old
                            // avatar stayed on screen looking like a no-op. Only
                            // refresh when the upload actually landed.
                            if (bytes != null) {
                                repository.uploadAvatar(bytes)
                                    .onFailureOrNotify("Avatar upload", "Settings")
                                    .onSuccess { me = repository.getCurrentUser() }
                            }
                            avatarBusy = false
                        }
                    }
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(t.CardBgHover),
                    contentAlignment = Alignment.Center
                ) {
                    val avatarUrl = me?.avatarUrl
                    if (avatarUrl != null) {
                        coil3.compose.AsyncImage(
                            model = avatarUrl,
                            contentDescription = "Your avatar — click to change",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(
                            Icons.Default.AddAPhoto,
                            contentDescription = "Change your avatar",
                            tint = t.Text_Muted,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        SectionHeader("Profile Customization")

        // Banner by URL only. The repository DOES have a real banner media-upload
        // API now (uploadBanner) but there is no picker wired to it, and reusing
        // uploadAvatar() would overwrite the user's real avatar, so the honest
        // state is a URL field rather than a button that silently does nothing.
        //
        // The Pronouns / Email / About Me / Custom Status / Profile Accent Color /
        // Avatar Decoration / Profile Effect controls were removed here: each one
        // persisted a value into im.orbit.profile_extras that NO reader anywhere
        // in the app consumed, so all seven help text claims ("appears on your
        // profile card", "visible to your friends") were false. Their fields stay
        // in the schema so previously-saved data is not discarded on next save.
        SettingsTextField(
            label = "Banner URL",
            description = "Link to an image for your profile banner.",
            value = draft.bannerUrl ?: "",
            onValueChange = { draft = draft.copy(bannerUrl = it); savedNotice = false },
            placeholder = "https://example.com/banner.jpg"
        )

        OrbitButton(
            text = if (savedNotice) "Profile saved" else "Save profile",
            onClick = { save(draft) },
            tone = OrbitButtonTone.Primary
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
//  HELPER: SettingsTextField
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun SettingsTextField(
    label: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    multiline: Boolean = false
) {
    val t = rememberCosmicTokens()
    // Keeps its border: this is a card, and cards keep the hairline that
    // separates one labelled group from the next. What changed is the *control*
    // inside it -- `OrbitTextField` no longer draws a ring of its own, so this
    // is now one edge per group instead of a ring around the field inside a ring
    // around the group.
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.CardBg)
            .border(1.dp, t.Separator, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(label, color = t.Text_White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (description.isNotEmpty()) {
            Text(description, color = t.Text_Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        OrbitTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 5 else 1
        )
    }
}
