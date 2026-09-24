package com.aniblaze.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Wide preview, separate reading surface: long synopses never squeeze the episode list. */
@Composable
internal fun TitleDescriptionCard(title: String, description: String, modifier: Modifier = Modifier) {
    var reading by remember(title) { mutableStateOf(false) }
    DescriptionPreview(description, { reading = true }, modifier)
    if (reading) Dialog(onDismissRequest = { reading = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            DescriptionReader(title, description, { reading = false },
                Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(.88f))
        }
    }
}

@Composable
internal fun DescriptionPreview(description: String, onRead: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = Shapes.card, color = Surface2,
        border = BorderStroke(1.dp, TextPrimary.copy(alpha = .07f))) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Описание", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(description.trim(), color = TextPrimary.copy(alpha = .9f), fontSize = 16.sp, lineHeight = 26.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            TextButton(onClick = onRead, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text("Читать полностью", color = AccentOrange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun DescriptionReader(title: String, description: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = Shapes.card, color = Surface2,
        border = BorderStroke(1.dp, TextPrimary.copy(alpha = .09f))) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Описание", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(title, color = TextSecondary, fontSize = 14.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть описание", tint = TextSecondary) }
            }
            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = TextPrimary.copy(alpha = .08f))
            val scroll = rememberScrollState()
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().browserAutoScroll(scroll).verticalScroll(scroll)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    remember(description) { descriptionParagraphs(description) }.forEach { paragraph ->
                        Text(paragraph, color = TextPrimary.copy(alpha = .94f), fontSize = 18.sp, lineHeight = 30.sp)
                    }
                }
            }
        }
    }
}

/** Keep the source's paragraph boundaries and wording; do not rewrite the synopsis. */
internal fun descriptionParagraphs(description: String): List<String> =
    description.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\\n+"))
        .map(String::trim).filter(String::isNotEmpty)
