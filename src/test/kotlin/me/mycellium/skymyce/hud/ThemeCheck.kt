package me.mycellium.skymyce.hud

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.hud.widget.parseWidgetConfig
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Files

fun checkThemes() {
    val originalConfig = JsonParser.parseString("""{"Theme & Appearance":{"primary":123,"unknown":true},"other":{"value":7}}""").asJsonObject
    val newConfig = JsonParser.parseString("""{"Theme & Appearance":{"primary":456,"themeVersion":1}}""").asJsonObject
    val merged = me.mycellium.skymyce.config.mergeConfigFields(originalConfig, newConfig)
    check(merged.getAsJsonObject("Theme & Appearance").get("unknown").asBoolean && merged.getAsJsonObject("other").get("value").asInt == 7)
    check(me.mycellium.skymyce.config.mergeConfigFields(merged, newConfig) == merged)
    val global = ThemeOverrides(primary = 0x123456, opacity = 72)
    val widget = ThemeOverrides(primary = 0x654321, rounded = false)
    check(resolveTheme(ThemePreset.LIGHT, global).primary == global.primary)
    check(resolveTheme(ThemePreset.LIGHT, global).text == ThemePreset.LIGHT.palette.text)
    check(resolveTheme(ThemePreset.LIGHT, global, widget).let { it.primary == widget.primary && it.opacity == 72 && !it.rounded })
    check(resolveTheme(ThemePreset.LIGHT, global.copy(primary = 0xFFFFFF), widget).primary == widget.primary)
    check(resolveTheme(ThemePreset.LIGHT, global.copy(primary = 0xFFFFFF), widget.copy(primary = null)).primary == 0xFFFFFF)
    val doc = ThemeDocument(preset = ThemePreset.HIGH_CONTRAST, overrides = widget)
    check(parseThemeDocument(exportThemeDocument(doc)) == doc)
    check(parseThemeDocument("""{"version":1}""").overrides == ThemeOverrides())
    listOf("{}", "{", """{"version":2}""", """{"version":"1"}""", """{"version":1,"overrides":{"opacity":0}}""",
        """{"version":1,"overrides":{"primary":-1}}""", """{"version":1,"overrides":{"primary":"123"}}""",
        """{"version":1,"overrides":{"padding":1}}""", """{"version":1,"overrides":{"shadow":"false"}}""",
        """{"version":1,"overrides":{"script":"run"}}""", " ".repeat(16385)).forEach { check(runCatching { parseThemeDocument(it) }.isFailure) }
    val old = JsonParser.parseString("""{"x":480,"y":290,"scale":1.5,"anchor":"BOTTOM_RIGHT","unknownPreference":true}""")
    val migrated = requireNotNull(parseWidgetConfig(old))
    check(migrated.x == 480 && migrated.y == 290 && migrated.scale == 1.5f && migrated.theme == null)
    val customized = migrated.copy(theme = widget)
    val restored = parseWidgetConfig(Gson().toJsonTree(customized))
    check(restored == customized && restored?.copy(theme = null) == migrated)
    val corruptedTheme = old.deepCopy().asJsonObject.apply { add("theme", JsonParser.parseString("""{"primary":99999999}""")) }
    check(parseWidgetConfig(corruptedTheme) == migrated)
    val previous = ThemeConfig.overrides(); val previousPreset = ThemeConfig.preset
    ThemeConfig.preset = ThemePreset.LIGHT; ThemeConfig.apply(global)
    val themed = themedHudText("§bTitle §fValue §aReady §cFailed")
    check(themed.string == "Title Value Ready Failed")
    check(themed.siblings.map { it.style.color?.value } == listOf(HudTheme.ACCENT, HudTheme.TEXT, HudTheme.GREEN, HudTheme.RED))
    check(HudTheme.TEXT == ThemePreset.LIGHT.palette.text)
    HudTheme.withWidget(widget) { check(HudTheme.ACCENT == widget.primary && HudTheme.OPACITY == 72) }
    check(HudTheme.ACCENT == global.primary)
    ThemeConfig.apply(previous); ThemeConfig.preset = previousPreset
    val dir = Files.createTempDirectory("sky-hawk-theme-check")
    try {
        val path = dir.resolve("theme.json"); val file = AtomicJsonFile(path)
        check(file.read() == null)
        Files.writeString(path, "broken")
        check(file.read() == null && file.damaged)
        file.write(JsonParser.parseString(exportThemeDocument(doc)))
        check(parseThemeDocument(Files.readString(path)) == doc)
        Files.list(dir).use { paths -> check(paths.anyMatch { it.fileName.toString().contains("corrupt-") }) }
    } finally { Files.list(dir).use { it.forEach(Files::delete) }; Files.delete(dir) }
    println("Theme checks passed: inheritance, resets, safe import, saved layout compatibility and corrupt-file recovery")
}
