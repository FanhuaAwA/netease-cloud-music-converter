package com.fanhua.yuncheng

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fanhua.yuncheng.ui.theme.YunChengTheme
import java.util.Locale

private val Hairline = Color(0xFFE1E8F3)
private val SoftBlue = Color(0xFFEAF2FF)
private val HeroBrush = Brush.linearGradient(listOf(Color(0xFF075FF2), Color(0xFF2588FF)))
private val LogoBrush = Brush.linearGradient(listOf(Color(0xFF075FF2), Color(0xFF45A7FF)))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { YunChengTheme { Home() } }
    }
}

@Composable
private fun Home(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sourcePick by remember { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let { uri -> vm.setFolder(uri, sourcePick) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ActionBar(state, vm::convert) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(contentType = "header") { Header() }
            item(contentType = "folders") {
                WorkspaceCard(
                    state,
                    onSource = { sourcePick = true; picker.launch(state.sourceUri) },
                    onOutput = { sourcePick = false; picker.launch(state.outputUri) },
                    onScan = vm::scan,
                )
            }
            item(contentType = "summary") { Summary(state, vm::setConcurrency) }
            if (state.songs.isNotEmpty()) item(contentType = "libraryHeader") {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column { Text("音乐库", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("${state.songs.size} 首本地歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                    TextButton(onClick = { vm.selectAll(state.selectedCount != state.songs.size) }) { Text(if (state.selectedCount == state.songs.size) "取消全选" else "全选") }
                }
            }
            items(state.songs, key = { it.uri.toString() }, contentType = { "song" }) { song ->
                SongRow(song, state.converting) { vm.toggle(song.uri) }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun Header() = Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(46.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("云澄", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
            Text("YUNCHENG AUDIO", fontSize = 9.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary)
        }
        Surface(shape = CircleShape, color = SoftBlue) { Text("PRIVATE · LOCAL", Modifier.padding(12.dp, 7.dp), color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold) }
    }
    Spacer(Modifier.height(24.dp))
    Text("本地音乐，一键焕新", fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp)
    Text("原始音质与音乐信息，完整保留。", Modifier.padding(top = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
}

@Composable
private fun BrandMark(markSize: androidx.compose.ui.unit.Dp) = Box(
    Modifier.size(markSize).clip(RoundedCornerShape(markSize * .31f)).background(LogoBrush),
    Alignment.Center,
) {
    Canvas(Modifier.size(markSize * .58f)) {
        fun wave(y: Float) = Path().apply {
            moveTo(size.width * .06f, size.height * y)
            cubicTo(size.width * .25f, size.height * (y - .24f), size.width * .42f, size.height * (y - .24f), size.width * .57f, size.height * y)
            cubicTo(size.width * .70f, size.height * (y + .20f), size.width * .83f, size.height * (y + .18f), size.width * .94f, size.height * (y - .05f))
        }
        drawPath(wave(.47f), Color.White, style = Stroke(size.width * .105f, cap = StrokeCap.Round))
        drawPath(wave(.70f), Color.White.copy(alpha = .58f), style = Stroke(size.width * .075f, cap = StrokeCap.Round))
    }
}

@Composable
private fun WorkspaceCard(state: UiState, onSource: () -> Unit, onOutput: () -> Unit, onScan: () -> Unit) = Surface(
    shape = RoundedCornerShape(26.dp), color = Color.Transparent,
) {
    Column(Modifier.background(HeroBrush).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("音乐转换工作台", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        Text("选择目录，由你决定何时检测与转换。", color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        PathRow("IN", "歌曲来源", state.sourceUri?.lastPathSegment ?: "选择 Music 目录", onSource)
        PathRow("OUT", "输出位置", state.outputUri?.lastPathSegment ?: "选择目标目录", onOutput)
        Button(
            onClick = onScan,
            enabled = state.sourceUri != null && !state.scanning && !state.converting,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
        ) {
            if (state.scanning) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp); Spacer(Modifier.width(9.dp)); Text("正在检测") }
            else Text("检测歌曲  →", fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun PathRow(code: String, label: String, path: String, click: () -> Unit) = Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = click), shape = RoundedCornerShape(15.dp), color = Color.White.copy(alpha = .13f),
) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f)), Alignment.Center) { Text(code, color = Color.White, fontSize = 9.sp, letterSpacing = .5.sp, fontWeight = FontWeight.ExtraBold) }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = .62f))
            Text(path, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Chevron(Color.White.copy(alpha = .72f))
    }
}

@Composable
private fun Chevron(color: Color) = Canvas(Modifier.size(18.dp)) {
    val path = Path().apply { moveTo(size.width * .38f, size.height * .25f); lineTo(size.width * .65f, size.height * .50f); lineTo(size.width * .38f, size.height * .75f) }
    drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
}

@Composable
private fun Summary(state: UiState, setConcurrency: (Int) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("媒体概览", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Metric("NCM", state.ncmCount, Modifier.weight(1f)); Metric("FLAC", state.flacCount, Modifier.weight(1f)); Metric("MP3", state.mp3Count, Modifier.weight(1f)); Metric("其他", state.otherCount, Modifier.weight(1f))
    }
    if (state.songs.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("并发任务", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        listOf(1, 2, 3, 4).forEach { value ->
            FilterChip(state.concurrency == value, { setConcurrency(value) }, { Text(value.toString()) }, Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier) = Surface(modifier, RoundedCornerShape(16.dp), Color.White, border = BorderStroke(1.dp, Hairline)) {
    Column(Modifier.padding(14.dp)) { Text(value.toString(), fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary); Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun SongRow(song: Song, locked: Boolean, toggle: () -> Unit) {
    val subtitle = remember(song.artist, song.album, song.size) {
        listOf(song.artist, song.album, formatBytes(song.size)).filter(String::isNotBlank).joinToString(" · ")
    }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { selected = song.selected; role = Role.Checkbox }.clickable(enabled = !locked, onClick = toggle),
        shape = RoundedCornerShape(18.dp), color = Color.White,
        border = BorderStroke(1.dp, if (song.selected) MaterialTheme.colorScheme.primary.copy(alpha = .28f) else Hairline),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(SoftBlue), Alignment.Center) {
                Text(song.kind.label.take(4), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (song.lyricUri != null) Text("含歌词", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                if (song.state is WorkState.Failed) Text(song.state.reason, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
            when (song.state) {
                WorkState.Running -> CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                WorkState.Done -> Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                else -> SelectionMark(song.selected)
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) = Canvas(Modifier.size(23.dp)) {
    if (selected) {
        drawCircle(Color(0xFF0A66F5))
        val check = Path().apply { moveTo(size.width * .27f, size.height * .51f); lineTo(size.width * .44f, size.height * .67f); lineTo(size.width * .75f, size.height * .34f) }
        drawPath(check, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    } else drawCircle(Color(0xFFC7D1E0), style = Stroke(1.5.dp.toPx()))
}

@Composable
private fun ActionBar(state: UiState, convert: () -> Unit) = Surface(color = Color.White, border = BorderStroke(1.dp, Hairline)) {
    Row(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("已选择 ${state.selectedCount} 首", fontWeight = FontWeight.Bold)
            Text(state.message, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(convert, enabled = !state.converting && !state.scanning && state.selectedCount > 0, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(46.dp)) {
            if (state.converting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("开始转换", fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatBytes(value: Long): String = if (value < 1_048_576) "${value / 1024} KB" else String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
