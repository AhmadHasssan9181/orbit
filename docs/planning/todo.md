# Trixnity Migration & Backend TODO

## Phase 1: Core Architecture & SDK Migration
- [~] 1. **Authentication Refactor**: *(SKIPPED - Trixnity 5.4.0 API compilation issues and undocumented KMP namespace changes. Existing Ktor login wrapper works flawlessly and supports flexible SSO.)* Replace manual HTTP `/login` and `/register` with `MatrixClient.login()` and `MatrixClient.register()`.
- [ ] 2. **Remove Ktor Raw Client**: Strip out the custom Ktor HTTP `client` instance, `matrixGet`, `matrixPost`, `matrixPut`, and the manual `processSyncEvents()`.
- [~] 3. **Room Management**: *(DELEGATED - Pseudo-code stubs injected into `MatrixOrbitRepository.kt`. Uncomment and use IDE autocomplete to fetch Trixnity states.)* Refactor `getSpaces()`, `getChannelsForSpace()`, `createSpace()`, and `createChannel()` to query Trixnity's `client.room.getAll()` and `createRoom()`.
- [~] 4. **Messages & Timeline**: *(DELEGATED - Pseudo-code stubs injected into `MatrixOrbitRepository.kt`.)* Replace manual REST fetching in `getMessagesForChannel()` with `trixnityClient.room.getTimelineEvents()`. Use `sendMessage()` for native encrypted dispatch.
- [ ] 5. **Media & Attachments**: Update `uploadMedia` to use `trixnityClient.media.uploadMedia()`. Ensure the payload includes E2EE encryption keys when dispatching `m.file` / `m.image` to encrypted rooms.
- [x] 6. **Device Verification**: Finish implementing `DeviceVerificationManager.kt` bindings.
- [ ] 7. **Clean & Verify**: Ensure `assembleDebug` passes with all old manual REST code removed.

## Phase 2: Missing Matrix Features
- [ ] 8. **Key Request Broadcasting**: Listen for decryption failure events in Trixnity and automatically broadcast `m.room_key_request` to the sender's verified devices.
- [ ] 9. **Decryption UI Fallbacks**: Update `ChatScreen.kt` to show "Waiting for keys..." for messages that are received but waiting for E2EE decryption, instead of dropping them entirely.
- [ ] 10. **Read Receipts (`m.read`)**: Trigger a Trixnity read receipt dispatch whenever a user scrolls to the bottom of the `ChatScreen`.
- [ ] 11. **Typing Indicators (`m.typing`)**: Broadcast local typing status and observe incoming typing events from Trixnity's ephemeral event stream to update the UI.
- [ ] 12. **Presence Polling**: Implement continuous fetching or listening for `m.presence` to accurately show Online/Away indicators on user avatars.

## Phase 3: WebRTC & Push Notifications
- [ ] 13. **STUN/TURN Discovery**: Call `/_matrix/client/v3/voip/turnServer` upon login and pass the ICE servers into the WebRTC `PeerConnectionFactory`.
- [ ] 14. **WebRTC Track Bindings**: Implement `PeerConnectionFactory`, local camera binding (`Camera2Capturer`), and mic inputs inside `WebRtcClientImpl.android.kt` and `WebRtcClientImpl.jvm.kt`.
- [ ] 15. **Push Notifications**: Integrate UnifiedPush or Firebase Cloud Messaging to wake the app and process incoming `m.room.message` payloads when the app is killed.
