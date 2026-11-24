package com.aisocial.copilot.ui.screens

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aisocial.copilot.BuildConfig
import com.aisocial.copilot.data.prefs.AppPreferences
import com.aisocial.copilot.domain.model.ThemeMode
import com.aisocial.copilot.overlay.OverlayService
import com.aisocial.copilot.overlay.ReplyFillService
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.Pill
import com.aisocial.copilot.ui.components.ScreenScaffold
import com.aisocial.copilot.ui.components.SectionHeader
import com.aisocial.copilot.ui.components.SettingRow
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    prefs: AppPreferences,
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetActiveProvider: (String) -> Unit,
    onSetFallbackProvider: (String) -> Unit,
    onSetParams: (Int, Double, Int) -> Unit,
    onSetHistoryEnabled: (Boolean) -> Unit,
    onSetConfirmBeforeSend: (Boolean) -> Unit,
    onSetOverlayEnabled: (Boolean) -> Unit,
    onSetOverlayAlpha: (Float) -> Unit,
    onSetAccessibilityFill: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClearCache: () -> Unit,
    onClearEverything: () -> Unit,
    onResetPresets: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    var showActivePicker by remember { mutableStateOf(false) }
    var showFallbackPicker by remember { mutableStateOf(false) }
    var showTimeoutPicker by remember { mutableStateOf(false) }
    var showClearAll by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }

    var fillServiceOn by remember { mutableStateOf(isFillServiceEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fillServiceOn = isFillServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val overlayPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission(context)) {
            runCatching { OverlayService.start(context) }
            onSetOverlayEnabled(true)
        } else {
            onSetOverlayEnabled(false)
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    fun enableOverlay() {
        requestNotificationPermission()
        if (hasOverlayPermission(context)) {
            runCatching { OverlayService.start(context) }
            onSetOverlayEnabled(true)
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { overlayPermission.launch(intent) }
                .onFailure { onSetOverlayEnabled(false) }
        }
    }

    fun disableOverlay() {
        runCatching { OverlayService.stop(context) }
        onSetOverlayEnabled(false)
    }

    val activeProvider = prefs.ai.providerById(prefs.ai.activeProviderId)
    val fallbackProvider = prefs.ai.providerById(prefs.ai.fallbackProviderId)

    ScreenScaffold(
        title = "设置",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            SectionHeader(title = "模型服务")
            AppCard {
                prefs.ai.providers.forEachIndexed { index, config ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingRow(
                        title = config.displayName,
                        subtitle = buildString {
                            append(if (config.enabled) "已启用" else "已停用")
                            append(" · ")
                            append(
                                if (prefs.providersWithKey.contains(config.id)) "已配置 Key"
                                else "未配置 Key"
                            )
                            if (config.model.isNotBlank()) append(" · ${config.model}")
                        },
                        onClick = { onOpenProvider(config.id) },
                        trailing = {
                            if (config.id == prefs.ai.activeProviderId) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        text = "默认",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            AppCard {
                SettingRow(
                    title = "默认模型",
                    subtitle = activeProvider?.displayName ?: "未选择",
                    onClick = { showActivePicker = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "备用模型",
                    subtitle = if (fallbackProvider == null || prefs.ai.fallbackProviderId.isBlank()) {
                        "未设置（默认模型失败时不自动重试）"
                    } else {
                        fallbackProvider.displayName
                    },
                    onClick = { showFallbackPicker = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "请求超时",
                    subtitle = "${prefs.ai.timeoutSeconds} 秒",
                    onClick = { showTimeoutPicker = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("创造性（temperature）", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = String.format("%.1f", prefs.ai.temperature),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = prefs.ai.temperature.toFloat(),
                        onValueChange = {
                            onSetParams(
                                prefs.ai.timeoutSeconds,
                                (it * 10f).roundToInt() / 10.0,
                                prefs.ai.maxTokens,
                            )
                        },
                        valueRange = 0f..1.5f,
                    )
                    Text(
                        text = "越低越稳定保守，越高越发散。聊天建议推荐 0.8~1.0。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader(title = "外观")
            AppCard {
                SettingRow(
                    title = "主题",
                    subtitle = prefs.themeMode.label,
                    onClick = { showThemePicker = true },
                )
            }

            SectionHeader(title = "悬浮球")
            AppCard {
                SettingRow(
                    title = "开启悬浮球",
                    subtitle = "在聊天界面点小球 → 框选屏幕 → 直接弹出三条可点的回复",
                    trailing = {
                        Switch(
                            checked = prefs.overlayEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) enableOverlay() else disableOverlay()
                            },
                        )
                    },
                )
                if (prefs.overlayEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("透明度", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${(prefs.overlayAlpha * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = prefs.overlayAlpha,
                            onValueChange = {
                                onSetOverlayAlpha(it)
                                OverlayService.setAlpha(it)
                            },
                            valueRange = 0.25f..1f,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { OverlayService.showBall() }) { Text("显示悬浮球") }
                        TextButton(onClick = { OverlayService.hideBall() }) { Text("隐藏悬浮球") }
                    }
                }
            }

            SectionHeader(title = "点一下填入输入框")
            AppCard {
                SettingRow(
                    title = "启用「填入回复」无障碍服务",
                    subtitle = if (fillServiceOn) {
                        "已开启：在悬浮球里点某条回复，会直接写进当前聊天输入框（不会自动发送）"
                    } else {
                        "需要你在系统设置里手动开启。只在点选时写入那一条文字，不读取、不上传聊天内容"
                    },
                    onClick = { openAccessibilitySettings(context) },
                    trailing = {
                        Switch(
                            checked = fillServiceOn,
                            onCheckedChange = {
                                onSetAccessibilityFill(it)
                                openAccessibilitySettings(context)
                            },
                        )
                    },
                )
                if (fillServiceOn) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingRow(
                        title = "关闭「填入回复」",
                        subtitle = "到系统无障碍设置里关掉即可，随时可以反悔",
                        onClick = { openAccessibilitySettings(context) },
                    )
                }
            }

            SectionHeader(title = "隐私与数据")
            AppCard {
                SettingRow(
                    title = "保存生成历史",
                    subtitle = "仅保存你粘贴的文字和生成的回复，不保存截图",
                    trailing = {
                        Switch(checked = prefs.historyEnabled, onCheckedChange = onSetHistoryEnabled)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "每次发送前确认",
                    subtitle = "发送给模型前先预览将要发送的内容",
                    trailing = {
                        Switch(checked = prefs.confirmBeforeSend, onCheckedChange = onSetConfirmBeforeSend)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "清除生成历史",
                    subtitle = "删除本机保存的生成记录",
                    onClick = onClearHistory,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "清除缓存",
                    subtitle = "清理临时文件，不会删除设置和人设",
                    onClick = onClearCache,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "清除全部数据",
                    subtitle = "包括 API Key、人设、历史与全部设置",
                    onClick = { showClearAll = true },
                )
            }

            SectionHeader(title = "其他")
            AppCard {
                SettingRow(
                    title = "恢复内置人设预设",
                    subtitle = "补充缺失的自然 / 温柔 / 成熟 等预设",
                    onClick = onResetPresets,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "版本",
                    subtitle = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                )
            }

            SectionHeader(title = "使用条款与免责声明")
            AppCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "本应用只做两件事：把你想说的话写得更得体，并在你确认后填进输入框。" +
                            "它不会替你发送任何消息，也不会替你做决定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "禁止用途：诈骗与刷单、冒充他人身份、骚扰威胁他人、" +
                            "造谣传谣，以及其他违反所在地法律法规的用途。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "使用本应用产生的全部后果由使用者本人承担，与开发者无关。" +
                            "AI 生成内容可能存在错误或不妥，发送前请自行判断。" +
                            "请遵守你所使用平台的用户协议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }

            SectionHeader(title = "反馈与联系")
            AppCard {
                SettingRow(
                    title = "QQ",
                    subtitle = "3199948824",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "B 站",
                    subtitle = "私信后台反馈，附上建议或问题截图会更快定位",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingRow(
                    title = "想改进什么",
                    subtitle = "哪一步卡住了、哪句话回得不对、想要什么功能，都欢迎直接说",
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "截图框选、文字识别、人设和设置都在本机处理。API Key 使用 Android Keystore " +
                    "加密保存，不会写入日志。只有你确认发送的内容才会离开这台设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showThemePicker) {
        OptionDialog(
            title = "主题",
            options = ThemeMode.entries.map { it.label to it },
            onDismiss = { showThemePicker = false },
            onPick = { mode ->
                onSetTheme(mode)
                showThemePicker = false
            },
        )
    }

    if (showActivePicker) {
        OptionDialog(
            title = "默认模型",
            options = prefs.ai.providers.map { provider ->
                "${provider.displayName}${if (prefs.providersWithKey.contains(provider.id)) "" else "（未配置 Key）"}" to provider.id
            },
            onDismiss = { showActivePicker = false },
            onPick = { id ->
                onSetActiveProvider(id)
                showActivePicker = false
            },
        )
    }

    if (showFallbackPicker) {
        OptionDialog(
            title = "备用模型",
            options = buildList {
                add("不使用备用模型" to "")
                prefs.ai.providers.filter { it.id != prefs.ai.activeProviderId }.forEach { provider ->
                    add(provider.displayName to provider.id)
                }
            },
            onDismiss = { showFallbackPicker = false },
            onPick = { id ->
                onSetFallbackProvider(id)
                showFallbackPicker = false
            },
        )
    }

    if (showTimeoutPicker) {
        OptionDialog(
            title = "请求超时",
            options = listOf(30, 60, 90, 120, 180, 240).map { "$it 秒" to it },
            onDismiss = { showTimeoutPicker = false },
            onPick = { seconds ->
                onSetParams(seconds, prefs.ai.temperature, prefs.ai.maxTokens)
                showTimeoutPicker = false
            },
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清除全部数据") },
            text = {
                Text(
                    "这会删除所有 API Key、人设、生成历史和设置，并且无法恢复。" +
                        "内置人设预设会被重新创建。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    onClearEverything()
                }) { Text("全部清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<Pair<String, T>>,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    TextButton(
                        onClick = { onPick(value) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun hasOverlayPermission(context: Context): Boolean =
    runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

private fun isFillServiceEnabled(context: Context): Boolean {
    if (ReplyFillService.isConnected) return true
    return runCatching {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val target = ComponentName(context, ReplyFillService::class.java)
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == target
            }
    }.getOrDefault(false)
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
