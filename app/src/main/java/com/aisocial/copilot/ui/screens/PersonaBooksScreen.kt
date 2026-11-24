package com.aisocial.copilot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aisocial.copilot.domain.model.PersonaBook
import com.aisocial.copilot.ui.components.AppCard
import com.aisocial.copilot.ui.components.ScreenScaffold

@Composable
fun PersonaBooksScreen(
    books: List<PersonaBook>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (PersonaBook) -> Unit,
    onActivate: (PersonaBook) -> Unit,
    onDeactivate: () -> Unit,
    onDelete: (PersonaBook) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var deleteTarget by remember { mutableStateOf<PersonaBook?>(null) }
    val active = books.firstOrNull { it.isActive }

    ScreenScaffold(
        title = "人设书",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "新建人设书")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AppCard {
                    Text(
                        text = "人设书是什么",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "用你自己的话写「我是谁、我平时怎么说话」。写好后它会注入每一次识别，" +
                            "优先级高于下面那些模板人设；两者冲突时以人设书为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = if (active == null) {
                            "当前没有启用的人设书。"
                        } else {
                            "当前启用：${active.title}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            items(items = books, key = { it.id }) { book ->
                PersonaBookRow(
                    book = book,
                    onOpen = { onEdit(book) },
                    onActivate = { onActivate(book) },
                    onDeactivate = onDeactivate,
                    onDelete = { deleteTarget = book },
                )
            }

            if (books.isEmpty()) {
                item {
                    AppCard(onClick = onAdd) {
                        Text(
                            text = "还没有人设书，点这里写第一本",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "例如：我叫小林，26 岁做设计，说话偏短句，不太用表情包，" +
                                "跟女朋友聊天会故意装傻，跟同事聊天就比较直接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除人设书") },
            text = { Text("确定删除「${target.title}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PersonaBookRow(
    book: PersonaBook,
    onOpen: () -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (book.isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = book.title.ifBlank { "未命名人设书" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (book.isActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            text = "生效中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("启用这本") },
                            onClick = {
                                menuOpen = false
                                onActivate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            onClick = {
                                menuOpen = false
                                onOpen()
                            },
                        )
                        if (book.isActive) {
                            DropdownMenuItem(
                                text = { Text("停止使用") },
                                onClick = {
                                    menuOpen = false
                                    onDeactivate()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Text(
                text = book.snippet.ifBlank { "（还没有写内容）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, end = 40.dp),
            )
        }
    }
}
