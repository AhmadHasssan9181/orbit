# Orbit - Architectural Walkthrough

This document (your "headache medicine") outlines the high-level UI architecture of Orbit after the recent refactoring, making it easier to navigate and build upon.

## 1. Directory Structure
The monolithic UI files have been broken down and organized by feature into the following folders under `ui/screen/`:

*   **`auth/`**: Authentication screens (`LoginScreen.kt`, `SessionErrorScreen.kt`).
*   **`settings/`**: Settings and preferences (`SettingsScreen.kt`, `SettingsExtraPanes.kt`, `SettingsUserPanes.kt`).
*   **`drawer/`**: Sidebar navigation and channel lists (`ChannelDrawer.kt`, `ChannelList.kt`, `DirectMessageList.kt`, `UserProfileBar.kt`); `ServerRail.kt` lives in `server/`.
*   **`server/`**: Server management (`JoinServerScreen.kt`, `ServerRolesDialog.kt`, `ServerHeader.kt`, `SelfHostingScreen.kt`).
*   **`call/`**: WebRTC and voice/video calling UI (`CallScreen.kt`, `CallGrid.kt`, `VoiceChannelScreen.kt`).
*   **`media/`**: Media pickers and viewers (`GifPicker.kt`, `NativeVideoPlayer.jvm.kt`).
*   **`main/`**: The core application orchestrators and layouts.
*   **`chat/`**: Reusable chat components (`ChatScreen.kt`, `MessageBubbleFull.kt`, `MessageInputBar.kt`, `MemberListPanel.kt`).

## 2. The Core Orchestrator: `OrbitApp.kt`
`OrbitApp.kt` is the root composable. It handles:
1.  **Authentication State**: Decides whether to show the `LoginScreen` or the main app.
2.  **State Management**: Loads user data, servers, and channels via `OrbitRepository`.
3.  **Adaptive Routing**: Uses `WindowSizeClass` to delegate the UI to one of three specialized layout files.

## 3. Responsive Layouts
The massive layout logic has been split into three distinct files inside `ui/screen/main/` based on the user's device size:

*   **`CompactAppLayout.kt` (Phones)**: 
    *   Optimized for small screens.
    *   Uses a bottom navigation bar (`BottomNavTab.Servers` vs `BottomNavTab.DMs`).
    *   Stack-based navigation: selecting a channel pushes the `ChatScreen` on top, with a back button to return to the list.
*   **`MediumAppLayout.kt` (Tablets)**:
    *   A two-pane layout.
    *   Shows the channel drawer and the active chat side-by-side.
    *   Collapses the server rail into a bottom navigation bar or top header depending on space.
*   **`ExpandedAppLayout.kt` (Desktop/Web)**:
    *   A classic three-pane layout (Discord-style).
    *   Left pane: Server Rail.
    *   Middle pane: Channel Drawer.
    *   Right pane: Active Chat Area.

## 4. WebRTC Extraction
The WebRTC connection management, state hoisting, and side effects have been extracted entirely from the UI code into **`WebRtcEffects.kt`**. 
*   This file provides a `CallManager` instance.
*   It observes active calls and handles the intricate logic of joining groups, starting screen shares, and syncing WebRTC states with Matrix events.

## 5. UI Package Restructuring
*   All UI files now properly declare their sub-packages (e.g., `package ui.screen.call`).
*   Shared components use wildcard imports (e.g., `import ui.screen.auth.*`) to access features across different folders cleanly.
*   Platform-specific files (`androidMain`, `jvmMain`) have been synchronized with the new `commonMain` package structure to ensure KMP (Kotlin Multiplatform) compatibility for `expect/actual` declarations.

---
*You can now comfortably explore the codebase by opening the relevant folder without scrolling through 1,500+ lines of code!*
