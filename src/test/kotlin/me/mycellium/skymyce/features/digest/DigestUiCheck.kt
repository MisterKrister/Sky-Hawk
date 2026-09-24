package me.mycellium.skymyce.features.digest

import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing

/** Called from the existing HUD check; no Minecraft window, HTTP, or disk required. */
fun digestUiCheck() {
    for ((width, height) in listOf(320 to 180, 426 to 240, 480 to 270, 640 to 360, 960 to 540, 1920 to 1080)) {
        for (compact in listOf(false, true)) {
            val layout = DigestLayout.fit(width, height, compact)
            check(layout.width <= width && layout.height <= height)
            check(layout.cardWidth >= 100)
            check(layout.cardWidth * layout.columns + layout.gap * (layout.columns - 1) <= layout.contentWidth)
            val row = UIContainers.horizontalFlow(Sizing.fixed(layout.contentWidth), Sizing.content()).gap(layout.gap)
            repeat(layout.columns) {
                row.child(UIContainers.verticalFlow(Sizing.fixed(layout.cardWidth), Sizing.content()).apply {
                    padding(Insets.of(8))
                    child(UIComponents.box(Sizing.fill(), Sizing.fixed(20)))
                    child(UIComponents.box(Sizing.fill(), Sizing.fixed(if (it == 0) 60 else 120)))
                })
            }
            row.inflate(Size.of(width, height)); row.mount(null, 0, 0)
            check(row.children().last().x() + row.children().last().width() <= layout.contentWidth)
            check(row.height() <= 156) // Uneven cards keep their measured height instead of overlapping.
            row.children().forEach { check(it.width() == layout.cardWidth) }
        }
    }
    check(DigestLayout.fit(960, 540).columns == 2)
    check(DigestLayout.fit(320, 180).columns == 1)
    check(DigestLayout.fit(427, 240).columns == 2)
    check(DigestLayout.fit(480, 270).columns == 2)
    check(digestText("Long news title ".repeat(100), 90).length == 90)
    check(digestText("Long item name ".repeat(100), 300).length == 300)
    check(digestText("Long player name ".repeat(100), 100).length == 100)
    check(digestText("§aBad\u0000\ttext\nline") == "aBadtext\nline")
    check(digestTimeRemaining(10_000, 10_001) == "Ready now")
    check(digestTimeRemaining(70_000, 10_000) == "1m 0s remaining")
    check(digestTimeRemaining(3_610_000, 10_000) == "1h 0m remaining")
    check(digestRelativeTime(10_000, 5_000) == "Just now")
    check(digestRelativeTime(0, 86_400_000) == "1d ago")
    check(digestLink("https://discord.com/channels/1/2/3") != null)
    listOf("http://example.com", "javascript:alert(1)", "file:///etc/passwd", "https://user:secret@example.com", "https://example.com:81", "not a url").forEach {
        check(digestLink(it) == null)
    }
    val local = DigestEntry("1", "Necron's Handle", meta = "ExamplePlayer", timestamp = 10, activity = "Dungeons")
    val remote = local.copy(id = "2", timestamp = 20, community = true)
    val slayer = local.copy(id = "3", title = "Warden Heart", timestamp = 30, activity = "Slayer")
    check(digestHistory(listOf(local, remote, slayer), false, "", "").map { it.id } == listOf("3", "1"))
    check(digestHistory(listOf(local, remote, slayer), false, "Dungeons", "handle") == listOf(local))
    check(digestHistory(listOf(local, remote), true, "", "exampleplayer") == listOf(remote))
    check(digestHistory(emptyList(), false, "", "").isEmpty())
    val partial = DigestView(cards = listOf(DigestCardState("news", "News", "Offline"),
        DigestCardState("dailies", "Dailies", "Available", entries = listOf(local))))
    check(partial.cards.first().entries.isEmpty() && partial.cards.last().entries.size == 1)
    println("Digest UI checks passed: responsive retained layout, bounded text, countdowns, safe links and filtered history")
}
