package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.hud.HudTheme
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI

internal val lfgButtonAction = Regex("^(yes|no) ([a-f0-9]{16})$")
internal fun userMessageError(name: String, message: String): String? = when {
    !name.matches(Regex("[A-Za-z0-9_]{1,16}")) -> "Use the player's real Minecraft name."
    message.isBlank() || message.length > 256 -> "Messages must contain 1–256 characters."
    message.any { it.isISOControl() || it == '§' } -> "Messages cannot contain control or formatting codes."
    else -> null
}
internal fun relayRecipients(names: List<String>, prefix: String) = names.filter {
    it.matches(Regex("[A-Za-z0-9_]{1,16}")) && it.startsWith(prefix, true)
}.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.take(30)
internal class RelayConversation {
    private var session = ""
    var replyTarget: String? = null
        private set
    fun session(key: String) { if (key != session) { session = key; replyTarget = null } }
    fun conversation(name: String) { if (name.matches(Regex("[A-Za-z0-9_]{1,16}"))) replyTarget = name }
    fun clear() { session = ""; replyTarget = null }
}

/** Ordinary private messages never enter the LFG parser and never fall back to Hypixel chat. */
object RelayMessages {
    private val conversations = RelayConversation()
    private val quiet = ThreadLocal.withInitial { false }
    private var nextSend = 0L
    @JvmStatic fun suppressChatLog() = quiet.get()
    fun reset() { conversations.clear(); nextSend = 0 }
    private fun session() { conversations.session("${MC.instance.user.profileId}|${LocationAPI.onHypixel}") }
    fun recipients(prefix: String): List<String> = relayRecipients(FriendsAPI.friends.map { it.name } + DungeonFriends.scanner.online.values.map { it.name }, prefix)
    fun send(name: String, text: String) {
        session()
        val problem = userMessageError(name, text)
        if (problem != null) { error(problem); return }
        if (!LocationAPI.isOnSkyBlock || MC.instance.player == null) { error("Join SkyBlock before messaging friends."); return }
        if (!DungeonFriends.isRelayFriend(name)) { error("That player is not in your cached friends list."); return }
        if (name.equals(MC.instance.user.name, true)) { error("Choose another player."); return }
        if (!DungeonFriendRelay.connected) { error("Relay unavailable. Reconnect and try again; nothing was sent to Hypixel chat."); return }
        if (!DungeonFriendRelay.userMessagesAvailable) { error("This relay does not support private messages yet."); return }
        val now = System.currentTimeMillis()
        if (now < nextSend) { error("Wait a moment before sending another message."); return }
        nextSend = now + 1000
        val account = MC.instance.user.profileId
        val accepted = DungeonFriendRelay.sendUserMessage(name, text, {
            if (MC.instance.user.profileId == account && LocationAPI.isOnSkyBlock) {
                conversations.conversation(name)
                display(Component.literal("Delivered to $name ✓").withColor(HudTheme.GREEN))
            }
        }, { reason -> if (MC.instance.user.profileId == account) error(deliveryFailure(reason)) })
        if (accepted) display(Component.literal("Me → $name: ").withColor(HudTheme.ACCENT)
            .append(Component.literal(text).withColor(HudTheme.TEXT))
            .append(Component.literal("  [sending…]").withColor(HudTheme.MUTED)))
        else error("Message queue unavailable. Nothing was sent to Hypixel chat.")
    }
    fun reply(text: String) {
        session()
        val name = conversations.replyTarget
        if (name == null) error("No current conversation. Use /sm msg <ign> <message> first.") else send(name, text)
    }
    fun receive(name: String, uuid: String, text: String): Boolean {
        if (!LocationAPI.isOnSkyBlock || !DungeonFriends.acceptsRelayIdentity(name, uuid) || userMessageError(name, text) != null) return false
        session(); conversations.conversation(name)
        val reply = Component.literal("  [Reply]").withColor(HudTheme.ACCENT).withStyle {
            it.withClickEvent(ClickEvent.SuggestCommand("/sm msg $name "))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Prepare a private reply to $name. Clicking does not send it.")))
        }
        display(Component.literal("$name → Me: ").withColor(HudTheme.SECONDARY)
            .append(Component.literal(text).withColor(HudTheme.TEXT)).append(reply))
        return true
    }
    fun legacy(name: String, text: String) {
        if (lfgButtonAction.matches(text)) action(name, text)
        else { display(Component.literal("/sm relaymsg is deprecated; use /sm msg.").withColor(HudTheme.MUTED)); send(name, text) }
    }
    fun action(name: String, text: String) {
        if (!lfgButtonAction.matches(text) || !DungeonFriends.relayReply(name, text)) error("Invalid or unavailable LFG action. Open a current invitation.")
    }
    private fun error(text: String) = display(Component.literal(text).withColor(HudTheme.RED))
    private fun display(message: Component) {
        val previous = quiet.get(); quiet.set(true)
        try { MC.instance.gui.chat.addClientSystemMessage(Component.literal("[Sky-Hawk] ").withColor(HudTheme.ACCENT).append(message)) }
        finally { quiet.set(previous) }
    }
}

internal fun deliveryFailure(reason: String) = when (reason) {
    "offline" -> "Recipient is offline on this relay. Message was not delivered."
    "recipient_disallowed" -> "Recipient's friend/identity check rejected the message."
    "recipient_unsupported" -> "Recipient needs a version supporting private messages."
    "rate_limited" -> "Relay rate limit reached. Wait a few seconds and retry."
    "recipient_busy" -> "Recipient is busy. Try again shortly."
    "receipt_timeout" -> "No recipient acknowledgement arrived. Delivery is unconfirmed."
    else -> "Connection failed. Delivery is unconfirmed; nothing was sent to Hypixel chat."
}
