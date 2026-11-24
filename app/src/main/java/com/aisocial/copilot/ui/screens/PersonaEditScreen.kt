package com.aisocial.copilot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ReplyLength
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.Pill
import com.aisocial.copilot.ui.components.ScreenScaffold
import kotlin.math.roundToInt

@Composable
fun PersonaEditScreen(
    personaId: Long,
    onBack: () -> Unit,
    load: suspend (Long) -> Persona?,
    onSave: (Persona) -> Unit,
    onDelete: (Persona) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var draft by remember(personaId) { mutableStateOf<Persona?>(null) }
    var loaded by remember(personaId) { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(personaId) {
        val existing = if (personaId > 0L) load(personaId) else null
        draft = existing ?: Persona(name = "")
        loaded = true
    }

    val current = draft

    ScreenScaffold(
        title = if (personaId > 0L) "编辑人设" else "新建人设",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(
                onClick = { current?.let(onSave) },
                enabled = !current?.name.isNullOrBlank(),
            ) { Text("保存") }
        },
    ) { padding ->
        if (!loaded || current == null) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@ScreenScaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            AppCard {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = { draft = (draft ?: current).copy(name = it) },
                    label = { Text("人设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = current.description,
                    onValueChange = { draft = (draft ?: current).copy(description = it) },
                    label = { Text("性格描述") },
                    placeholder = { Text("例如：随和真诚，不刻意表现，说话有点直") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = current.tone,
                    onValueChange = { draft = (draft ?: current).copy(tone = it) },
                    label = { Text("语气") },
                    placeholder = { Text("例如：轻松自然 / 温柔耐心 / 专业简洁") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))

            AppCard {
                Text("回复长度", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReplyLength.entries.forEach { length ->
                        Pill(
                            text = length.label,
                            selected = current.length == length,
                            onClick = { draft = (draft ?: current).copy(length = length) },
                        )
                    }
                }
                Text(
                    text = current.length.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            AppCard {
                LevelSlider(
                    label = "情绪表达程度",
                    hint = "1 = 很克制，5 = 情绪外放",
                    value = current.emotion,
                    onChange = { draft = (draft ?: current).copy(emotion = it) },
                )
                LevelSlider(
                    label = "主动程度",
                    hint = "1 = 被动回应，5 = 主动推进",
                    value = current.initiative,
                    onChange = { draft = (draft ?: current).copy(initiative = it) },
                )
                LevelSlider(
                    label = "幽默程度",
                    hint = "1 = 一本正经，5 = 经常开玩笑",
                    value = current.humor,
                    onChange = { draft = (draft ?: current).copy(humor = it) },
                )
            }

            Spacer(Modifier.height(14.dp))

            AppCard {
                OutlinedTextField(
                    value = current.catchphrases,
                    onValueChange = { draft = (draft ?: current).copy(catchphrases = it) },
                    label = { Text("常用词句") },
                    placeholder = { Text("用分号隔开，例如：挺好的；行啊；是有点") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = current.forbidden,
                    onValueChange = { draft = (draft ?: current).copy(forbidden = it) },
                    label = { Text("禁用表达") },
                    placeholder = { Text("例如：书面语；感叹号堆叠；阴阳怪气") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = current.customInstruction,
                    onValueChange = { draft = (draft ?: current).copy(customInstruction = it) },
                    label = { Text("自定义指令") },
                    placeholder = { Text("例如：先回应情绪，再给建议；每条不超过两句") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { onSave(current) },
                enabled = current.name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) { Text("保存人设") }

            if (personaId > 0L) {
                TextButton(
                    onClick = { showDelete = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text("删除这个人设", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showDelete && current != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除人设") },
            text = { Text("确定删除「${current.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete(current)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LevelSlider(
    label: String,
    hint: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$value / 5",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
