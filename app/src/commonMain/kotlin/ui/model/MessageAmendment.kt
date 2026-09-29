package ui.model

/**
 * Folds a live-arriving reaction or edit into the messages already on screen.
 *
 * The history path folds these in the repository (`applyEdits` +
 * `aggregateReactions`), but the LIVE sync path emitted the reaction/edit
 * pseudo-messages as if they were ordinary messages. In an open channel that
 * meant every reaction anyone sent appeared as a lone "👍" bubble while the
 * parent message's chip never updated, and someone else's edit appeared as a
 * second bubble with the new text while the original kept the old one --
 * permanently, until the channel was closed and reopened. Only your OWN
 * reactions/edits repaired it, because those force a full history reload.
 *
 * This is the same job, done on the accumulated list, and is deliberately a
 * pure function so it can be tested without a repository.
 *
 * @param messages the messages currently rendered, oldest first.
 * @param incoming the newly arrived events (may be a mix of real messages and
 *   reaction/edit pseudo-messages).
 * @return the updated list, or [messages] unchanged when nothing applied.
 */
fun applyLiveAmendments(
    messages: List<OrbitMessage>,
    incoming: List<OrbitMessage>
): List<OrbitMessage> {
    if (incoming.isEmpty()) return messages

    var result: List<OrbitMessage>? = null
    val adds = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
    // Senders to drop, per parent. The spec's un-annotate marker carries an empty
    // emoji key, so it has to be applied to the PARENT'S EXISTING reaction map --
    // a fresh map built from the newly-arrived events alone would have nothing to
    // remove from, and the chip would keep showing a reaction the user took back.
    val removals = HashMap<String, LinkedHashSet<String>>()
    val edits = HashMap<String, MessageContent>()

    for (event in incoming) {
        when (val content = event.content) {
            is MessageContent.Reaction -> {
                if (content.reactsToEventId.isEmpty()) continue
                if (content.key.isEmpty()) {
                    removals.getOrPut(content.reactsToEventId) { LinkedHashSet() }.add(event.userId)
                } else {
                    adds.getOrPut(content.reactsToEventId) { LinkedHashMap() }
                        .getOrPut(content.key) { LinkedHashSet() }
                        .add(event.userId)
                }
            }

            is MessageContent.Edit -> {
                if (content.replacesEventId.isEmpty()) continue
                // Later edits win; the map keeps the last one seen.
                edits[content.replacesEventId] = content.newContent
            }

            else -> Unit
        }
    }

    if (adds.isNotEmpty() || removals.isNotEmpty() || edits.isNotEmpty()) {
        result = messages.map { message ->
            val edited = edits[message.id]
            val byEmoji = adds[message.id]
            val drop = removals[message.id]
            if (edited == null && byEmoji == null && drop == null) {
                message
            } else {
                // Never resurrect a deleted message: the edit event can arrive
                // after the redaction, and re-showing the text would undo the
                // deletion on screen.
                val redacted = message.content is MessageContent.Redacted
                var amended = if (edited != null && !redacted) {
                    message.copy(body = edited.displayText, content = edited, isEdited = true)
                } else {
                    message
                }

                if (byEmoji != null || drop != null) {
                    val merged = amended.reactions.toMutableMap()
                    // Removals first, so a same-batch remove-then-add for the
                    // same sender resolves to "still reacted".
                    if (drop != null) {
                        merged.keys.forEach { key ->
                            val senders = merged[key]?.filterNot { it in drop } ?: return@forEach
                            if (senders.isEmpty()) merged.remove(key) else merged[key] = senders
                        }
                    }
                    if (byEmoji != null) {
                        byEmoji.forEach { (emoji, senders) ->
                            merged[emoji] = (merged[emoji].orEmpty() + senders).distinct()
                        }
                    }
                    amended = amended.copy(reactions = merged)
                }
                amended
            }
        }
    }

    // Pseudo-messages are never rendered on their own; only real messages are
    // appended.
    val appendable = incoming.filterNot {
        it.content is MessageContent.Reaction || it.content is MessageContent.Edit
    }
    if (appendable.isEmpty()) return result ?: messages

    // Merge regardless of whether anything is "fresh": an arriving event with the
    // same transactionId as an optimistic local copy must REPLACE it, which is
    // what de-duplicating first would have thrown away.
    val current = result ?: messages
    return (current + appendable)
        .groupBy { it.transactionId ?: it.id }
        .map { it.value.last() }
        .sortedBy { it.timestamp }
}
