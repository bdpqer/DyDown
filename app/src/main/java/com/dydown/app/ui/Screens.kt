package com.dydown.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dydown.app.core.Kind
import com.dydown.app.core.LiveInfo
import com.dydown.app.core.MediaMeta
import com.dydown.app.data.TStatus
import com.dydown.app.data.TaskEntity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel, initialLink: String? = null) {
    // 0=设置 1=解析 2=批量 3=录播 4=任务；设置按需求并入底栏并排在最左
    var tab by remember { mutableIntStateOf(1) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (tab == 0) {
                        Text("设置", fontWeight = FontWeight.Medium)
                    } else {
                        // 能力说明紧跟在 DyDown 后面，不再占解析页正文
                        Column {
                            Text("DyDown", fontWeight = FontWeight.Medium)
                            Text(
                                "支持：视频 · 图集 · 主页批量 · 直播录屏",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavItem(tab, 0, "设置", Icons.Default.Settings) { tab = it }
                NavItem(tab, 1, "解析", Icons.Default.Add) { tab = it }
                NavItem(tab, 2, "批量", Icons.Default.List) { tab = it }
                NavItem(tab, 3, "录播", Icons.Default.PlayArrow) { tab = it }
                NavItem(tab, 4, "任务", Icons.Default.Done) { tab = it }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> SettingsScreen(vm)
                1 -> HomeScreen(vm, initialLink)
                2 -> BatchScreen(vm)
                3 -> LiveScreen(vm)
                else -> TasksScreen(vm)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(current: Int, index: Int, label: String, icon: ImageVector, onClick: (Int) -> Unit) {
    NavigationBarItem(
        selected = current == index,
        onClick = { onClick(index) },
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) }
    )
}

/* ----------------------------- 解析页 ----------------------------- */

@Composable
private fun HomeScreen(vm: MainViewModel, initialLink: String?) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(initialLink.orEmpty()) }

    LaunchedEffect(initialLink) {
        if (!initialLink.isNullOrBlank()) {
            text = initialLink
            vm.parse(initialLink)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("抖音分享链接或分享文案") },
            placeholder = { Text("https://v.douyin.com/xxxxxxx/") },
            minLines = 3,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                }
            }
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = {
                clipboard.getText()?.text?.let { text = it }
            }) { Text("粘贴") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.parse(text) },
                enabled = vm.parseState !is UiState.Loading
            ) {
                if (vm.parseState is UiState.Loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("解析")
            }
        }

        when (val s = vm.parseState) {
            is UiState.Loading -> CenterHint("正在尝试四条通道…")
            is UiState.Err -> ErrorCard(s.message)
            is UiState.Ok -> MetaCard(vm, s.data)
            UiState.Idle -> CenterHint("粘贴抖音分享链接或分享文案，点「解析」开始")
        }
    }
}

@Composable
private fun MetaCard(vm: MainViewModel, meta: MediaMeta) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                AsyncImage(
                    model = meta.cover,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp, 128.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        meta.title.ifBlank { "无标题" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("@" + meta.author.ifBlank { "未知作者" }, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "来源：${meta.source} · " + when (meta.kind) {
                            Kind.GALLERY -> "图集 ${meta.images.size} 张"
                            else -> "视频 ${meta.videos.size} 档"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (meta.kind == Kind.VIDEO && meta.videos.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("清晰度", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(meta.videos.size) { i ->
                        FilterChip(
                            selected = vm.qualityIndex == i,
                            onClick = { vm.selectQuality(i) },
                            label = { Text(meta.videos[i].label) }
                        )
                    }
                }
            }

            if (meta.kind == Kind.GALLERY) {
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(meta.images.size) { i ->
                        AsyncImage(
                            model = meta.images[i],
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp, 84.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.enqueue(meta, vm.qualityIndex) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (meta.kind == Kind.GALLERY) "下载图集" else "下载视频")
                }
                if (meta.music != null) {
                    TextButton(onClick = {
                        vm.enqueue(
                            meta.copy(
                                kind = Kind.VIDEO,
                                videos = listOfNotNull(meta.music),
                                images = emptyList()
                            )
                        )
                    }) { Text("只要 BGM") }
                }
            }
        }
    }
}

/* ----------------------------- 批量页 ----------------------------- */

@Composable
private fun BatchScreen(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val b = vm.batch

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("主页链接") },
            placeholder = { Text("https://www.douyin.com/user/MS4wLjABAAAA...") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = {
                    clipboard.getText()?.text?.let { text = it }
                }) { Icon(Icons.Default.Add, contentDescription = "粘贴") }
            }
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自动加载全部", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.loadProfile(text) },
                enabled = !b.loading
            ) {
                if (b.loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("拉取列表")
            }
        }

        b.error?.let { ErrorCard(it) }

        if (b.items.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${b.items.size} 条", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(12.dp))
                Text("已选 ${b.selected.size}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.selectAll(b.selected.size != b.items.size) }) {
                    Text(if (b.selected.size == b.items.size) "全不选" else "全选")
                }
            }
            HorizontalDivider()
        }

        LazyColumn(Modifier.weight(1f)) {
            items(b.items, key = { it.awemeId }) { item ->
                BatchRow(
                    meta = item,
                    checked = b.selected.contains(item.awemeId),
                    onToggle = { vm.toggle(item.awemeId) }
                )
            }
        }

        if (b.items.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (b.hasMore) {
                    TextButton(onClick = { vm.loadMore() }, enabled = !b.loadingMore) {
                        Text(if (b.loadingMore) "拉取中…" else "再拉一页")
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.downloadSelected() }) {
                    Text("下载选中 (${b.selected.size})")
                }
            }
        }
    }
}

