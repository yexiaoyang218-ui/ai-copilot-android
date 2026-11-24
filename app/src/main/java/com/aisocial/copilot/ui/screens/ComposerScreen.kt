package com.aisocial.copilot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import com.aisocial.copilot.data.prefs.AppPreferences
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ProviderConfig
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.ScreenScaffold
import com.aisocial.copilot.ui.session.SessionState

@Composable
fun ComposerScreen(
    state: SessionState,
    personas: List<Persona>,
    activePersona: Persona,
    prefs: AppPreferences,
    provider: ProviderConfig?,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onIntentChange: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onPickImage: () -> Unit,
    onCapture: () -> Unit,
    onSelectPersona: (Long) -> Unit,
    onSelectProvider: (String) -> Unit,
    onManagePersonas: () -> Unit,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
    onGenerate: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showPersonaPicker by remember { mutableStateOf(false) }
    var showProviderPicker by remember { mutableStateOf(false) }

    val providerLabel = provider?.displayName ?: "未配置模型"
    val modelLabel = provider?.let { config ->
        if (state.images.isNotEmpty() && config.supportsVision) {
            config.visionModel.ifBlank { config.model }
        } else {
            config.model
        }
    }?.ifBlank { "未填写模型 ID" } ?: "—"
    val hasKey = provider != null && prefs.providersWithKey.contains(provider.id)

    ScreenScaffold(
        title = "AI 分析",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            if (state.chatText.isNotBlank() || state.images.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("清空") }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "本次使用：$providerLabel · $modelLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (prefs.confirmBeforeSend) showConfirm = true else onGenerate()
                        },
                        enabled = state.canGenerate && !state.ocrRunning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        if (state.generating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在生成…")
                        } else {
                            Text("生成三条回复", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
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
            if (!hasKey) {
                AppCard(modifier = Modifier.padding(bottom = 12.dp), onClick = onOpenSettings) {
                    Text(
                        text = "还没有可用的 API Key",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "点这里去「设置 → 模型服务」里启用一个模型并填写 Key。Key 会加密保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            sectionLabel("聊天内容")
            AppCard {
                OutlinedTextField(
                    value = state.chatText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("把聊天记录粘贴进来，或者截一张图自动识别…") },
                    minLines = 5,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )

                if (state.images.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(items = state.images, key = { it.id }) { image ->
                            Box(modifier = Modifier.size(78.dp)) {
                                Image(
                                    bitmap = image.bitmap.asImageBitmap(),
                                    contentDescription = "已附加的截图",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(20.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                        .clickable { onRemoveImage(image.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("×", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                if (state.ocrRunning) {
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在本地识别文字…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                state.ocrNotice?.let { notice ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onCapture) { Text("框选屏幕") }
                    TextButton(onClick = onPickImage) { Text("选择图片") }
                }
            }

            Spacer(Modifier.height(16.dp))
            sectionLabel("我想表达什么（可选）")
            AppCard {
                OutlinedTextField(
                    value = state.intent,
                    onValueChange = onIntentChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：想约她周末看电影，但不想显得太急") },
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "写一句你的真实想法、希望对方理解什么、或者想避免什么，回复会更像你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            sectionLabel("人设与模型")
            AppCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPersonaPicker = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("人设", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(activePersona.name, style = MaterialTheme.typography.titleSmall)
                    }
                    Text("更换", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProviderPicker = true }
                        .padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$providerLabel · $modelLabel", style = MaterialTheme.typography.titleSmall)
                    }
                    Text("更换", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("发送前确认") },
            text = {
                Column {
                    Text(
                        text = "以下内容会发送给 $providerLabel（$modelLabel）：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = state.chatText.trim().ifBlank { "（没有文字，仅发送图片）" }.take(220),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.intent.isNotBlank()) {
                                Text(
                                    text = "意图：${state.intent.trim().take(80)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            if (state.images.isNotEmpty()) {
                                Text(
                                    text = "附带 ${state.images.size} 张你预览过的截图",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = "不会发送其他应用的数据，也不会自动发送消息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onGenerate()
                }) { Text("确认发送") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showPersonaPicker) {
        AlertDialog(
            onDismissRequest = { showPersonaPicker = false },
            title = { Text("选择人设") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    personas.forEach { persona ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPersona(persona.id)
                                    showPersonaPicker = false
                                }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(persona.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    persona.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (persona.id == activePersona.id) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPersonaPicker = false
                    onManagePersonas()
                }) { Text("管理人设") }
            },
            dismissButton = {
                TextButton(onClick = { showPersonaPicker = false }) { Text("关闭") }
            },
        )
    }

    if (showProviderPicker) {
        AlertDialog(
            onDismissRequest = { showProviderPicker = false },
            title = { Text("选择模型服务") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    prefs.ai.providers.filter { it.enabled }.forEach { config ->
                        val ready = prefs.providersWithKey.contains(config.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectProvider(config.id)
                                    showProviderPicker = false
                                }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(config.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (ready) config.model else "未配置 API Key",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ready) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            if (config.id == prefs.ai.activeProviderId) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    if (prefs.ai.providers.none { it.enabled }) {
                        Text(
                            text = "没有启用的模型服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showProviderPicker = false
                    onOpenSettings()
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showProviderPicker = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun sectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Suppress("unused")
private val DividerStroke = BorderStroke(1.dp, Color.Transparent)
