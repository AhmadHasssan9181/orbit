package ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

// Bypassing the K2 compiler bug by using standard system fonts
val Inter = FontFamily.Default
val GoogleSansFlex = FontFamily.Default
val FragmentMono = FontFamily.Default

val StoatTypography = Typography()
val GoogleTypography = Typography()

enum class UserInterfaceFont { Default, GoogleSansFlex }
fun getDefaultFont(): UserInterfaceFont = UserInterfaceFont.Default