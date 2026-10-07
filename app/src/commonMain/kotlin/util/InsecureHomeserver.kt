package util

import repository.HomeserverUrlNormalizer

/**
 * The plaintext homeserver currently in use, or null if the session is on https.
 *
 * Exists so the Diagnostics pane can render a warning without reaching into
 * [repository.MatrixContext]. Before this the approval behind a plaintext homeserver
 * was invisible after the fact: `allowInsecure` was an API with no caller, and the
 * refusal message pointed at a Settings control that did not exist.
 *
 * @see HomeserverUrlNormalizer.allowInsecure
 */
fun currentInsecureHomeserverHost(): String? =
    HomeserverUrlNormalizer.activeInsecureHost