@Composable
private fun BatchRow(meta: MediaMeta, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        AsyncImage(
            model = meta.cover,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp, 64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                meta.title.ifBlank { "无标题" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 显示作者，方便一眼核对列表里有没有混进别人家的作品
            Text(
                (if (meta.kind == Kind.GALLERY) "图集 ${meta.images.size} 张" else "视频") +
                    if (meta.author.isNotBlank()) " · @${meta.author}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/* ----------------------------- 录播页 ----------------------------- */

@Composable
private fun LiveScreen(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("直播间链接") },
            placeholder = { Text("https://live.douyin.com/123456789") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = {
                    clipboard.getText()?.text?.let { text = it }
                }) { Icon(Icons.Default.Add, contentDescription = "粘贴") }
            }
        )

        Row(Modifier.padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.resolveLive(text) }, enabled = vm.liveState !is UiState.Loading) {
                if (vm.liveState is UiState.Loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("解析直播间")
            }
        }

        when (val s = vm.liveState) {
            is UiState.Loading -> CenterHint("正在取流地址…")
            is UiState.Err -> ErrorCard(s.message)
            is UiState.Ok -> LiveCard(vm, s.data)
            UiState.Idle -> CenterHint("录制时长不限，停止后自动转 mp4")
        }
    }
}

@Composable
private fun LiveCard(vm: MainViewModel, info: LiveInfo) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                AsyncImage(
                    model = info.cover,
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp, 68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(info.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Text("@" + info.author.ifBlank { "未知主播" }, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (info.living) "正在直播" else "未开播 / 已结束",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (info.living) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    info.flv != null -> "FLV 流已获取（推荐，最省 CPU）"
                    info.hls != null -> "只拿到 HLS 流，稳定性稍差"
                    else -> "没有可用流地址"
                },
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.startRecord(info) },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.recording
                ) { Text("开始录制") }
                TextButton(
                    onClick = { vm.stopRecord() },
                    enabled = vm.recording
                ) { Text("停止") }
            }
        }
    }
}

/* ----------------------------- 任务页 ----------------------------- */

@Composable
private fun TasksScreen(vm: MainViewModel) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tasks.size} 个任务", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.clearFinished() }) { Text("清理已完成") }
        }
        HorizontalDivider()

        if (tasks.isEmpty()) {
            CenterHint("还没有任务，去「解析」页粘一条链接")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(tasks, key = { it.id }) { t -> TaskRow(vm, t) }
            }
        }
    }
}

@Composable
private fun TaskRow(vm: MainViewModel, t: TaskEntity) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = t.cover,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp, 58.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title.ifBlank { "抖音作品" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    vm.statusText(t) + if (t.quality.isNotBlank()) " · ${t.quality}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = when (t.status) {
                        TStatus.DONE -> MaterialTheme.colorScheme.primary
                        TStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (t.status == TStatus.FAILED) {
                IconButton(onClick = { vm.retry(t) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "重试")
                }
            }
            IconButton(onClick = { vm.remove(t) }) {
                Icon(Icons.Default.Clear, contentDescription = "删除")
            }
        }
        if (t.status == TStatus.RUNNING && t.kind != "LIVE") {
            LinearProgressIndicator(
                progress = { t.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .padding(top = 6.dp)
            )
        }
        if (t.kind == "LIVE" && t.status == TStatus.RUNNING) {
            Text(
                "已录 " + formatSize(t.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    HorizontalDivider()
}

/* ----------------------------- 设置页 ----------------------------- */

@Composable
private fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var dir by remember { mutableStateOf(vm.saveDir()) }
    var imageDir by remember { mutableStateOf(vm.imageDir()) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("账号", style = MaterialTheme.typography.labelLarge)
        Text(
            "分享页和接口都依赖登录态。如果解析老是提示「空列表 / 风控」，先来这里登录一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            ctx.startActivity(android.content.Intent(ctx, com.dydown.app.LoginActivity::class.java))
        }) { Text("打开抖音网页登录") }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("yt-dlp 内核", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                vm.ytDlpVersion,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.updateYtDlp() }, enabled = !vm.updatingYtDlp) {
                Text(if (vm.updatingYtDlp) "更新中…" else "检查更新")
            }
        }
        Text(
            "抖音改版后上游通常几天内修复，点一次更新即可，不用重装 APK。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("保存位置", style = MaterialTheme.typography.labelLarge)
        Text(
            "MediaStore 相对路径，视频与图集可分别设置",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = dir,
            onValueChange = { dir = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("视频目录") },
            placeholder = { Text("Movies/DyDown") },
            supportingText = { Text("视频、直播录制成品") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = imageDir,
            onValueChange = { imageDir = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("图集目录") },
            placeholder = { Text("Pictures/DyDown") },
            supportingText = { Text("每个作品再建 gallery_作品ID 子目录") }
        )
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.saveDirs(dir, imageDir) }) { Text("保存") }
        }

        // 主页批量统一走 WebView 自动滚动加载全部作品，不再提供页数设置
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("说明", style = MaterialTheme.typography.labelLarge)
        Text(
            "· 默认：视频 Movies/DyDown，图集 Pictures/DyDown\n" +
                "· 图集会在所填目录下再建 gallery_作品ID 子目录\n" +
                "· 直播录制约 1 小时 500MB-1GB，注意存储\n" +
                "· 仅供个人离线保存，不要二次发布或商用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ----------------------------- 小组件 ----------------------------- */

@Composable
private fun CenterHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes < 1024 * 1024) String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
