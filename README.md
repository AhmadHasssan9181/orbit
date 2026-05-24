<p align="center">
  <img
    alt="Orbit"
    src="https://capsule-render.vercel.app/api?type=rect&height=150&color=7C6AF0&text=Orbit&fontColor=FFFFFF&fontAlignY=42&desc=Kotlin%20Multiplatform%20Matrix%20Client&descAlignY=66"
  />
</p>

<p align="center">
  <strong>Orbit is a modern Matrix client for Android and desktop, built with Kotlin Multiplatform and Compose.</strong>
</p>

<p align="center">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose-1.7.3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" />
  <img alt="Android" src="https://img.shields.io/badge/Android-SDK%2034-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img alt="JVM" src="https://img.shields.io/badge/JVM-17-F97316?style=for-the-badge&logo=openjdk&logoColor=white" />
</p>

<p align="center">
  <img alt="Matrix" src="https://img.shields.io/badge/Matrix-Client-000000?style=flat-square&logo=matrix&logoColor=white" />
  <img alt="Trixnity" src="https://img.shields.io/badge/Trixnity-5.4.0-7C6AF0?style=flat-square" />
  <img alt="Ktor" src="https://img.shields.io/badge/Ktor-3.0.1-087CFA?style=flat-square&logo=ktor&logoColor=white" />
  <img alt="Room KMP" src="https://img.shields.io/badge/Room%20KMP-2.7.0--alpha11-10B981?style=flat-square" />
  <img alt="WebRTC" src="https://img.shields.io/badge/WebRTC-Enabled-EC4899?style=flat-square&logo=webrtc&logoColor=white" />
</p>

## About

Orbit is the Matrix-powered communication client used by the app label **Onichat**. It
combines a Compose Multiplatform interface with a Trixnity-backed Matrix data layer,
local Room persistence, media caching, device verification, and WebRTC call support.

The product model is intentionally familiar:

| Orbit concept | Matrix concept |
| --- | --- |
| Server | Matrix space, direct-message bucket, or room bucket |
| Channel | Matrix room |
| Message | Matrix room event |
| Attachment | Matrix media upload and `mxc://` URI |
| Call signaling | Matrix call event in a room |
| User profile | Matrix profile and presence |

## Highlights

| Area | Capability |
| --- | --- |
| Authentication | Password login, SSO login, basic registration, persisted session restore |
| Messaging | Timeline loading, live sync, local echo, text, image, file, and audio messages |
| Rich Events | Reactions, replies, edits, unsupported-event fallback, decryption placeholder |
| Spaces And Rooms | Matrix spaces, direct rooms, joined room bucket, join flow, create space/channel |
| Identity | Profile lookup, display-name updates, avatar uploads, presence and typing |
| Verification | Trixnity-backed device verification with SAS emoji comparison |
| Calls | WebRTC call manager, Matrix call signaling, TURN server discovery |
| Interface | Compact, medium, and expanded layouts for phones, tablets, and desktop |
| Appearance | Theme system with default, light, amoled, ocean, forest, sunset, and cyberpunk modes |

## Visual Identity

Orbit uses a lavender-first identity with optional theme variants for different user
preferences. The main project color is:

| Token | Value | Usage |
| --- | --- | --- |
| Orbit Lavender | `#7C6AF0` | Primary brand color, accents, selected states |
| Sky Blue | `#3B82F6` | Ocean theme and secondary emphasis |
| Emerald | `#10B981` | Success states and forest theme |
| Red Orange | `#F97316` | Sunset theme and warm emphasis |
| Neon Pink | `#EC4899` | Cyberpunk theme accent |

The root README uses a solid lavender banner so GitHub renders it consistently without the
blank Mermaid-style rendering issue.

## Architecture

```text
Compose UI
   |
   v
OrbitRepository
   |
   v
MatrixOrbitRepository
   |-----------------------------|
   |                             |
   v                             v
Ktor Matrix HTTP                 Trixnity MatrixClient
login, register, TURN, push      sync, rooms, media, profile, verification, crypto
                                 |
                                 |---------------------------|
                                 v                           v
                         Room KMP local store          Okio media cache
```

The UI depends on `OrbitRepository`, not directly on Matrix SDK types. The concrete
repository translates Matrix rooms, state, events, media, users, and verification objects
into Orbit models used by the Compose screens.

For the full repository-layer breakdown, see
[`app/src/commonMain/kotlin/repository/README.md`](app/src/commonMain/kotlin/repository/README.md).

## Technology

| Layer | Stack |
| --- | --- |
| Language | Kotlin Multiplatform `2.0.21` |
| UI | Compose Multiplatform `1.7.3`, Material 3 |
| Android | Android Gradle Plugin `8.2.2`, compile SDK `34`, min SDK `24` |
| Desktop | JVM `17`, Compose Desktop |
| Matrix SDK | Trixnity `5.4.0` |
| Networking | Ktor `3.0.1` |
| Persistence | Room KMP `2.7.0-alpha11`, bundled SQLite `2.5.0-alpha11` |
| Media | Trixnity Okio media store, Coil `3.0.0` |
| Crypto | Trixnity Vodozemac crypto driver |
| Android Calls | Stream WebRTC Android `1.1.2` |
| Desktop Calls | `dev.onvoid.webrtc:webrtc-java:0.14.0` |

