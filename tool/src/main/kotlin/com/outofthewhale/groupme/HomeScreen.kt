package com.outofthewhale.groupme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.thelightphone.sdk.InitialScreen
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object LoggedOut : HomeUiState
    data class Groups(val groups: List<GroupMeGroup>) : HomeUiState
}

class HomeScreenViewModel internal constructor(
    private val sessionStore: SessionStore,
) : LightViewModel<Unit>() {

    internal val uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private var credentials: GroupMeCredentials? = null
    private var realtimeJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            if (credentials == null) {
                sessionStore.migrateIfNeeded()
                credentials = sessionStore.credentials.first()
            }
            val creds = credentials
            if (creds == null) {
                uiState.value = HomeUiState.LoggedOut
            } else {
                GroupMeRuntime.start(creds)
                GroupMeRuntime.resume()
                observeRealtime()
                refreshGroups()
            }
        }
    }

    override fun onAppPause() {
        super.onAppPause()
        GroupMeRuntime.pause()
    }

    fun submitToken(raw: String?) {
        if (raw == null) return
        val token = extractToken(raw)
        if (token == null) {
            error.value = "That does not look like a GroupMe token."
            return
        }
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val probe = GroupMeApi(token)
            probe.getMe()
                .onSuccess { me ->
                    val creds = GroupMeCredentials(token, me.id, me.name)
                    sessionStore.save(creds)
                    credentials = creds
                    GroupMeRuntime.start(creds)
                    observeRealtime()
                    refreshGroups()
                }
                .onFailure {
                    error.value = "Login failed. Check the token and try again."
                    uiState.value = HomeUiState.LoggedOut
                }
            probe.close()
            busy.value = false
        }
    }

    fun logOut() {
        viewModelScope.launch {
            sessionStore.clear()
            credentials = null
            realtimeJob?.cancel()
            GroupMeRuntime.stop()
            uiState.value = HomeUiState.LoggedOut
        }
    }

    fun refreshGroups() {
        val api = GroupMeRuntime.api ?: return
        viewModelScope.launch {
            busy.value = true
            api.getGroups()
                .onSuccess { groups ->
                    error.value = null
                    uiState.value = HomeUiState.Groups(groups)
                }
                .onFailure { e ->
                    if (e is GroupMeApiException && e.isUnauthorized) {
                        logOut()
                        error.value = "Session expired. Please log in again."
                    } else {
                        error.value = "Could not load groups. Tap refresh to retry."
                    }
                }
            busy.value = false
        }
    }

    private fun observeRealtime() {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            GroupMeRuntime.incomingMessages.collect { message ->
                val current = uiState.value as? HomeUiState.Groups ?: return@collect
                val groupId = message.groupId ?: return@collect
                val updated = current.groups.map { group ->
                    if (group.id != groupId) group else group.copy(
                        messages = (group.messages ?: GroupMessagesInfo()).copy(
                            lastMessageCreatedAt = message.createdAt,
                            preview = MessagePreview(
                                nickname = message.name,
                                text = message.text,
                            ),
                        )
                    )
                }.sortedByDescending { it.messages?.lastMessageCreatedAt ?: 0 }
                uiState.value = HomeUiState.Groups(updated)
            }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(SessionStore(lightContext.dataStore))
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val error by viewModel.error.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                when (val current = state) {
                    is HomeUiState.Loading -> LoadingContent()
                    is HomeUiState.LoggedOut -> LoginContent(busy, error)
                    is HomeUiState.Groups -> GroupsContent(current.groups, busy, error)
                }
            }
        }
    }

    @Composable
    private fun LoadingContent() {
        LightText(
            text = "Loading...",
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun LoginContent(busy: Boolean, error: String?) {
        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            LightText(
                text = "GroupMe",
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(top = 4f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
            )
            LightText(
                text = "Sign in with a GroupMe access token. Scan the QR code from " +
                    "the login page on your computer, or type the token in manually.",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(bottom = 2f.gridUnitsAsDp()),
            )

            error?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
            }

            if (busy) {
                LightText(
                    text = "Signing in...",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                )
            } else {
                LightText(
                    text = "SCAN TOKEN QR CODE",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            navigateTo(
                                screenFactory = { QrScanScreen(it) },
                                resultCallback = { viewModel.submitToken(it) },
                            )
                        }
                        .padding(vertical = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = "ENTER TOKEN MANUALLY",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            navigateTo(
                                screenFactory = {
                                    TextEntryScreen(it, title = "Access Token", submitLabel = "LOG IN")
                                },
                                resultCallback = { viewModel.submitToken(it) },
                            )
                        }
                        .padding(vertical = 1f.gridUnitsAsDp()),
                )
                Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                LightText(
                    text = "Get a token at dev.groupme.com, or open the login page " +
                        "in the tool/oauth folder of this project.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }

    @Composable
    private fun GroupsContent(groups: List<GroupMeGroup>, busy: Boolean, error: String?) {
        LightTopBar(
            leftButton = null,
            center = LightTopBarCenter.Text("GROUPME"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.REFRESH,
                onClick = { viewModel.refreshGroups() },
            ),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
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

        if (groups.isEmpty() && !busy) {
            LightText(
                text = "No groups found.",
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4f.gridUnitsAsDp()),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(groups, key = { it.id }) { group ->
                GroupRow(group)
            }
            item {
                LightText(
                    text = "LOG OUT",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.logOut() }
                        .padding(vertical = 2f.gridUnitsAsDp()),
                )
            }
        }
    }

    @Composable
    private fun GroupRow(group: GroupMeGroup) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable {
                    navigateTo(
                        screenFactory = { ChatScreen(it, group.id, group.name, group.childrenCount) },
                    )
                }
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 12.dp)
        ) {
            LightText(
                text = group.name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
            )
            val preview = group.messages?.preview
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
