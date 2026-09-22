package me.mycellium.skymyce.features.instances.dungeons.friends

/** Only suppresses recognizable responses to a scan we actually requested. All times are monotonic milliseconds. */
class FriendListScanner {
    val online = linkedMapOf<String, OnlineDungeonFriend>()
    var scanning = false
        private set
    var status = "Waiting for friend list"
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
    private var nextScan = 0L
    private var stopAfterResponse = false
    private val seen = mutableSetOf<String>()

    fun refresh(now: Long) { if (!scanning) nextScan = minOf(nextScan, maxOf(now, nextCommand)) }

    fun tick(now: Long): String? {
        if (waiting && now >= deadline) {
            scanning = false
            waiting = false
            status = "Friend scan timed out; retrying later"
            nextScan = now + 60000
        }
        if (!scanning) {
            if (now < nextScan) return null
            scanning = true
            page = 1
            completedPages = 0
            stopAfterResponse = false
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

    fun manualCommand(now: Long) {
        // The response already in flight still belongs to us; suppress it before yielding to manual output.
        stopAfterResponse = waiting
        if (!waiting) scanning = false
        nextScan = now + 60000
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
                nextScan = now + 60000
                status = "Friend scan delayed by server"
            } else {
                val entry = ENTRY.matchEntire(line)
                if (entry != null && headerSeen) {
                    val (name, location) = entry.destructured
                    val key = name.lowercase()
                    seen += key
                    if (location.startsWith("offline", true)) online.remove(key)
                    else online[key] = OnlineDungeonFriend(name, location)
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
        if (page >= lastPage) {
            online.keys.retainAll(seen)
            scanning = false
            nextScan = now + 60000
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
