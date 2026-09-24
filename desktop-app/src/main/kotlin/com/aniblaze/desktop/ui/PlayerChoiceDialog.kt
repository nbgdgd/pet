package com.aniblaze.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dark in-scene picker shared by source, voice, episode and HUD menus. Keeping
 * it in the Compose scene avoids a separate native popup window and its white
 * platform fallback frame while the menu is being created. */
internal data class PlayerChoice<T>(
    val value: T,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** Dim right-aligned annotation (dub share, air date…). Null = nothing shown. */
    val trailing: String? = null,
)

@Composable
internal fun <T> PlayerChoicePanel(
    title: String,
    choices: List<PlayerChoice<T>>,
    onClose: () -> Unit,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // Callers pass the height their layout context can actually afford (the HUD
    // computes it from BoxWithConstraints); the list scrolls when it overflows.
    listMaxHeight: Dp = 240.dp,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Surface3,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(max = listMaxHeight),
            ) {
                items(choices) { choice ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (choice.selected) Surface4
                                else Color.Transparent,
                            )
                            .clickable(enabled = choice.enabled) { onSelect(choice.value) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (choice.selected) "●  ${choice.label}" else choice.label,
                            color = if (choice.enabled) TextPrimary else TextSecondary.copy(alpha = 0.38f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        choice.trailing?.let { note ->
                            Text(
                                note,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
