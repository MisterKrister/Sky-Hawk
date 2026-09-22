package me.mycellium.skymyce.features.instances.dungeons.friends

/** Only suppresses recognizable responses to a scan we actually requested. All times are monotonic milliseconds. */
class FriendListScanner(cached: Collection<OnlineDungeonFriend>? = null) {
    val online = cached.orEmpty().associateByTo(linkedMapOf()) { it.name.lowercase() }
    var hasScanned = cached != null
        private set
    var version = 0L
        private set
    var scanning = false
        private set
    var status = if (hasScanned) "${online.size} cached friends online" else "Waiting for friend list"
        private set
    private var page = 1
    private var lastPage = 1
    var completedPages = 0
        private set
    val remainingPages: Int get() = if (scanning) (lastPage - completedPages).coerceAtLeast(1) else 0
    private var waiting = false
    private var headerSeen = false
    private var deadline = 0L
    private var nextCommand = 0L
    private var requested = !hasScanned
    private var stopAfterResponse = false
    private var offlineSeen = false
    private val seen = mutableSetOf<String>()

    fun refresh(now: Long) {
        if (!scanning) {
            requested = true
            nextCommand = maxOf(now, nextCommand)
        }
    }

    fun tick(now: Long): String? {
        if (waiting && now >= deadline) {
            scanning = false
            waiting = false
            status = "Friend scan timed out; click Refresh to retry"
        }
        if (!scanning) {
            if (!requested) return null
            requested = false
            hasScanned = true
            version++
            scanning = true
            page = 1
            lastPage = 1
            completedPages = 0
            stopAfterResponse = false
            offlineSeen = false
            seen.clear()
        }
        if (waiting || now < nextCommand) return null
        waiting = true
        headerSeen = false
        deadline = now + 10000
        nextCommand = now + 1200
        status = "Scanning friends (page $page)"
        return "friend list $page"
    }

    fun manualCommand() {
        // The response already in flight still belongs to us; suppress it before yielding to manual output.
        stopAfterResponse = waiting
        if (!waiting) scanning = false
        requested = false
        hasScanned = true
        version++
    }

    fun cancel() {
        scanning = false
        waiting = false
        requested = !hasScanned
    }

    fun notification(name: String, joined: Boolean) {
        val key = name.lowercase()
        if (joined) {
            online[key] = OnlineDungeonFriend(name, "Unknown")
            seen += key
        } else {
            online.remove(key)
            seen.remove(key)
        }
        version++
    }

    fun receive(message: String, now: Long): Boolean {
        if (!waiting || now >= deadline) return false
        val lines = message.replace(Regex("§."), "").lines().map { it.trim() }
        // Never swallow a mixed packet containing unrelated chat.
        if (lines.any { !recognized(it) }) return false
        if (!headerSeen && lines.none { HEADER.containsMatchIn(it) } && lines.any { ENTRY.matches(it) }) return false
        for (line in lines) {
            val header = HEADER.find(line)
            if (header != null) {
                if (header.groupValues[1].toIntOrNull() != page) return false
                lastPage = header.groupValues[2].toIntOrNull()?.coerceIn(1, 1000) ?: return false
                headerSeen = true
            } else if (EMPTY.matches(line)) {
                lastPage = page
                headerSeen = true
                finishPage(now)
            } else if (ERROR.matches(line)) {
                scanning = false
                waiting = false
                status = "Friend scan stopped by server; click Refresh to retry"
            } else {
                val entry = ENTRY.matchEntire(line)
                if (entry != null && headerSeen) {
                    val (name, location) = entry.destructured
                    val key = name.lowercase()
                    if (location.startsWith("offline", true) || location.startsWith("currently offline", true)) {
                        offlineSeen = true
                        online.remove(key)
                    } else if (!offlineSeen) {
                        seen += key
                        online[key] = OnlineDungeonFriend(name, location)
                    }
                    version++
                } else if (SEPARATOR.matches(line) && headerSeen) {
                    finishPage(now)
                }
            }
        }
        return true
    }

    private fun finishPage(now: Long) {
        if (!waiting) return
        completedPages = page
        waiting = false
        if (stopAfterResponse) {
            scanning = false
            status = "Background scan paused after manual command"
            return
        }
        // Drain this response's footer, but never request another page after the first offline entry.
        if (offlineSeen || page >= lastPage) {
            online.keys.retainAll(seen)
            version++
            scanning = false
            status = "${online.size} friends online"
        } else {
            page++
            nextCommand = now + 1200
        }
    }

    private fun recognized(line: String) = line.isEmpty() || HEADER.containsMatchIn(line) ||
        ENTRY.matches(line) || SEPARATOR.matches(line) || EMPTY.matches(line) || ERROR.matches(line) ||
        line.matches(Regex("(?:<<|>>|<-|->|Previous Page|Next Page|Click to view.*)(?:.*)"))

    companion object {
        private val HEADER = Regex("^(?:<<\\s*)?Friends \\(Page (\\d+) of (\\d+)\\)(?:.*)$")
        private val ENTRY = Regex("^(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}) is (.+)$")
        private val SEPARATOR = Regex("^[-▬─]{5,}$")
        private val EMPTY = Regex("^(?:You (?:don't|do not) have any friends(?: yet)?[!.]?|Your friends list is empty[!.]?)$")
        private val ERROR = Regex("^(?:You are sending commands too fast!.*|Please wait.*before using this command.*|You can only use this command.*|Invalid page number.*)$")
    }
}
