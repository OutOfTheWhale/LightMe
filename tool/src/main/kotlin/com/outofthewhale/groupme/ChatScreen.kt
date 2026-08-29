package com.outofthewhale.groupme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

class ChatViewModel internal constructor(
    private val groupId: String,
) : LightViewModel<Unit>() {

    /** Newest message first (the list is rendered with reverseLayout). */
    internal val messages = MutableStateFlow<List<GroupMeMessage>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val canLoadOlder = MutableStateFlow(false)

    private val seenIds = HashSet<String>()
    private var realtimeJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (messages.value.isEmpty()) refresh()
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            GroupMeRuntime.incomingMessages
                .filter { it.groupId == groupId }
                .collect { message -> prepend(listOf(message)) }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        realtimeJob?.cancel()
        realtimeJob = null
    }

    fun refresh() {
        val api = GroupMeRuntime.api ?: return
        viewModelScope.launch {
            busy.value = true
            api.getMessages(groupId, limit = PAGE_SIZE)
                .onSuccess { page ->
                    error.value = null
                    seenIds.clear()
                    seenIds.addAll(page.map { it.id })
                    messages.value = page
                    canLoadOlder.value = page.size >= PAGE_SIZE
                }
                .onFailure { error.value = "Could not load messages." }
            busy.value = false
        }
    }

    fun loadOlder() {
        val api = GroupMeRuntime.api ?: return
        val oldest = messages.value.lastOrNull()?.id ?: return
        viewModelScope.launch {
            busy.value = true
            api.getMessages(groupId, beforeId = oldest, limit = PAGE_SIZE)
                .onSuccess { page ->
                    val fresh = page.filter { seenIds.add(it.id) }
                    messages.value = messages.value + fresh
                    canLoadOlder.value = page.size >= PAGE_SIZE
                }
                .onFailure { error.value = "Could not load older messages." }
            busy.value = false
        }
    }

    fun send(text: String?) {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return
        val api = GroupMeRuntime.api ?: return
        viewModelScope.launch {
            api.sendMessage(groupId, body)
                .onSuccess { sent ->
                    error.value = null
                    prepend(listOf(sent))
                }
                .onFailure { error.value = "Could not send message." }
        }
    }

    private fun prepend(newMessages: List<GroupMeMessage>) {
        val fresh = newMessages.filter { seenIds.add(it.id) }
        if (fresh.isEmpty()) return
        messages.value = (fresh.sortedByDescending { it.createdAt } + messages.value)
    }
}

class ChatScreen(
    sealedActivity: SealedLightActivity,
    private val groupId: String,
    private val groupName: String,
) : LightScreen<Unit, ChatViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChatViewModel>
        get() = ChatViewModel::class.java

    override fun createViewModel(): ChatViewModel {
        return ChatViewModel(groupId)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by viewModel.messages.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val error by viewModel.error.collectAsState()
        val canLoadOlder by viewModel.canLoadOlder.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(groupName.uppercase()),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.REFRESH,
                        onClick = { viewModel.refresh() },
                    ),
                )

                error?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    reverseLayout = true,
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageRow(message)
                    }
                    if (canLoadOlder) {
                        item {
                            LightText(
                                text = if (busy) "LOADING..." else "LOAD EARLIER MESSAGES",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { viewModel.loadOlder() }
                                    .padding(vertical = 1f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                ComposerBar()
            }
        }
    }

    @Composable
    private fun ComposerBar() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable {
                    navigateTo(
                        screenFactory = {
                            TextEntryScreen(it, title = groupName, submitLabel = "SEND")
                        },
                        resultCallback = { viewModel.send(it) },
                    )
                }
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 12.dp)
        ) {
            LightIcon(
                icon = LightIcons.COMPOSE_MESSAGE,
                modifier = Modifier.padding(end = 12.dp),
            )
            LightText(
                text = "Write a message...",
                variant = LightTextVariant.Copy,
                lighten = true,
            )
        }
    }

    @Composable
    private fun MessageRow(message: GroupMeMessage) {
        if (message.system) {
            LightText(
                text = message.text.orEmpty(),
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 6.dp),
            )
            return
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                LightText(
                    text = message.name ?: "Unknown",
                    variant = LightTextVariant.Detail,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp),
                )
                LightText(
                    text = formatTimestamp(message.createdAt),
                    variant = LightTextVariant.Fine,
                    lighten = true,
                )
            }

            message.text?.takeIf { it.isNotBlank() }?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            message.attachments
                .filter { it.type == "image" && it.url != null }
                .forEach { attachment ->
                    val url = attachment.url ?: return@forEach
                    RemoteImage(
                        url = thumbnailUrl(url),
                        contentDescription = "Image attachment",
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .height(160.dp)
                            .lightClickable {
                                navigateTo(screenFactory = { ImageViewerScreen(it, url) })
                            },
                    )
                }
        }
    }
}

private fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val format = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return format.format(Date(epochSeconds * 1000))
}
