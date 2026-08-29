package com.outofthewhale.groupme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TopicsViewModel internal constructor(
    private val groupId: String,
) : LightViewModel<Unit>() {

    internal val topics = MutableStateFlow<List<GroupMeTopic>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (topics.value.isEmpty()) refresh()
    }

    fun refresh() {
        val api = GroupMeRuntime.api ?: return
        viewModelScope.launch {
            busy.value = true
            api.getTopics(groupId)
                .onSuccess {
                    error.value = null
                    topics.value = it.sortedByDescending { topic ->
                        topic.messages?.lastMessageCreatedAt ?: topic.updatedAt
                    }
                }
                .onFailure { error.value = "Could not load topics. Tap refresh to retry." }
            busy.value = false
        }
    }
}

/** Lists the topics inside a group. Each one opens in the ordinary [ChatScreen]. */
class TopicsScreen(
    sealedActivity: SealedLightActivity,
    private val groupId: String,
    private val groupName: String,
) : LightScreen<Unit, TopicsViewModel>(sealedActivity) {

    override val viewModelClass: Class<TopicsViewModel>
        get() = TopicsViewModel::class.java

    override fun createViewModel(): TopicsViewModel = TopicsViewModel(groupId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val topics by viewModel.topics.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val error by viewModel.error.collectAsState()

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
                    center = LightTopBarCenter.Text("TOPICS"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.REFRESH,
                        onClick = { viewModel.refresh() },
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightText(
                    text = groupName,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 4.dp),
                )

                error?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 4.dp),
                    )
                }

                if (topics.isEmpty() && !busy) {
                    LightText(
                        text = "No topics in this group.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4f.gridUnitsAsDp()),
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(topics, key = { it.id }) { topic ->
                        TopicRow(topic)
                    }
                }
            }
        }
    }

    @Composable
    private fun TopicRow(topic: GroupMeTopic) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable {
                    navigateTo(
                        screenFactory = { ChatScreen(it, topic.id.toString(), topic.topic) },
                    )
                }
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 12.dp)
        ) {
            LightText(
                text = topic.topic.ifBlank { "Untitled topic" },
                variant = LightTextVariant.Copy,
                maxLines = 1,
            )
            val preview = topic.messages?.preview
            val who = preview?.nickname?.takeIf { it.isNotBlank() }
            val body = when {
                preview == null -> null
                !preview.text.isNullOrBlank() -> preview.text
                preview.imageUrl != null || preview.attachments.any { it.type == "image" } -> "[image]"
                else -> null
            }
            val previewText = when {
                body == null -> null
                who == null -> body
                else -> "$who: $body"
            }
            previewText?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
