package me.mycellium.skymyce.features.social

import com.google.gson.JsonParser
import java.util.UUID
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.ClickEvent
import net.minecraft.util.FormattedCharSequence
import net.minecraft.client.StringSplitter

fun checkCosmetics() {
    fun parse(text: String) = parseCosmeticProfile(JsonParser.parseString(text).asJsonObject)
    val raw = """{"uuid":"${"a".repeat(32)}","name":"Hawk","scale":1.5,"revision":1,"updatedAt":1700000000000}"""
    val profile = requireNotNull(parse(raw))
    check(profile.name == "Hawk" && profile.scale == 1.5f)
    listOf(raw.replace("1.5", "2.01"), raw.replace("1.5", "0.49"), raw.replace("1.5", "\"NaN\""),
        raw.replace("\"Hawk\"", "123"), raw.replace("Hawk", "§aHawk"), raw.replace("Hawk", "a\\nb"),
        raw.replace("Hawk", "x".repeat(33)), raw.replace("Hawk", "<click:run>"), raw.replace("Hawk", "@everyone"),
        raw.replace("\"revision\":1", "\"revision\":-1"), raw.replace("\"revision\":1", "\"revision\":1.5"),
        raw.dropLast(1) + ",\"discord\":\"private\"}").forEach { check(parse(it) == null) }
    val cache = CosmeticCache(2)
    check(cache.apply(profile, 1000))
    val reset = profile.copy(name = null, scale = 1f, revision = 3, updatedAt = profile.updatedAt + 100)
    check(cache.apply(reset, 1100))
    check(!cache.apply(profile.copy(revision = 2), 1200)) // Late snapshot cannot undo a live reset.
    check(!cache.apply(profile.copy(revision = 3), 1200)) // Same revision must not change payload.
    check(cache.get(profile.uuid, 1300) == reset)
    check(cache.get(profile.uuid, 601101) == null) // Bounded outage rendering, without network work in a hook.
    check(cache.apply(reset, 700000) && cache.get(profile.uuid, 700001) == reset) // Reconnect reads can refresh identical revisions.
    cache.apply(profile.copy(uuid = UUID(0, 2)), 700002); cache.apply(profile.copy(uuid = UUID(0, 3)), 700003)
    check(cache.size == 2 && cache.get(profile.uuid, 700004) == null)
    cache.clear(); check(cache.size == 0)
    check(parse(raw.replace("\"Hawk\"", "null").replace("1.5", "1").replace("\"revision\":1", "\"revision\":0").replace("1700000000000", "0")) != null)
    val extended = JsonParser.parseString(raw).asJsonObject.apply {
        add("nameStyle", JsonParser.parseString("""{"text":"Hawk","bold":true,"color":"gold"}"""))
        addProperty("scaleX", .6); addProperty("scaleY", 1.5); addProperty("scaleZ", 2)
    }
    val axes = requireNotNull(parseCosmeticProfile(extended))
    check(axes.scaleX == .6f && axes.scaleY == 1.5f && axes.scaleZ == 2f)
    check(axes.nameStyle!!.style.isBold)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("scaleX", 2.1) }) == null)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("scaleY", "1.5") }) == null)
    check(parseCosmeticProfile(extended.deepCopy().apply { addProperty("name", "Wrong") }) == null)
    for (unsafe in listOf("""{"text":"Hawk","clickEvent":{"action":"run_command","command":"/op"}}""", """{"translate":"anything"}""", """{"text":"Hawk","font":"remote:font"}""", """{"text":"Hawk","bold":"true"}""")) {
        check(runCatching { parseCosmeticText(JsonParser.parseString(unsafe)) }.isFailure)
    }
    fun text(sequence: FormattedCharSequence) = buildString { sequence.accept { _, _, code -> appendCodePoint(code); true } }
    val names = CosmeticNameMap()
    val replacement = parseCosmeticText(JsonParser.parseString("""{"text":"Sky","bold":true,"extra":[{"text":" Hawk","color":"#67ccf2"}]}"""))
    check(names.update(mapOf("Alice" to replacement, "Bob" to Component.literal("Alice"))))
    check(!names.update(mapOf("Alice" to replacement, "Bob" to Component.literal("Alice"))))
    val input = Component.literal("[MVP] Al").append(Component.literal("ice").withStyle(Style.EMPTY.withItalic(true))).append(" / Bob / Alice123 / xAlice / Alice_x")
    val result = names.sequence(input.visualOrderText)
    check(text(result) == "[MVP] Sky Hawk / Alice / Alice123 / xAlice / Alice_x")
    check(names.sequence(result) === result) // No recursive replacement inside another cosmetic name.
    check(input.string == "[MVP] Alice / Bob / Alice123 / xAlice / Alice_x")
    check(text(names.string("§aALICE, Alice.")) == "Sky Hawk, Sky Hawk.")
    val action = ClickEvent.SuggestCommand("/party Alice")
    val clickable = names.sequence(Component.literal("Alice").withStyle { it.withClickEvent(action) }.visualOrderText)
    clickable.accept { _, style, _ -> check(style.clickEvent == action); true }
    val splitter = StringSplitter { _, style -> if (style.isBold) 2f else 1f }
    check(splitter.stringWidth(names.string("Alice")) == 16f) // Styled displayed width, not five real-name characters.
    check(text(names.string("Unknown")) == "Unknown")
    names.update(emptyMap()); check(text(names.string("Alice")) == "Alice")
    CosmeticNames.enabled = true; CosmeticNames.update(mapOf("Alice" to replacement))
    check(CosmeticNames.display(CosmeticNames.original(Component.literal("Alice"))).string == "Alice")
    val displayed = CosmeticNames.display(Component.literal("Alice").visualOrderText)
    check(text(displayed) == "Sky Hawk")
    CosmeticNames.enterOriginal()
    try { check(text(CosmeticNames.display(displayed)) == "Alice") } finally { CosmeticNames.exitOriginal() }
    CosmeticNames.enabled = false
    check(text(CosmeticNames.display(displayed)) == "Alice")
    CosmeticNames.update(emptyMap())
    println("Cosmetic client checks passed: strict public payloads, scale/name limits, snapshot/live ordering, reset tombstones, reconnect freshness and bounded offline cache")
}
