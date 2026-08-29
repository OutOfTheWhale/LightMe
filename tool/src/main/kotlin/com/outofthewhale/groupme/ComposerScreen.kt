package com.outofthewhale.groupme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
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
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

/**
 * Message composer laid out to match the LightOS messaging screen: send lives in the
 * top bar, the draft sits directly above the keys, and a close control sits beneath
 * the keyboard. Returns the composed text, or null if the screen was dismissed.
 */
internal class ComposerScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String = "",
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val textState = rememberTextFieldState(initialValue)
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val callback = remember(textState) { ComposerKeyboardCallback(textState) }
        val keyboardViewModel: Lp3KeyboardViewModel<*> =
            viewModel<EnQwertyLp3KeyboardViewModel<Unit>>(
                key = "ComposerScreen-$title",
                factory = composerKeyboardFactory(callback, keyboardOptionsFlow),
            )

        val draft = textState.text.toString()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                    ),
                    center = LightTopBarCenter.Text(title),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEND,
                        onClick = { goBack(draft.takeIf { it.isNotBlank() }) },
                    ),
                )

                // The draft grows upward from just above the keys, as it does in LightOS.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    LightText(
                        text = draft + CURSOR,
                        variant = LightTextVariant.Heading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 1f.gridUnitsAsDp()),
                    )
                }

                LightEmbeddedLp3Keyboard(
                    viewModel = keyboardViewModel,
                    bottomBar = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 1f.gridUnitsAsDp(),
                                    vertical = 0.5f.gridUnitsAsDp(),
                                ),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            LightIcon(
                                icon = LightIcons.CLOSE,
                                modifier = Modifier.lightClickable { goBack(null) },
                            )
                        }
                    },
                )
            }
        }
    }
}

private const val CURSOR = "|"

private fun composerKeyboardFactory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return EnQwertyLp3KeyboardViewModel<Unit>(
            callback,
            keyboardOptionsFlow = keyboardOptionsFlow,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        ) as T
    }
}

/**
 * Applies Light Phone keyboard events to a [TextFieldState]. Mirrors the SDK's own
 * internal callback, which tools cannot reach.
 */
private class ComposerKeyboardCallback(
    private val state: TextFieldState,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBeforeCursor(surrogateAwareDeleteCount(before))
            }
            SpecialKey.Return -> insertAtCursor("\n")
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            deleteBeforeCursor(deleteWordCount(before))
        }
    }

    override fun onKeyRepeated(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onSubmitWord(word: CharSequence) = insertAtCursor(word.toString())

    private fun insertCodePoint(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            val end = selection.max
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

private fun surrogateAwareDeleteCount(value: CharSequence): Int {
    if (value.isEmpty()) return 0
    return if (Character.isLowSurrogate(value[value.length - 1])) 2 else 1
}

private fun deleteWordCount(value: CharSequence): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}
