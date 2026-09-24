package com.aniblaze.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniblaze.database.settings.SettingsDataStore
import com.aniblaze.ui.components.BrandGradient
import com.aniblaze.ui.components.glass
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.AccentPurple
import com.aniblaze.ui.theme.OledBlack
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val store: SettingsDataStore,
) : ViewModel() {

    /** Enabled silently; the user never picks providers. */
    private val defaultSources = setOf("Anixart", "AniLibria")

    fun finish() {
        viewModelScope.launch {
            store.setEnabledSources(defaultSources)
            store.setOnboarded(true)
        }
    }
}

private data class Feature(val icon: ImageVector, val title: String, val subtitle: String)

private val features = listOf(
    Feature(Icons.Filled.Movie, "Огромный каталог", "Тысячи тайтлов с поиском и фильтрами по жанрам"),
    Feature(Icons.Filled.Translate, "Озвучки на выбор", "Несколько дубляжей и субтитры для каждого тайтла"),
    Feature(Icons.Filled.NotificationsActive, "Новые серии", "Уведомления, когда выходит свежий эпизод из избранного"),
    Feature(Icons.Filled.Bolt, "Плавный плеер", "Картинка в картинке, фон и высокая частота кадров"),
)

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    Box(Modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Text(
                "AniBlaze",
                style = TextStyle(
                    brush = BrandGradient,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp,
                ),
            )
            Text(
                "Аниме без границ",
                color = TextSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(Modifier.height(44.dp))
            features.forEach { feature ->
                FeatureRow(feature)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(36.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BrandGradient)
                    .clickable { viewModel.finish(); onDone() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Начать", color = OledBlack, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(
                "Контент из открытых источников",
                color = TextSecondary.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun FeatureRow(feature: Feature) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(18.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AccentPurple.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(feature.icon, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(feature.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(feature.subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
