# SkyMyce

**SkyMyce** is a Modern Minecraft Fabric mod for **Hypixel SkyBlock**. It includes a variety of QOL features made to improve your gameplay experience while being fast and lightweight.

> **Getting Started:** Run `/skymyce` or `/sm` while in game to open the config.

---

# Features
## General
- **Client Info Widget**: Shows client info as a widget
- **Auto Refill**:  Automatically refill items from your sack when you run out of them
- **Skill XP Widget**: Moves the XP gain from your action bar to a widget
- **Ability Cooldown Widget**: Displays a widget showing the cooldown of your currently held item
- **Party Commands**: Adds useful commands while in parties using the "!" prefix
- **Stash Helper**: Highlights and blocks clicks for worthless items when in your stash
- **Wardrobe/Loadout Keybinds**: Allows you to use keybinds to quickly swap slots while in your wardrobe/loadout
## Instances
### Misc
- **Notify Missing Players**: Notifies you of missing players when joining an instance
- **Auto Requeue**: Automatically requeues you at the end of the instance
### Dungeon
- **Dungeon Win Screen**: Displays an animation that shows your run summary when your dungeon ends
- **Dungeon Message Filter**: Filters annoying chat messages while in a dungeon
- **Safe Chest Reroll**: Blocks accidental chest rerolls if they contain a valuable item (configurable)
- **Dungeon Chest Profit**: Shows the value of the items while in a dungeon chest
- **Dungeon Friends & LFG (/skymyce pf)**: Shows online friends with floor eligibility, Catacombs/class levels, PBs, location badges, and quick party invites/messages.
### Dungeon Tracker
- **Tracker Screen (/skymyce dungeon)**: Opens a detailed overview of your dungeon runs
- **Widget**: Shows a summary of your dungeon tracker while in the dungeon hub or at the end of your dungeon run

---

# Dungeon Friends & LFG

Open `/skymyce pf` (or `/sm pf`). Stats use installed **SkyBlocker** or **SkyBlock Profile Viewer (SkyBlockPv)** providers, following DungeonProgressHud's integration. No personal API key is required when a provider is working. **API setup** opens the optional Hypixel API-key fallback. The floor defaults to your highest completed tier, checking Master Mode first; selecting a floor manually keeps your choice for that screen.

- Friends refresh silently every minute in SkyBlock, including all friend-list pages. Disable background scans under **Instances > Dungeons > Dungeon Friends**. Opening the assistant also scans. Manually requested friend lists remain visible.
- The centered dark menu uses cyan accents, aligned Catacombs/all five class levels, and the selected floor's **S+ PB**. Click **CATA**, any class heading, or **S+ PB** to sort; click again to reverse. The **Sort** menu also exposes each column for smaller windows. Missing values stay last in either direction. The highest class is highlighted; levels are numbers without bars, including virtual levels above 50.
- Known stats must meet the floor's Catacombs requirement and include a completion time. Hidden or unavailable values appear as **—**, with the reason on hover. S+ PB sorting puts friends without S+ times last; an ordinary completion still qualifies but is never displayed as an S+ time.
- **Missing** has independent checkboxes for the classes you need. It follows open party classes until you make a manual selection; **Use party classes** restores that behavior. Clear all boxes to show everyone. Class matching uses the highest class plus the secondary classes recorded with **Edit**. Hidden stats without recorded classes remain visible. Messages choose a matching class for that friend from the checked classes.
- **Settings** edits the message template using `{name}`, `{class}`, and `{floor}`. Secondary classes and the template are saved in `config/skymyce/dungeon_friends.json`. The existing Hypixel API-key setting is reused.
- Invites and messages require a confirmed party size, are disabled at 5 members, and have a one-second click cooldown. Party Finder lore, party announcements, and dungeon teammate data update the composition; the API's last selected class is used when a current class has not been observed.
- After sending LFG through **/msg**, short positive private replies such as `yes`, `invite me`, or `sure 1s` mark the friend **Accepted** and highlight **/p** in green. Nothing is invited automatically. Ambiguous replies are marked **Replied**, with the text on hover. Only recently messaged players are tracked, for five minutes; replies are not saved to disk.
- The server message **You are now friends with [username]** immediately queues that player's stats and refreshes their location. No specific username is hardcoded.
- Badges use the location Hypixel actually reports. `Dungeons`/`Catacombs` means **In Run**, while Hub/Island means **Idle**. Generic `SkyBlock` or private locations show **Unknown**; the mod cannot infer a private location.

Normalized stats, UUIDs, and expiry times are saved atomically in `config/skymyce/dungeon_friend_stats.json` and restored next session. Every new player is checked once; subsequent automatic and manual stat refreshes only update known **Cata > 40** players, using a ten-minute cache. Players at exactly 40 or below, hidden profiles, and absent profiles keep their saved result. Failed initial requests may retry; a new-friend notification or local profile change explicitly rechecks that player. Online/location scans still cover all friends.

Requests run off the client thread, one player at a time, with pacing and API backoff. SkyBlockAPI's HTTP helper handles the direct Hypixel fallback. The footer shows the player being loaded and remaining queue. Refresh and network errors preserve previously displayed stats and do not bypass rate limits. An unreadable cache is preserved and reported in the log, with memory-only operation for that session. No extra dependency is bundled.

Build with JDK 25 and `./gradlew build` (`gradlew.bat build` on Windows). `./gradlew dungeonFriendsCheck` runs the standalone checks for scanning, eligibility, hidden stats, party capacity/class rotation, sorting, and message rendering without connecting to Minecraft or Hypixel. Live server chat formats and GUI interaction still need an in-game smoke test.

The checkout originally referenced a missing `PersonalBestCommand`; the `!pb F7` / `!pb M7` handler now shares the assistant's cache. If its stats have not loaded yet, retry shortly.

---

# Dependencies

Sky-Myce uses the following libraries:
> Bundled dependencies do not need to be installed separately.

| Dependency | Bundled? |
|------------|--------|
| **SkyblockAPI** | ✅ |
| **Resourceful Config** | ✅ |
| **owo-lib** | ❌ |

---

# Installation

1. Install the **[Fabric Installer](https://fabricmc.net/use/installer/)**.
2. Download the latest compatible SkyMyce version from the **[Releases](https://github.com/mycell1um/Sky-Myce/releases)** page.
3. Locate the downloaded `.jar` file.
4. Move the `.jar` file into your Minecraft `mods` folder.
5. Launch Minecraft using your Fabric profile.

## Minecraft Mods Folder

| Operating System | Location |
|------------------|----------|
| **Windows** | `%appdata%\.minecraft\mods` |
| **macOS** | `~/Library/Application Support/minecraft/mods` |
| **Linux** | `~/.minecraft/mods` |

---
