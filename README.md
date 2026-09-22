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

The player relay is deployed at `wss://skyblock-relay.skyblock-relay.workers.dev/websocket` and included as the default. See the [relay setup and testing walkthrough](relay/README.md) for installation, Cloudflare maintenance, and connection troubleshooting.

Temporary diagnostic: `/skymyce connect <ign>` (or `/sm connect <ign>`) tests the relay connection to a friend. Both players must be in SkyBlock using the mod and the same relay room. The recipient sees the test and their mod acknowledges automatically; the sender sees the round-trip time or a failure within five seconds. This does not invite, change LFG replies, or fall back to Hypixel chat.

- Friends refresh silently every minute in SkyBlock, including all friend-list pages. Disable background scans under **Instances > Dungeons > Dungeon Friends**. Opening the assistant also scans. Manually requested friend lists remain visible.
- The centered dark menu uses cyan accents, aligned Catacombs/all five class levels, and the selected floor's **S+ PB**. Click **CATA**, any class heading, or **S+ PB** to sort; click again to reverse. The **Sort** menu also exposes each column for smaller windows. Missing values stay last in either direction. The highest class is highlighted; levels are numbers without bars, including virtual levels above 50.
- Known stats must meet the floor's Catacombs requirement and include a completion time. Hidden or unavailable values appear as **—**, with the reason on hover. S+ PB sorting puts friends without S+ times last; an ordinary completion still qualifies but is never displayed as an S+ time.
- **Missing** has independent checkboxes for the classes you need. It follows open party classes until you make a manual selection; **Use party classes** restores that behavior. Clear all boxes to show everyone. Class matching uses the highest class plus the secondary classes recorded with **Edit**. Hidden stats without recorded classes remain visible. Messages choose a matching class for that friend from the checked classes.
- **Available** lets you select the classes you can play on the menu's current floor and enter an **S+ PB limit**, such as `5:30.000`. While solo in SkyBlock, real Hypixel party invitations are accepted automatically only when the inviter has a verified S+ time at or faster than that limit. Auto join is off by default; uncheck every class to stop it. Ordinary invitations do not disclose their floor or class, so they use your configured floor and first available class.
- **Join** sends a relay request asking that friend to invite you for one of your available classes. A friend running this version can offer an open class and invite you back automatically after their offer is acknowledged by your mod; your mod then accepts the actual server invitation after checking their PB. Both sides need a connected relay and an S+ limit for the same floor. The host also checks your PB, invite permissions, party capacity, and open classes; only friends' structured relay requests trigger this behavior. Requests expire after a minute, and outstanding offers reserve their class and party slot. The host can leave their own Available classes unchecked to host without auto joining other parties. Automatic Join messages never fall back to Hypixel chat.
- **Settings** edits the message template using `{name}`, `{class}`, and `{floor}` and the relay address. Availability, the PB limit, secondary classes, the template, and relay address are saved in `config/skymyce/dungeon_friends.json`. The existing Hypixel API-key setting is reused for stats.
- **/msg** first tries the relay and waits for a receipt from the recipient's mod. A receipt means their mod received the message, not that they accepted. If no receipt arrives within five seconds, or the recipient/relay is offline, the LFG text is sent once through Hypixel `/msg`. Incoming relay messages have Yes, No, and Reply buttons, or use `/skymyce relaymsg Username your reply`. Ordinary positive replies still only highlight `/p`. Both transports respect command pacing; pending fallback messages are cancelled when leaving SkyBlock.
- Anyone with a verified Minecraft account may connect to the relay, but the mod only handles messages from known friends. Verification uses Minecraft's authentication library directly with Mojang; account tokens, passwords, and Hypixel keys never go to Cloudflare. The footer shows relay status, reconnects use backoff, and Settings has a Reconnect button. Clear the relay address to disable it. The server uses hibernating WebSockets, room isolation, bounded payloads, and connection/message limits without storing chat history.
- Invites and messages require a confirmed party size, are disabled at 5 members, and have a one-second click cooldown. Party Finder lore, party announcements, and dungeon teammate data update the composition; the API's last selected class is used when a current class has not been observed.
- Clicking a Party Finder listing captures its floor and member classes. When you join that leader, **Missing** automatically selects the remaining classes. The last party's leader, roster, floor, and classes are saved with your preferences and restored on reconnect when the complete roster still matches and the snapshot is under 24 hours old. Partial party updates preserve captured classes; confirmed departures remove them.
- After sending LFG through **/msg**, short positive private replies such as `yes`, `invite me`, or `sure 1s` mark the friend **Accepted** and highlight **/p** in green. Ordinary replies never trigger automatic invites. Ambiguous replies are marked **Replied**, with the text on hover. Only recently messaged players are tracked, for five minutes; replies are not saved to disk.
- The server message **You are now friends with [username]** immediately queues that player's stats and refreshes their location. No specific username is hardcoded.
- Badges use the location Hypixel actually reports. `Dungeons`/`Catacombs` means **In Run**, while Hub/Island means **Idle**. Generic `SkyBlock` or private locations show **Unknown**; the mod cannot infer a private location.

Normalized stats, UUIDs, and expiry times are saved atomically in `config/skymyce/dungeon_friend_stats.json` and restored next session. Every new player is checked once; subsequent automatic and manual stat refreshes only update known **Cata > 40** players, using a ten-minute cache. Players at exactly 40 or below, hidden profiles, and absent profiles keep their saved result. Failed initial requests may retry; a new-friend notification or local profile change explicitly rechecks that player. An active Join exchange or incoming invite also prioritizes a fresh lookup if its PB verification is older than ten minutes, regardless of Cata level. Unknown, hidden, or stale PBs never authorize automatic actions, and failed lookups do not extend verification. Online/location scans still cover all friends.

Requests run off the client thread, one player at a time, with pacing and API backoff. SkyBlockAPI's HTTP helper handles the direct Hypixel fallback. The footer shows the player being loaded and remaining queue. Refresh and network errors preserve previously displayed stats and do not bypass rate limits. An unreadable cache is preserved and reported in the log, with memory-only operation for that session. No extra dependency is bundled.

Build with JDK 25 and `./gradlew build` (`gradlew.bat build` on Windows). `./gradlew dungeonFriendsCheck` runs the standalone checks for scanning, eligibility, hidden stats, party capacity/class rotation, sorting, message rendering, the Join exchange, invitation authenticity, PB limits, and saved party composition without connecting to Minecraft or Hypixel. Live server interaction and the GUI still need an in-game smoke test.

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
