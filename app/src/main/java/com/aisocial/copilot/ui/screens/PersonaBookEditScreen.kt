package com.aisocial.copilot.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.aisocial.copilot.domain.model.PersonaBook
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.ScreenScaffold

private const val BOOK_TEMPLATE = """我是谁：
（年龄、职业、性格、和对方是什么关系）

我怎么说话：
（句子长短、常用词、会不会用表情、爱不爱开玩笑）

我在这个关系里想成为什么样的人：
（比如：想显得稳一点、不想太主动、希望别把气氛搞僵）"""

@Composable
fun PersonaBookEditScreen(
    bookId: Long,
    onBack: () -> Unit,
    load: suspend (Long) -> PersonaBook?,
    onSave: (PersonaBook) -> Unit,
    onDelete: (PersonaBook) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var draft by remember(bookId) {
        mutableStateOf<PersonaBook?>(null)
    }
    var loaded by remember(bookId) { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        val existing = if (bookId > 0L) load(bookId) else null
        draft = existing ?: PersonaBook(title = "", content = "", isActive = true)
        loaded = true
    }

    val current = draft

    ScreenScaffold(
        title = if (bookId > 0L) "编辑人设书" else "新建人设书",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(
                onClick = { current?.let { onSave(it) } },
                enabled = !current?.content.isNullOrBlank(),
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
                    value = current.title,
                    onValueChange = { draft = (draft ?: current).copy(title = it) },
                    label = { Text("给这本起个名字") },
                    placeholder = { Text("例如：我本人 / 跟女朋友聊天时的我") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))

            AppCard {
                Text("人设书正文", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "随便写，不用按格式。保存后会立刻替换掉旧版本并马上生效，" +
                        "下一次截屏识别就直接用这本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = current.content,
                    onValueChange = { draft = (draft ?: current).copy(content = it) },
                    placeholder = { Text(BOOK_TEMPLATE) },
                    minLines = 12,
                    maxLines = 24,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${current.content.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onSave(current.copy(isActive = true)) },
                enabled = current.content.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) { Text("保存并立即启用") }

            if (bookId > 0L) {
                TextButton(
                    onClick = { showDelete = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text("删除这本", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showDelete && current != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除人设书") },
            text = { Text("确定删除「${current.title.ifBlank { "未命名人设书" }}」吗？") },
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
