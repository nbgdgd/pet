package com.aniblaze.app.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.TextSecondary

@Composable
fun AppBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
    onMore: () -> Unit,
) {
    NavigationBar(containerColor = OledBlack) {
        TopLevelDestination.entries.forEach { dest ->
            val selected = currentRoute == dest.route
            val iconColor by animateColorAsState(
                targetValue = if (selected) AccentOrange else TextSecondary,
                animationSpec = tween(250),
                label = "iconColor",
            )
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(dest) },
                icon = {
                    Icon(
                        dest.icon,
                        contentDescription = dest.label,
                        modifier = Modifier
                            .size(24.dp)
                            .scale(if (selected) 1.1f else 1f),
                        tint = iconColor,
                    )
                },
                label = {
                    Text(
                        dest.label,
                        color = iconColor,
                        fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentOrange,
                    selectedTextColor = AccentOrange,
                    indicatorColor = AccentOrange.copy(alpha = 0.14f),
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                ),
                interactionSource = remember { MutableInteractionSource() },
            )
        }
        // Пятый пункт — вход в остальные экраны. Подсвечивается, когда открыт любой
        // из них, иначе с расписания или статистики панель выглядела бы так, будто
        // ты нигде.
        val moreSelected = MoreDestination.entries.any { it.route == currentRoute }
        val moreColor by animateColorAsState(
            targetValue = if (moreSelected) AccentOrange else TextSecondary,
            animationSpec = tween(250),
            label = "moreColor",
        )
        NavigationBarItem(
            selected = moreSelected,
            onClick = onMore,
            icon = {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "Ещё",
                    modifier = Modifier.size(24.dp).scale(if (moreSelected) 1.1f else 1f),
                    tint = moreColor,
                )
            },
            label = { Text("Ещё", color = moreColor, fontSize = 10.sp, maxLines = 1, softWrap = false) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentOrange,
                selectedTextColor = AccentOrange,
                indicatorColor = AccentOrange.copy(alpha = 0.14f),
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary,
            ),
            interactionSource = remember { MutableInteractionSource() },
        )
    }
}
