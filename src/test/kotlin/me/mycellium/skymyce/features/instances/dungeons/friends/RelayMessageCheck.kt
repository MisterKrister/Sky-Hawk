package me.mycellium.skymyce.features.instances.dungeons.friends

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType

fun checkUserMessages() {
    check(userMessageError("Alice", "yes") == null)
    check(userMessageError("Not a real IGN", "hello") != null)
    listOf("", " ", "x".repeat(257), "a\nb", "§ahello", "a\u007fb").forEach { check(userMessageError("Alice", it) != null) }
    check(StringArgumentType.greedyString().parse(StringReader("hello there yes no")) == "hello there yes no")
    check(relayRecipients(listOf("Bob", "alice", "Alice", "Other", "unsafe name"), "A") == listOf("alice"))
    val conversations = RelayConversation()
    conversations.session("account/session"); check(conversations.replyTarget == null)
    conversations.conversation("Alice"); conversations.conversation("[SkyMyce control]")
    check(conversations.replyTarget == "Alice")
    conversations.session("account/session"); check(conversations.replyTarget == "Alice")
    conversations.session("other/session"); check(conversations.replyTarget == null)
    checkRelayChat(conversations)
    check(!lfgButtonAction.matches("yes") && !lfgButtonAction.matches("no") && !lfgButtonAction.matches("yes expired"))
    check(lfgButtonAction.matches("yes abcdef1234567890") && lfgButtonAction.matches("no abcdef1234567890"))
    val receipts = RelayDeliveries()
    var delivered = false; var failure: String? = null
    receipts.add("id", "Alice", 0, { delivered = true }, { failure = it }, transmit = { true })
    receipts.tick(1); check(!delivered) // Forwarding is not a recipient receipt.
    receipts.acknowledge("id", "Wrong"); check(!delivered)
    receipts.acknowledge("id", "ALICE"); check(delivered)
    receipts.add("next", "Alice", 0, {}, { failure = it })
    receipts.fail("next", "recipient_disallowed"); check(failure == "recipient_disallowed")
    check("unconfirmed" in deliveryFailure("receipt_timeout"))
    check("Hypixel" in deliveryFailure("connection_lost"))
    receipts.add("ordinary", "Alice", 0, {}, { failure = it }, notifyOnClear = true)
    receipts.add("control", "Alice", 0, {}, { error("Leaving must not activate an LFG fallback") })
    receipts.clear(); check(failure == "connection_lost")
    println("Private-message checks passed: greedy input, real-name suggestions, session reply targets, explicit LFG tokens and acknowledged delivery")
}

private fun checkRelayChat(conversations: RelayConversation) {
    val sent = mutableListOf<Pair<String, String>>()
    val send: (String, String) -> Unit = { name, text -> sent += name to text }
    check(conversations.allowChat("public", send) && sent.isEmpty())
    conversations.chat("Alice")
    conversations.conversation("Bob") // An incoming message changes /reply, never the selected channel.
    check(conversations.replyTarget == "Bob" && conversations.chatTarget == "Alice")
    check(!conversations.allowChat("hello there", send))
    check(!conversations.allowChat("yes", send))
    check(sent == listOf("Alice" to "hello there", "Alice" to "yes"))
    conversations.chat("Not an IGN")
    check(conversations.chatTarget == "Alice")
    conversations.relayDisconnected()
    check(conversations.replyTarget == null && conversations.chatTarget == "Alice")
    // Send returning without delivery (offline, invalid input, rate limit, missing friend) stays private.
    check(!conversations.allowChat("private even while offline") { _, _ -> })
    conversations.session("other/session")
    check(conversations.chatTarget == "Alice")
    listOf("p invite Bob", "pc one-off message", "gc hello", "msg Bob hello", "sm msg Bob hello",
        "sm reply hi", "sm lfgreply Bob yes abcdef1234567890", "chat", "chat invalid", "chat party extra").forEach {
        check(!conversations.channelCommand(it) && conversations.chatTarget == "Alice")
    }
    listOf("a", "all", "p", "party", "g", "guild", "o", "officer", "c", "co", "coop", "co-op").forEach {
        conversations.chat("Alice")
        check(conversations.channelCommand(" CHAT   ${it.uppercase()} "))
        check(conversations.chatTarget == null && conversations.allowChat("normal chat", send))
    }
    conversations.chat("Alice")
    conversations.chat("Bob")
    check(!conversations.allowChat("new recipient", send) && sent.last() == "Bob" to "new recipient")
    conversations.leaveChat()
    check(conversations.allowChat("normal chat", send))
    conversations.chat("Alice")
    conversations.session("different account")
    check(conversations.chatTarget == null && conversations.replyTarget == null)
    conversations.chat("Alice")
    conversations.clear() // Leaving the Minecraft server ends the conversation.
    check(conversations.chatTarget == null && conversations.allowChat("other server", send))
    println("Relay-chat checks passed: sticky recipient, channel aliases, private failures, reconnect and session reset")
}
