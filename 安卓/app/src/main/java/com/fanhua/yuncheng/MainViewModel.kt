package com.fanhua.yuncheng

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val preferences = application.getSharedPreferences("folders", 0)
    private val mutable = MutableStateFlow(UiState(
        sourceUri = preferences.getString("source", null)?.let(Uri::parse),
        outputUri = preferences.getString("output", null)?.let(Uri::parse),
        concurrency = preferences.getInt("concurrency", 2).coerceIn(1, 4),
    ))
    val state = mutable.asStateFlow()
    private var work: Job? = null

    fun setFolder(uri: Uri, source: Boolean) {
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (source) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        preferences.edit { putString(if (source) "source" else "output", uri.toString()) }
        mutable.update { if (source) it.copy(sourceUri = uri, songs = emptyList(), message = "目录已选择，请点击检测") else it.copy(outputUri = uri, message = "输出目录已就绪") }
    }

    fun scan() {
        val uri = mutable.value.sourceUri ?: return message("请先选择歌曲目录")
        work?.cancel()
        work = viewModelScope.launch {
            mutable.update { it.copy(scanning = true, songs = emptyList(), message = "正在读取歌曲信息…") }
            runCatching { repository.scan(uri) }.onSuccess { songs ->
                mutable.update { it.copy(scanning = false, songs = songs, message = "检测完成：${songs.size} 首，NCM ${songs.count(Song::isNcm)} 首") }
            }.onFailure { mutable.update { s -> s.copy(scanning = false, message = "检测失败：${it.message}") } }
        }
    }

    fun convert() {
        val output = mutable.value.outputUri ?: return message("请先选择输出目录")
        val chosen = mutable.value.songs.filter { it.selected && it.state !is WorkState.Failed }
        if (chosen.isEmpty()) return message("请至少选择一首可处理歌曲")
        work = viewModelScope.launch {
            mutable.update { it.copy(converting = true, message = "正在转换 ${chosen.size} 首歌曲…") }
            val limiter = Semaphore(mutable.value.concurrency)
            val jobs = chosen.map { song -> launch {
                limiter.withPermit {
                    updateSong(song.uri) { it.copy(state = WorkState.Running) }
                    runCatching { repository.convert(song, output) }
                        .onSuccess { updateSong(song.uri) { it.copy(state = WorkState.Done) } }
                        .onFailure { e -> updateSong(song.uri) { it.copy(state = WorkState.Failed(e.message ?: "转换失败")) } }
                }
            } }
            jobs.forEach { it.join() }
            val failed = mutable.value.songs.count { it.selected && it.state is WorkState.Failed }
            mutable.update { it.copy(converting = false, message = if (failed == 0) "全部处理完成，可在椒盐音乐中扫描输出目录" else "处理完成，失败 $failed 首") }
        }
    }

    fun toggle(uri: Uri) = updateSong(uri) { it.copy(selected = !it.selected) }
    fun selectAll(selected: Boolean) = mutable.update { state -> state.copy(songs = state.songs.map { it.copy(selected = selected) }) }
    fun setConcurrency(value: Int) { val safe = value.coerceIn(1, 4); preferences.edit { putInt("concurrency", safe) }; mutable.update { it.copy(concurrency = safe) } }
    private fun updateSong(uri: Uri, action: (Song) -> Song) = mutable.update { state -> state.copy(songs = state.songs.map { if (it.uri == uri) action(it) else it }) }
    private fun message(value: String) = mutable.update { it.copy(message = value) }
}
