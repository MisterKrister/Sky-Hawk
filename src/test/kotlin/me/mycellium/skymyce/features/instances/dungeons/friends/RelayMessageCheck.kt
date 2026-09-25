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
