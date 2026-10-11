package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun DetailSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun ResponsiveDetailColumns(
    primary: @Composable () -> Unit,
    compact: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    compactFirst: Boolean = false,
    hasCompact: Boolean = true,
    minimumPrimaryWidth: Dp = 480.dp,
    compactWidth: Dp = 320.dp,
) {
    val fontScale = LocalDensity.current.fontScale
    val currentPrimary by rememberUpdatedState(primary)
    val currentCompact by rememberUpdatedState(compact)
    val primaryContent = remember { movableContentOf { currentPrimary() } }
    val compactContent = remember { movableContentOf { currentCompact() } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sidebarWidth = compactWidth * fontScale
        if (!hasCompact) {
            primaryContent()
        } else if (maxWidth >= minimumPrimaryWidth * fontScale + sidebarWidth + 16.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (compactFirst) Box(Modifier.width(sidebarWidth)) { compactContent() }
                Box(Modifier.weight(1f)) { primaryContent() }
                if (!compactFirst) Box(Modifier.width(sidebarWidth)) { compactContent() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (compactFirst) compactContent()
                primaryContent()
                if (!compactFirst) compactContent()
            }
        }
    }
}

@Composable
internal fun AdaptiveCommunityPane(
    controls: @Composable () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 960.dp * fontScale) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(280.dp * fontScale).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    controls()
                }
                Box(Modifier.weight(1f)) { content(false) }
            }
        } else {
            content(true)
        }
    }
}
