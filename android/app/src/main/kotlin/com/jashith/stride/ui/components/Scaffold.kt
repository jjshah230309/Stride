package com.jashith.stride.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType

/** A scrolling page on a tab: status bar space on top, 20 dp gutters, 18 dp between blocks. */
@Composable
fun TabPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrideColors.PageBackground)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/**
 * A pushed screen with a back button. When [scroll] is false the content gets the whole body and manages
 * its own scrolling, which the map screens need.
 */
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    gutters: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrideColors.PageBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 40.dp)
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = StrideType.RowTitle.copy(fontSize = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke(this)
        }
        val body = Modifier.weight(1f).fillMaxWidth()
        if (scroll) {
            Column(
                body.verticalScroll(rememberScrollState()).then(if (gutters) Modifier.padding(horizontal = 20.dp) else Modifier).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        } else {
            Column(body.then(if (gutters) Modifier.padding(horizontal = 20.dp) else Modifier), content = content)
        }
    }
}


/** Full-bleed container that sits on the page background; used for the record screen. */
@Composable
fun FullScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(StrideColors.PageBackground)) { content() }
}
