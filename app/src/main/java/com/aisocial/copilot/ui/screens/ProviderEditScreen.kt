package com.aisocial.copilot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aisocial.copilot.data.prefs.AppPreferences
import com.aisocial.copilot.domain.model.ModelCatalog
import com.aisocial.copilot.domain.model.ProviderConfig
import com.aisocial.copilot.domain.model.ProviderKind
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.ScreenScaffold

private enum class ModelTarget { NONE, PRIMARY, VISION }

@Composable
fun ProviderEditScreen(
    providerId: String,
    prefs: AppPreferences,
    onBack: () -> Unit,
    loadApiKey: suspend (String) -> String,
    onSave: (ProviderConfig, String) -> Unit,
    onTest: (ProviderConfig, String) -> Unit,
    onFetchModels: (ProviderConfig, String) -> Unit,
    onClearModelFetch: () -> Unit,
    testRunning: Boolean,
    testMessage: String?,
    testOk: Boolean,
    modelsFetching: Boolean,
    modelOptions: List<String>,
    modelFetchMessage: String?,
    modelFetchOk: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    val original = remember(providerId, prefs.ai.providers) {
        prefs.ai.providers.firstOrNull { it.id == providerId }
    }

    var displayName by remember(providerId) { mutableStateOf(original?.displayName ?: "") }
    var baseUrl by remember(providerId) { mutableStateOf(original?.baseUrl ?: "") }
    var model by remember(providerId) { mutableStateOf(original?.model ?: "") }
    var visionModel by remember(providerId) { mutableStateOf(original?.visionModel ?: "") }
    var supportsVision by remember(providerId) { mutableStateOf(original?.supportsVision ?: false) }
    var enabled by remember(providerId) { mutableStateOf(original?.enabled ?: true) }
    var apiKey by remember(providerId) { mutableStateOf("") }
    var keyLoaded by remember(providerId) { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(ModelTarget.NONE) }

    LaunchedEffect(providerId) {
        apiKey = runCatching { loadApiKey(providerId) }.getOrDefault("")
        keyLoaded = true
    }

    if (original == null) {
        ScreenScaffold(title = "模型服务", onBack = onBack, snackbarHostState = snackbarHostState) { padding ->
            Column(Modifier.padding(padding).padding(20.dp)) {
                Text("找不到这个模型服务。")
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        return
    }

    fun buildConfig(): ProviderConfig = original.copy(
        displayName = displayName.trim().ifBlank { original.displayName },
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        visionModel = visionModel.trim(),
        supportsVision = supportsVision,
        enabled = enabled,
        fetchedModels = modelOptions.take(40),
    )

    val curated = ModelCatalog.curated(original.id)
    val choices = buildList {
        addAll(modelOptions)
        addAll(curated)
        if (model.isNotBlank()) add(model)
        if (visionModel.isNotBlank()) add(visionModel)
    }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    ScreenScaffold(
        title = original.displayName,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(onClick = { onSave(buildConfig(), apiKey.trim()) }) { Text("保存") }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "协议：${protocolLabel(original.kind)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "API Key 使用 Android Keystore 加密后保存在本机，不会写进日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            AppCard {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示")
                        }
                    },
                    placeholder = {
                        Text(if (keyLoaded) "粘贴你的 API Key" else "读取中…")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))

            AppCard {
                Text("对话模型", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "填好 API Key 后点「获取模型列表」，就能选到这个账号实际可用的模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    placeholder = { Text("例如 deepseek-chat") },
                    trailingIcon = {
                        TextButton(onClick = { pickTarget = ModelTarget.PRIMARY }) { Text("选择") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onFetchModels(buildConfig(), apiKey.trim()) },
                        enabled = !modelsFetching,
                    ) {
                        if (modelsFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("获取中…")
                        } else {
                            Text("获取模型列表")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${choices.size} 个可选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                modelFetchMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelFetchOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("支持图片输入", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "开启后，聊天截图会直接发给模型；关闭时只用本地 OCR 的文字。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = supportsVision, onCheckedChange = { supportsVision = it })
                }
                if (supportsVision) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = visionModel,
                        onValueChange = { visionModel = it },
                        label = { Text("视觉模型 ID（留空则用上面的模型）") },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = { pickTarget = ModelTarget.VISION }) { Text("选择") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用这个服务", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "停用后不会出现在模型选择列表里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedButton(
                onClick = { onTest(buildConfig(), apiKey.trim()) },
                enabled = !testRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (testRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("测试中…")
                } else {
                    Text("测试连接")
                }
            }

            testMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    color = if (testOk) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testOk) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { onSave(buildConfig(), apiKey.trim()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) { Text("保存配置") }

            TextButton(
                onClick = {
                    model = original.model
                    visionModel = original.visionModel
                    onClearModelFetch()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("恢复默认模型") }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (pickTarget != ModelTarget.NONE) {
        ModelPickerDialog(
            title = if (pickTarget == ModelTarget.PRIMARY) "选择对话模型" else "选择视觉模型",
            fetched = modelOptions,
            curated = curated,
            current = if (pickTarget == ModelTarget.PRIMARY) model else visionModel,
            fetching = modelsFetching,
            fetchMessage = modelFetchMessage,
            fetchOk = modelFetchOk,
            onFetch = { onFetchModels(buildConfig(), apiKey.trim()) },
            onDismiss = { pickTarget = ModelTarget.NONE },
            onPick = { picked ->
                if (pickTarget == ModelTarget.PRIMARY) model = picked else visionModel = picked
                pickTarget = ModelTarget.NONE
            },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    title: String,
    fetched: List<String>,
    curated: List<String>,
    current: String,
    fetching: Boolean,
    fetchMessage: String?,
    fetchOk: Boolean,
    onFetch: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onFetch, enabled = !fetching) {
                        if (fetching) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("获取中…")
                        } else {
                            Text("从 API 获取")
                        }
                    }
                }
                fetchMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fetchOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                if (fetched.isNotEmpty()) {
                    ModelGroup(
                        title = "这个 Key 可用的模型",
                        models = fetched,
                        current = current,
                        onPick = onPick,
                    )
                }
                if (curated.isNotEmpty()) {
                    ModelGroup(
                        title = "推荐模型",
                        models = curated,
                        current = current,
                        onPick = onPick,
                    )
                }
                if (fetched.isEmpty() && curated.isEmpty()) {
                    Text(
                        text = "还没有可选的模型。可以先点「从 API 获取」，或者直接在输入框里手写模型 ID。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun ModelGroup(
    title: String,
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    models.forEach { modelId ->
        val selected = modelId == current
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(modelId) },
            shape = RoundedCornerShape(10.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = BorderStroke(
                1.dp,
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                },
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

private fun protocolLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI -> "OpenAI Chat Completions 兼容（DeepSeek / GPT / Kimi / Qwen / GLM）"
    ProviderKind.GEMINI -> "Google Gemini generateContent"
    ProviderKind.CLAUDE -> "Anthropic Messages"
}
