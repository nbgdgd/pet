package com.aniblaze.app.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.Surface1
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

/**
 * Лист «Ещё»: экраны, которым не хватило места в нижней панели.
 *
 * Нижний лист, а не отдельный экран со списком: он открывается поверх текущего,
 * закрывается смахиванием и не добавляет шага в историю переходов — с телефона это
 * одно движение большого пальца, а не «зайти и потом вернуться».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    onDismiss: () -> Unit,
    onSelect: (MoreDestination) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Surface1,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Ещё",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            MoreDestination.entries.forEach { dest ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(dest) }
                        // Высота строки с запасом: 56 dp — минимальная цель для
                        // пальца, к которой Material сводит списки на телефоне.
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        dest.icon,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(dest.label, color = TextPrimary, fontSize = 16.sp)
                        Text(dest.hint, color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
