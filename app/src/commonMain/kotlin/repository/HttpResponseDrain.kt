package repository

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

/**
 * Reads and discards a response body we are not going to use.
 *
 * The repository HttpClient is configured with `expectSuccess = false`, so Ktor
 * does not consume a body that the caller ignores. Membership is PUT on join, on
 * camera toggle, on LiveKit reconnect and on every key-exchange nudge, so over a
 * long call hundreds of undrained responses pile up and exhaust the connection
 * pool -- after which every later request in the app starts timing out.
 *
 * This existed as a private copy in two repositories before it lived here. Two
 * copies meant a third call site would have to write a third one, and one of the
 * copies was missing the comment explaining WHY it existed, which is the kind of
 * line that gets "cleaned up" by the next person who does not know what it
 * prevents.
 */
internal suspend fun HttpResponse.drain() {
    runCatching { bodyAsText() }
}
