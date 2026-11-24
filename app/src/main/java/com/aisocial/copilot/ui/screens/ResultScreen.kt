package com.aisocial.copilot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.aisocial.copilot.domain.model.AnalysisResult
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ReplyOp
import com.aisocial.copilot.domain.model.ReplySuggestion
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.ScreenScaffold
import com.aisocial.copilot.ui.session.SessionState

@Composable
fun ResultScreen(
    state: SessionState,
    activePersona: Persona,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onOperation: (Int, ReplyOp) -> Unit,
    onRestore: (ReplySuggestion) -> Unit,
    onEditReply: (ReplySuggestion, String) -> Unit,
    onMore: () -> Unit,
    onRegenerate: () -> Unit,
    onEditInputs: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var editing by remember { mutableStateOf<ReplySuggestion?>(null) }
    var editingText by remember { mutableStateOf("") }
    val result = state.result

    ScreenScaffold(
        title = "回复建议",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(onClick = onRegenerate) { Text("重新生成") }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (state.generating) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在生成…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            if (state.transformBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = "人设：${activePersona.name}　模型：${state.providerLabel} · ${state.modelLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.usedFallback) {
                Text(
                    text = "默认模型调用失败，已自动切换到备用模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (state.imagesDropped) {
                Text(
                    text = "当前模型不支持图片，已改用本地识别的文字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            state.error?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
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

            if (result == null) {
                Text(
                    text = "还没有生成结果。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onEditInputs) { Text("返回修改输入") }
            } else {
                if (result.hasAnalysis) {
                    AnalysisCard(result)
                    Spacer(Modifier.height(12.dp))
                }

                result.replies.forEachIndexed { index, reply ->
                    ReplyCard(
                        index = index,
                        reply = reply,
                        canRestore = state.originals.containsKey(reply.id),
                        enabled = !state.transformBusy && !state.generating,
                        onCopy = { onCopy(reply.text) },
                        onOperation = { op -> onOperation(index, op) },
                        onRestore = { onRestore(reply) },
                        onEdit = {
                            editing = reply
                            editingText = reply.text
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = onMore,
                    enabled = !state.transformBusy && !state.generating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("再给我三条不一样的")
                }

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onEditInputs, modifier = Modifier.fillMaxWidth()) {
                    Text("修改输入内容")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "回复不会自动发送。复制后请自己检查一遍再发出去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    editing?.let { reply ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("修改这条回复") },
            text = {
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditReply(reply, editingText.trim())
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AnalysisCard(result: AnalysisResult) {
    AppCard {
        Text(
            text = "AI 分析",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (result.replyingTo.isNotBlank()) {
            AnalysisLine("正在回应", result.replyingTo)
        }
        if (result.tone.isNotBlank()) {
            AnalysisLine("语气判断", result.tone)
        }
        if (result.strategy.isNotBlank()) {
            AnalysisLine("建议策略", result.strategy)
        }
        if (result.analysis.isNotBlank()) {
            AnalysisLine("对方状态", result.analysis)
        }
    }
}

@Composable
private fun AnalysisLine(label: String, value: String) {
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyCard(
    index: Int,
    reply: ReplySuggestion,
    canRestore: Boolean,
    enabled: Boolean,
    onCopy: () -> Unit,
    onOperation: (ReplyOp) -> Unit,
    onRestore: () -> Unit,
    onEdit: () -> Unit,
) {
    var toneMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = reply.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "方案 ${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = reply.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )

        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SmallAction("复制", enabled, onCopy)
            SmallAction("改写", enabled) { onOperation(ReplyOp.REWRITE) }
            SmallAction("更短", enabled) { onOperation(ReplyOp.SHORTEN) }
            SmallAction("更详细", enabled) { onOperation(ReplyOp.EXPAND) }

            Box {
                SmallAction("语气", enabled) { toneMenuOpen = true }
                DropdownMenu(
                    expanded = toneMenuOpen,
                    onDismissRequest = { toneMenuOpen = false },
                ) {
                    listOf(
                        ReplyOp.NATURAL,
                        ReplyOp.HUMOR,
                        ReplyOp.PROACTIVE,
                        ReplyOp.SUBTLE,
                        ReplyOp.CALM,
                    ).forEach { op ->
                        DropdownMenuItem(
                            text = { Text(op.label) },
                            onClick = {
                                toneMenuOpen = false
                                onOperation(op)
                            },
                        )
                    }
                }
            }

            Box {
                SmallAction("更多", enabled) { moreMenuOpen = true }
                DropdownMenu(
                    expanded = moreMenuOpen,
                    onDismissRequest = { moreMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("再生成一条") },
                        onClick = {
                            moreMenuOpen = false
                            onOperation(ReplyOp.MORE)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑文字") },
                        onClick = {
                            moreMenuOpen = false
                            onEdit()
                        },
                    )
                    if (canRestore) {
                        DropdownMenuItem(
                            text = { Text("还原改写前的版本") },
                            onClick = {
                                moreMenuOpen = false
                                onRestore()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
