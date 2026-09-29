package util

expect suspend fun performSsoLogin(homeserverUrl: String): String
expect fun openUrlInBrowser(url: String)

/**
 * Opens media in the OS default handler (image viewer, video player, browser),
 * fetching it with the account's access token when it lives behind the Matrix
 * media endpoint.
 *
 * The distinction matters and is the whole point of this function. Handing a
 * `/_matrix/client/v1/media/download/...` URL straight to the browser sends no
 * Authorization header -- the browser is not Orbit and holds no credential -- so
 * the homeserver answers
 *
 *     {"errcode":"M_MISSING_TOKEN","error":"Missing access token"}
 *
 * and the user gets a wall of JSON instead of their photo. That is not a homeserver
 * fault and not a token-expiry fault; the request simply never carried the token.
 *
 * So authenticated media is downloaded here with the token attached, written to a
 * temp file, and the LOCAL file is handed to the OS. Non-Matrix URLs (ordinary
 * links) go to the browser untouched.
 *
 * Returns true when something was handed to the OS, false when the fetch failed
 * or no handler exists.
 */
expect suspend fun openAuthenticatedMedia(url: String): Boolean
