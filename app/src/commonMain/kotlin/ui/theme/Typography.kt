package ui.theme

import androidx.compose.material3.Typography
import onichat.app.generated.resources.*
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable

@Composable
fun getInterFontFamily() = FontFamily(Font(onichat.app.generated.resources.Res.font.inter_regular))

@Composable
fun getGoogleSansFlexFontFamily() = FontFamily(Font(onichat.app.generated.resources.Res.font.googlesansflex))

val Inter get() = FontFamily.Default
val GoogleSansFlex get() = FontFamily.Default
val FragmentMono get() = FontFamily.Default

val StoatTypography = Typography()
val GoogleTypography = Typography()

enum class UserInterfaceFont { Default, GoogleSansFlex }
fun getDefaultFont(): UserInterfaceFont = UserInterfaceFont.Default