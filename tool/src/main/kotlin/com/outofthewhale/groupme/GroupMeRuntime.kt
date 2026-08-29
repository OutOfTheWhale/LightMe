package com.outofthewhale.groupme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Tool-wide holder for the authenticated API client and the realtime push connection.
 * Started after login or session restore, stopped on logout or when the tool pauses.
 */
internal object GroupMeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var api: GroupMeApi? = null
        private set

    private var faye: FayeClient? = null
    private var startedForToken: String? = null

    /** Live messages pushed from GroupMe while the tool is open. */
    val incomingMessages = MutableSharedFlow<GroupMeMessage>(extraBufferCapacity = 64)

    fun start(credentials: GroupMeCredentials) {
        if (startedForToken == credentials.token && faye != null) return
        stop()
        api = GroupMeApi(credentials.token)
        faye = FayeClient(credentials.token, credentials.userId, scope) { message ->
            incomingMessages.tryEmit(message)
        }.also { it.start() }
        startedForToken = credentials.token
    }

    /** Reconnects the push socket, e.g. when the tool comes back to the foreground. */
    fun resume() {
        faye?.start()
    }

    /** Drops the push socket but keeps the API client for a quick resume. */
    fun pause() {
        faye?.stop()
    }

    fun stop() {
        faye?.stop()
        faye = null
        api?.close()
        api = null
        startedForToken = null
    }
}
