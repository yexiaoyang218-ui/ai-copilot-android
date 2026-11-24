package com.aisocial.copilot.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aisocial.copilot.data.prefs.AppPreferences
import com.aisocial.copilot.data.repo.HistoryRecord
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.ScreenScaffold
import com.aisocial.copilot.ui.components.SectionHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    prefs: AppPreferences,
    personas: List<Persona>,
    history: List<HistoryRecord>,
    activePersona: Persona,
    activeBookTitle: String,
    onCapture: () -> Unit,
    onPickImage: () -> Unit,
    onTextInput: () -> Unit,
    onManagePersonas: () -> Unit,
    onOpenPersonaBooks: () -> Unit,
    onSelectPersona: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: (HistoryRecord) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    ScreenScaffold(
        title = "AI 沟通助手",
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(text = "把话说到点子上", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "截图或粘贴聊天内容，AI 会给出三条可以直接发出去的回复。不会替你发送。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(20.dp))

            QuickActionCard(
                badge = "截",
                title = "框选屏幕",
                subtitle = "截取聊天窗口，只处理你选中的区域",
                onClick = onCapture,
            )
            Spacer(Modifier.height(10.dp))
            QuickActionCard(
                badge = "图",
                title = "上传图片",
                subtitle = "从相册选一张聊天截图，本地识别文字",
                onClick = onPickImage,
            )
            Spacer(Modifier.height(10.dp))
            QuickActionCard(
                badge = "写",
                title = "手动输入",
                subtitle = "粘贴聊天记录，或者简单描述一下情况",
                onClick = onTextInput,
            )

            Spacer(Modifier.height(18.dp))

            SectionHeader(
                title = "当前人设 · ${activePersona.name}",
                actionLabel = "管理",
                onAction = onManagePersonas,
            )

            if (personas.isEmpty()) {
                Text(
                    text = "还没有人设，点「管理」新建一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items = personas, key = { it.id }) { persona ->
                        PersonaTile(
                            persona = persona,
                            selected = persona.id == activePersona.id,
                            onClick = { onSelectPersona(persona.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            PersonaBookEntry(title = activeBookTitle, onClick = onOpenPersonaBooks)

            if (prefs.historyEnabled && history.isNotEmpty()) {                Spacer(Modifier.height(18.dp))
                SectionHeader(title = "最近生成")
                history.take(5).forEach { record ->
                    HistoryRow(record = record, onClick = { onOpenHistory(record) })
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "隐私优先：截图裁剪、文字识别和设置都在本机完成。只有你点下「生成」并确认后，" +
                    "必要的内容才会发送给你选择的模型服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun QuickActionCard(
    badge: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun PersonaBookEntry(title: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (title.isNotBlank()) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "人设书",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (title.isBlank()) "写一本你自己的，让回复更像你" else title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = if (title.isBlank()) "去写" else "编辑",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PersonaTile(persona: Persona, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = persona.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = persona.tone.ifBlank { "自然口语" },
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HistoryRow(record: HistoryRecord, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.personaName.ifBlank { "未命名人设" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatTimestamp(record.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = record.inputText.replace('\n', ' ').take(80),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