## Repository Layout

```text
.
|-- app
|   |-- build.gradle.kts
|   |-- src
|   |   |-- commonMain
|   |   |   |-- kotlin
|   |   |   |   |-- repository   Matrix/Trixnity repository layer
|   |   |   |   |-- ui           Compose models, screens, theme, layout
|   |   |   |   |-- util         Shared expect declarations
|   |   |   |   |-- webrtc       Shared call manager and WebRTC contract
|   |   |-- androidMain         Android actual implementations
|   |   |-- jvmMain             Desktop actual implementations
|   |   |-- main/res            Android resources and certificates
|-- trixnity-src                Local reference copy of Trixnity client sources
|-- gradle/wrapper              Gradle wrapper
|-- implementation_plan.md      Integration notes
|-- todo.md                     Backend roadmap
```

## Requirements

- JDK 17.
- Android SDK 34.
- Git.
- Gradle wrapper from this repository.
- Network access to Maven repositories and a Matrix homeserver.
- A Matrix account, or a Matrix homeserver with SSO configured.

The Gradle wrapper is configured for Gradle `8.5`.

## Build

Build the Android debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

Run the desktop JVM target:

```powershell
.\gradlew.bat :app:run
```

Clean generated output:

```powershell
.\gradlew.bat clean
```

## Runtime Flow

### Login

```text
LoginScreen
   -> MatrixOrbitRepository.login or loginWithSso
   -> Matrix login endpoint
   -> CredentialManager stores session
   -> Trixnity MatrixClient is created
   -> OrbitApp starts sync
```

### Restore

```text
OrbitApp startup
   -> CredentialManager reads stored session
   -> MatrixOrbitRepository starts sync
   -> Trixnity restores local auth from Room
   -> whoAmI validates session
   -> room list and timeline sync begin
```

## Matrix Integration

Trixnity is initialized with persistent repositories, media storage, and crypto support:

```kotlin
MatrixClient.create(
    repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
    mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
    cryptoDriverModule = CryptoDriverModule.vodozemac(),
    authProviderData = authProviderData
)
```

Trixnity handles the long-lived Matrix client features:

- Room list, room state, and space children.
- Timeline history and live sync.
- Outbox messages and local pending state.
- Media upload and media cache.
- Profile, presence, and typing.
- Device verification.
- End-to-end encryption state.

Ktor is used for direct Matrix HTTP calls where the repository currently keeps the flow
simple:

- Login.
- SSO token exchange.
- Registration.
- TURN server discovery.
- Pusher registration.

## Local Data

| Data | Android | Desktop JVM |
| --- | --- | --- |
| Credentials | `SharedPreferences` file `orbit_creds` | Java Preferences node `com/noobdev/orbit/credentials` |
| Trixnity database | App database path `trixnity.db` | `$HOME/.orbit/trixnity.db` |
| Media cache | App files directory `orbit_media` | `$HOME/.orbit/media` |
| SSO callback | `orbit://sso-callback` | Temporary localhost server |

## Main Screens

| Screen | Purpose |
| --- | --- |
| `OrbitApp.kt` | App shell, auth routing, session restore, adaptive layout |
| `LoginScreen.kt` | Homeserver login, registration toggle, SSO |
| `ServerRail.kt` | Server and space navigation |
| `ChannelDrawer.kt` | Channels, DMs, profile bar, lite-mode drawer |
| `ChatScreen.kt` | Timeline, rich message rendering, input, member list |
| `CallScreen.kt` | Active call controls and video surfaces |
| `VerificationOverlay.kt` | Device verification UI |
| `SettingsScreen.kt` | Appearance, display, notifications, streaming, logout |
| `JoinServerScreen.kt` | Join room or space dialog |

## WebRTC

Calls are coordinated by `CallManager` and implemented per platform through the
`WebRtcClient` contract.

| Platform | Implementation |
| --- | --- |
| Android | `AndroidWebRtcClient` with `org.webrtc` |
| Desktop JVM | `JvmWebRtcClient` with `dev.onvoid.webrtc` |

Matrix call events used for signaling:

- `m.call.invite`
- `m.call.answer`
- `m.call.candidates`
- `m.call.hangup`

TURN server configuration is fetched from:

```text
/_matrix/client/v3/voip/turnServer
```

## Current Implementation Notes

- `getSpaceHierarchy()` is currently a stub.
- Message pagination token handling is not complete.
- Live parsing into `observeCallEvents()` is not fully wired.
- Group call observation currently returns `null`.
- `DeviceVerificationManager` exists, but the UI primarily consumes Trixnity verification state directly.
- Some edit/delete paths need stronger room ID handling.
- Credentials are stored in plain platform settings.
- Logout clears app credentials but does not wipe the Trixnity database or media cache.
- Registration does not implement the full Matrix UIAA flow.

## Documentation

| Document | Description |
| --- | --- |
| [`app/src/commonMain/kotlin/repository/README.md`](app/src/commonMain/kotlin/repository/README.md) | Detailed repository, Matrix, and Trixnity documentation |
| [`todo.md`](todo.md) | Backend roadmap |
| [`implementation_plan.md`](implementation_plan.md) | Integration notes |

