package me.bmax.apatch.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A pinned (non-scrolling) Miuix scroll behavior, kept for API compatibility
 * with the previous Material3 implementation.
 */
@Composable
fun pinnedScrollBehavior(
    canScroll: () -> Boolean = { true },
): ScrollBehavior {
    return MiuixScrollBehavior(canScroll = canScroll)
}

@Composable
fun SearchAppBar(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onBackClick: (() -> Unit)? = null,
    dropdownContent: @Composable (() -> Unit)? = null,
    navigationContent: @Composable (() -> Unit)? = null,
    searchBarPlaceHolderText: String
) {
    var expanded by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager: FocusManager = LocalFocusManager.current

    fun collapseSearchBar() {
        expanded = false
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    BackHandler(enabled = expanded) {
        collapseSearchBar()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBackClick != null) {
            IconButton(
                onClick = {
                    if (expanded) {
                        if (searchText.isNotEmpty()) {
                            onSearchTextChange("")
                        } else {
                            collapseSearchBar()
                        }
                    } else {
                        onBackClick.invoke()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    tint = MiuixTheme.colorScheme.onSurface,
                    contentDescription = stringResource(R.string.back)
                )
            }
        } else {
            navigationContent?.invoke()
        }

        SearchBar(
            inputField = {
                InputField(
                    query = searchText,
                    onQueryChange = onSearchTextChange,
                    onSearch = { collapseSearchBar() },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    label = searchBarPlaceHolderText,
                    trailingIcon = dropdownContent
                )
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            // No search result panel — filtering happens in the backing list.
        }
    }
}
