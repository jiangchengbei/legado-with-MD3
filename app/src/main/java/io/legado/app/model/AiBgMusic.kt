package io.legado.app.model

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

object AiBgMusic {

    private const val KEY_ENABLED = "ai_bgm_enabled"
    private const val KEY_DIR = "ai_bgm_dir"
    private const val KEY_MODEL_URL = "ai_bgm_model_url"
    private const val KEY_MODEL_NAME = "ai_bgm_model_name"
    private const val KEY_MODEL_KEY = "ai_bgm_model_key"
    private const val KEY_MODEL_PROFILES = "ai_bgm_model_profiles"
    private const val KEY_SELECTED_MODEL_PROFILE = "ai_bgm_selected_model_profile"
    private const val KEY_PROMPTS = "ai_bgm_prompts"
    private const val KEY_PROMPT_PROFILES = "ai_bgm_prompt_profiles"
    private const val KEY_SELECTED_PROMPT = "ai_bgm_selected_prompt"
    private const val KEY_FREQUENCY = "ai_bgm_frequency"
    private const val KEY_SCENES_PER_MUSIC = "ai_bgm_scenes_per_music"
    private const val KEY_PLAYLIST = "ai_bgm_playlist"
    private const val KEY_VOLUME = "ai_bgm_volume"
    private const val KEY_PRELOAD_CHAPTERS = "ai_bgm_preload_chapters"
    private const val KEY_PRELOAD_WHOLE_BOOK = "ai_bgm_preload_whole_book"
    private const val KEY_PROMPT_MUSIC_CANDIDATE_LIMIT = "ai_bgm_prompt_music_candidate_limit"

    const val FREQUENCY_BOOK = 0
    const val FREQUENCY_CHAPTER = 1
    const val FREQUENCY_SCENE = 2

    data class Config(
        val enabled: Boolean = false,
        val musicDir: String = "",
        val modelUrl: String = "",
        val modelName: String = "",
        val modelKey: String = "",
        val prompts: String = defaultPrompt,
        val frequency: Int = FREQUENCY_SCENE,
        val scenesPerMusic: Int = 1,
        val volume: Int = 35,
        val preloadChapters: Int = 5,
        val preloadWholeBook: Boolean = false,
        val promptMusicCandidateLimit: Int = 250,
    )

    data class PlaylistItem(
        val bookName: String = "",
        val chapterTitle: String = "",
        val chapterIndex: Int,
        val sceneIndex: Int = 0,
        val unitType: String = "",
        val start: Int,
        val end: Int,
        val musicName: String = "",
        val musicUri: String = "",
        val reason: String = "",
        val mood: String = "",
        val sourceText: String = "",
        val status: String = STATUS_DONE,
        val statusMessage: String = "",
        val modeKey: String = "",
    )

    data class ChapterAnalysis(
        val bookName: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val status: String,
        val statusMessage: String,
        val items: List<PlaylistItem> = emptyList(),
        val modeKey: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class PromptProfile(
        val name: String,
        val prompt: String,
    )

    data class ModelProfile(
        val name: String,
        val provider: String = "",
        val modelUrl: String = "",
        val modelName: String = "",
        val modelKey: String = "",
    )

    private data class AiScene(
        val startText: String = "",
        val endText: String = "",
        val mood: String = "",
        val reason: String = "",
        val musicName: String = "",
    )

    private data class PlaylistBuildResult(
        val items: List<PlaylistItem>,
        val statusMessage: String,
        val usedFallback: Boolean = false,
    )

    private data class AiSceneRequestConfig(
        val compact: Boolean,
        val candidateLimit: Int,
        val chapterCharLimit: Int,
        val maxTokens: Int,
        val callTimeoutSeconds: Long,
        val readTimeoutSeconds: Long,
    )

    private data class AiChatContent(
        val content: String,
        val finishReason: String = "",
        val usedReasoningContent: Boolean = false,
    )

    private val defaultPrompt = """
        根据小说章节内容判断场景氛围，从本地背景音乐文件名中选择最合适的音乐。
        输出时优先匹配情绪、场景、节奏，例如紧张、战斗、安静、温柔、悲伤、悬疑、日常。
    """.trimIndent()

    private var mediaPlayer: MediaPlayer? = null
    private var fadingPlayer: MediaPlayer? = null
    private var crossfadeJob: Job? = null
    private var currentMusicUri: String? = null
    private var currentPlaylist: List<PlaylistItem> = emptyList()
    @Volatile
    private var manualPaused = false
    private var pendingAutoPlay = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analyzeJob: Job? = null
    private val analyzingChapterKeys = ConcurrentHashMap.newKeySet<String>()
    private val runtimeAnalyses = ConcurrentHashMap<String, ChapterAnalysis>()
    private val analysisQueueSeq = AtomicLong(0)
    @Volatile
    private var activeAnalysisWindow: AnalysisWindow? = null
    private var cachedMusicDir: String = ""
    private var cachedTracks: List<MusicTrack> = emptyList()

    const val STATUS_ANALYZING = "analyzing"
    const val STATUS_DONE = "done"
    const val STATUS_WAITING = "waiting"
    const val STATUS_FAILED = "failed"

    private data class AnalysisWindow(
        val bookUrl: String,
        val bookName: String,
        val startChapterIndex: Int,
        val endExclusive: Int,
        val queueId: Long,
        val modeKey: String,
    )

    var enabled: Boolean
        get() = appCtx.getPrefBoolean(KEY_ENABLED, false)
        set(value) {
            appCtx.putPrefBoolean(KEY_ENABLED, value)
            if (!value) stop()
            postEvent(EventBus.AI_BGM_CHANGED, value)
        }

    var musicDir: String
        get() = appCtx.getPrefString(KEY_DIR).orEmpty()
        set(value) {
            if (value != musicDir) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_DIR, value)
        }

    var modelUrl: String
        get() = appCtx.getPrefString(KEY_MODEL_URL).orEmpty()
        set(value) {
            if (value != modelUrl) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_MODEL_URL, value)
        }

    var modelName: String
        get() = appCtx.getPrefString(KEY_MODEL_NAME).orEmpty()
        set(value) {
            if (value != modelName) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_MODEL_NAME, value)
        }

    var modelKey: String
        get() = appCtx.getPrefString(KEY_MODEL_KEY).orEmpty()
        set(value) {
            if (value != modelKey) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_MODEL_KEY, value)
        }

    var prompts: String
        get() = selectedPromptProfile().prompt
        set(value) {
            val name = selectedPromptName()
            val profiles = promptProfiles().toMutableList()
            val index = profiles.indexOfFirst { it.name == name }
            val profile = PromptProfile(name, value.ifBlank { defaultPrompt })
            if (index >= 0) profiles[index] = profile else profiles.add(profile)
            savePromptProfiles(profiles)
            appCtx.putPrefString(KEY_PROMPTS, profile.prompt)
            invalidateRuntimePlaylist()
        }

    var frequency: Int
        get() = appCtx.getPrefInt(KEY_FREQUENCY, FREQUENCY_SCENE)
        set(value) {
            val newValue = value.coerceIn(FREQUENCY_BOOK, FREQUENCY_SCENE)
            if (newValue != frequency) invalidateRuntimePlaylist()
            appCtx.putPrefInt(KEY_FREQUENCY, newValue)
        }

    var scenesPerMusic: Int
        get() = appCtx.getPrefInt(KEY_SCENES_PER_MUSIC, 1)
        set(value) {
            val newValue = value.coerceIn(1, 10)
            if (newValue != scenesPerMusic) invalidateRuntimePlaylist()
            appCtx.putPrefInt(KEY_SCENES_PER_MUSIC, newValue)
        }

    var volume: Int
        get() = appCtx.getPrefInt(KEY_VOLUME, 35)
        set(value) {
            appCtx.putPrefInt(KEY_VOLUME, value.coerceIn(0, 100))
            updateVolume()
        }

    var preloadChapters: Int
        get() = appCtx.getPrefInt(KEY_PRELOAD_CHAPTERS, 5)
        set(value) = appCtx.putPrefInt(KEY_PRELOAD_CHAPTERS, value.coerceIn(1, 200))

    var preloadWholeBook: Boolean
        get() = appCtx.getPrefBoolean(KEY_PRELOAD_WHOLE_BOOK, false)
        set(value) = appCtx.putPrefBoolean(KEY_PRELOAD_WHOLE_BOOK, value)

    var promptMusicCandidateLimit: Int
        get() = appCtx.getPrefInt(KEY_PROMPT_MUSIC_CANDIDATE_LIMIT, 250)
        set(value) {
            val newValue = value.coerceIn(50, 500)
            if (newValue != promptMusicCandidateLimit) invalidateRuntimePlaylist()
            appCtx.putPrefInt(KEY_PROMPT_MUSIC_CANDIDATE_LIMIT, newValue)
        }

    fun config(): Config = Config(
        enabled = enabled,
        musicDir = musicDir,
        modelUrl = modelUrl,
        modelName = modelName,
        modelKey = modelKey,
        prompts = prompts,
        frequency = frequency,
        scenesPerMusic = scenesPerMusic,
        volume = volume,
        preloadChapters = preloadChapters,
        preloadWholeBook = preloadWholeBook,
        promptMusicCandidateLimit = promptMusicCandidateLimit,
    )

    fun save(config: Config) {
        enabled = config.enabled
        musicDir = config.musicDir
        modelUrl = config.modelUrl
        modelName = config.modelName
        modelKey = config.modelKey
        prompts = config.prompts
        frequency = config.frequency
        scenesPerMusic = config.scenesPerMusic
        volume = config.volume
        preloadChapters = config.preloadChapters
        preloadWholeBook = config.preloadWholeBook
        promptMusicCandidateLimit = config.promptMusicCandidateLimit
        updateVolume()
    }

    fun promptProfiles(): List<PromptProfile> {
        val json = appCtx.getPrefString(KEY_PROMPT_PROFILES).orEmpty()
        val profiles = runCatching {
            if (json.isBlank()) emptyList()
            else GSON.fromJson(json, Array<PromptProfile>::class.java).toList()
        }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() && it.prompt.isNotBlank() }
        return profiles.ifEmpty {
            listOf(PromptProfile("默认", appCtx.getPrefString(KEY_PROMPTS, defaultPrompt) ?: defaultPrompt))
        }
    }

    fun savePromptProfiles(profiles: List<PromptProfile>) {
        val normalized = profiles
            .filter { it.name.isNotBlank() && it.prompt.isNotBlank() }
            .distinctBy { it.name.trim() }
            .ifEmpty { listOf(PromptProfile("默认", defaultPrompt)) }
        appCtx.putPrefString(KEY_PROMPT_PROFILES, GSON.toJson(normalized))
        if (normalized.none { it.name == selectedPromptName() }) {
            selectedPromptName = normalized.first().name
        }
    }

    var selectedPromptName: String
        get() = selectedPromptName()
        set(value) {
            appCtx.putPrefString(KEY_SELECTED_PROMPT, value)
            appCtx.putPrefString(KEY_PROMPTS, selectedPromptProfile(value).prompt)
        }

    fun selectedPromptProfile(name: String = selectedPromptName()): PromptProfile {
        return promptProfiles().firstOrNull { it.name == name } ?: promptProfiles().first()
    }

    private fun selectedPromptName(): String {
        return appCtx.getPrefString(KEY_SELECTED_PROMPT).orEmpty().ifBlank {
            promptProfiles().first().name
        }
    }

    fun modelProfiles(): List<ModelProfile> {
        val json = appCtx.getPrefString(KEY_MODEL_PROFILES).orEmpty()
        val profiles = runCatching {
            if (json.isBlank()) emptyList()
            else GSON.fromJson(json, Array<ModelProfile>::class.java).toList()
        }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() && it.modelUrl.isNotBlank() && it.modelName.isNotBlank() }

        return profiles.ifEmpty {
            if (modelUrl.isNotBlank() || modelName.isNotBlank() || modelKey.isNotBlank()) {
                listOf(
                    ModelProfile(
                        name = "当前配置",
                        modelUrl = modelUrl,
                        modelName = modelName,
                        modelKey = modelKey,
                    )
                )
            } else {
                emptyList()
            }
        }
    }

    fun saveModelProfiles(profiles: List<ModelProfile>) {
        val normalized = profiles
            .map {
                it.copy(
                    name = it.name.trim(),
                    provider = it.provider.trim(),
                    modelUrl = it.modelUrl.trim(),
                    modelName = it.modelName.trim(),
                    modelKey = it.modelKey.trim(),
                )
            }
            .filter { it.name.isNotBlank() && it.modelUrl.isNotBlank() && it.modelName.isNotBlank() }
            .distinctBy { it.name }

        appCtx.putPrefString(KEY_MODEL_PROFILES, GSON.toJson(normalized))
        if (selectedModelProfileName.isNotBlank() && normalized.none { it.name == selectedModelProfileName }) {
            selectedModelProfileName = normalized.firstOrNull()?.name.orEmpty()
        }
    }

    var selectedModelProfileName: String
        get() = appCtx.getPrefString(KEY_SELECTED_MODEL_PROFILE).orEmpty()
        set(value) = appCtx.putPrefString(KEY_SELECTED_MODEL_PROFILE, value)

    fun selectedModelProfile(): ModelProfile? {
        return modelProfiles().firstOrNull { it.name == selectedModelProfileName }
    }

    fun selectModelProfile(profile: ModelProfile) {
        selectedModelProfileName = profile.name
        modelUrl = profile.modelUrl
        modelName = profile.modelName
        modelKey = profile.modelKey
    }

    fun upsertSelectedModelProfile(provider: String = "") {
        val selectedName = selectedModelProfileName
        if (selectedName.isBlank()) return

        val profiles = modelProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.name == selectedName }
        if (index < 0) return

        profiles[index] = profiles[index].copy(
            provider = provider.ifBlank { profiles[index].provider },
            modelUrl = modelUrl,
            modelName = modelName,
            modelKey = modelKey,
        )
        saveModelProfiles(profiles)
    }

    fun testModel(): Result<String> = runCatching {
        val url = modelUrl.trim()
        require(url.isNotBlank()) { "请先填写模型地址" }
        require(modelName.isNotBlank()) { "请先填写模型名" }
        val testUrl = normalizeChatCompletionsUrl(url)
        val body = GSON.toJson(
            mapOf(
                "model" to modelName.trim(),
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "ping")
                ),
                "temperature" to 0,
                "max_tokens" to 8
            )
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(testUrl)
            .apply {
                if (modelKey.isNotBlank()) {
                    header("Authorization", normalizeBearerToken(modelKey))
                }
            }
            .post(body)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                "模型连接正常 HTTP ${response.code}"
            } else {
                val message = response.body.string().take(300)
                "模型连接失败 HTTP ${response.code}\n$testUrl\n$message"
            }
        }
    }

    private fun normalizeChatCompletionsUrl(rawUrl: String): String {
        var clean = rawUrl.trim().trimEnd('/')
        if (clean.isBlank()) return clean

        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) {
            clean = "https://$clean"
        }

        val lower = clean.lowercase()

        return when {
            lower.endsWith("/chat/completions") -> clean
            lower.endsWith("/responses") -> clean
            lower.matches(Regex(".*/v\\d+(\\.\\d+)?$")) -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }

    private fun normalizeBearerToken(rawKey: String): String {
        val key = rawKey.trim()
        return if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
    }

    fun preparePlaylist(bookName: String, chapterIndex: Int, chapter: TextChapter?): List<PlaylistItem> {
        if (!enabled || chapter == null) return emptyList()
        chapterPlaylist(bookName, chapterIndex).takeIf { it.isNotEmpty() }?.let {
            currentPlaylist = it
            return it
        }
        markWaiting(
            bookName = bookName,
            chapterTitle = chapter.title,
            chapterIndex = chapterIndex,
            message = "等待整章正文完成后再生成背景音乐。"
        )
        return emptyList()
    }

    private fun buildPlaylist(
        bookName: String,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>
    ): List<PlaylistItem> {
        val units = splitUnits(chapterTitle, content)
        val selectedTracks = arrayListOf<MusicTrack>()
        return units.mapIndexed { index, unit ->
            val trackIndex = if (frequency == FREQUENCY_SCENE) index / scenesPerMusic else index
            val track = selectedTracks.getOrNull(trackIndex) ?: chooseTrack(tracks, unit.text, trackIndex)
                .also { selectedTracks.add(it) }
            val mood = detectMood(unit.text)
            PlaylistItem(
                bookName = bookName,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                sceneIndex = index + 1,
                unitType = frequencyLabel(frequency),
                start = unit.start,
                end = unit.end,
                musicName = track.name,
                musicUri = track.uri,
                reason = buildReason(unit.text, track.name, index, trackIndex),
                mood = mood,
                sourceText = unit.text.take(220),
                status = STATUS_DONE,
                statusMessage = "已匹配",
                modeKey = modeKey(),
            )
        }
    }

    fun onReadAloudState(play: Boolean, book: Book?, chapterIndex: Int, chapter: TextChapter?) {
        if (!enabled) return
        if (play) {
            manualPaused = false
            ensureAnalysis(book, chapterIndex, chapter, force = false)
            currentPlaylist = chapterPlaylist(book?.name.orEmpty(), chapterIndex)
            if (!manualPaused) {
                if (currentPlaylist.isEmpty()) {
                    pendingAutoPlay = true
                } else {
                    playForPosition(0)
                }
            }
        } else {
            manualPaused = true
            pause()
        }
    }

    fun ensureAnalysis(book: Book?, chapterIndex: Int, chapter: TextChapter?, force: Boolean = false) {
        if (book == null) return
        if (!enabled) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterIndex = chapterIndex,
                    status = STATUS_FAILED,
                    statusMessage = "智能背景音乐未开启，请先在设置中打开智能背景音乐总开关并保存。",
                    modeKey = modeKey(),
                )
            )
            return
        }
        val existing = chapterAnalysis(book.name, chapterIndex)
        if (force || existing?.status != STATUS_DONE || existing.modeKey != modeKey()) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterIndex = chapterIndex,
                    status = STATUS_ANALYZING,
                    statusMessage = "AI 背景音乐分析已触发，正在排队准备整章内容和音乐列表。",
                    modeKey = modeKey(),
                )
            )
        }
        val count = runCatching {
            if (preloadWholeBook) {
                (appDb.bookChapterDao.getChapterCount(book.bookUrl) - chapterIndex).coerceAtLeast(1)
            } else {
                preloadChapters + 1
            }
        }.getOrElse { e ->
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterIndex = chapterIndex,
                    status = STATUS_FAILED,
                    statusMessage = "读取章节数量失败：${e.localizedMessage.orEmpty()}",
                    modeKey = modeKey(),
                )
            )
            return
        }
        analyzeRange(book, chapterIndex, count, chapter, force)
    }

    fun analyzeRange(
        book: Book,
        startChapterIndex: Int,
        chapterCount: Int,
        currentChapter: TextChapter?,
        force: Boolean = false,
    ) {
        // 使用缓存避免每次 resume 时阻塞主线程扫描目录
        val tracks = getCachedMusicFiles()
        if (tracks.isEmpty()) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = currentChapter?.title.orEmpty(),
                    chapterIndex = startChapterIndex,
                    status = STATUS_FAILED,
                    statusMessage = "未找到背景音乐文件，请先选择背景音乐目录。",
                    modeKey = modeKey(),
                )
            )
            return
        }
        val chapterTotal = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        val safeStartChapterIndex = startChapterIndex.coerceAtLeast(0)
        val endExclusive = (safeStartChapterIndex + chapterCount.coerceAtLeast(1))
            .coerceAtMost(chapterTotal)
        val indices = if (endExclusive > safeStartChapterIndex) {
            safeStartChapterIndex until endExclusive
        } else {
            safeStartChapterIndex..safeStartChapterIndex
        }

        val currentModeKey = modeKey()
        val existingWindow = activeAnalysisWindow
        val reusableWindow = existingWindow?.takeIf {
            !force &&
                    analyzeJob?.isActive == true &&
                    it.bookUrl == book.bookUrl &&
                    it.startChapterIndex == safeStartChapterIndex &&
                    it.endExclusive == endExclusive &&
                    it.modeKey == currentModeKey
        }
        val queueId = if (reusableWindow != null) {
            reusableWindow.queueId
        } else {
            analyzeJob?.cancel()
            analyzingChapterKeys.clear()
            analysisQueueSeq.incrementAndGet().also { id ->
                activeAnalysisWindow = AnalysisWindow(
                    bookUrl = book.bookUrl,
                    bookName = book.name,
                    startChapterIndex = safeStartChapterIndex,
                    endExclusive = endExclusive,
                    queueId = id,
                    modeKey = currentModeKey,
                )
            }
        }

        indices.forEachIndexed { order, index ->
            val old = chapterAnalysis(book.name, index)
            if (!force && old?.status == STATUS_DONE && old.modeKey == currentModeKey) return@forEachIndexed
            saveQueueChapterAnalysis(
                queueId,
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = if (index == safeStartChapterIndex) currentChapter?.title.orEmpty() else "",
                    chapterIndex = index,
                    status = if (order == 0) STATUS_ANALYZING else STATUS_WAITING,
                    statusMessage = if (order == 0) {
                        "当前章优先分析：正在准备整章内容和音乐列表。"
                    } else {
                        "已进入当前缓存窗口，等待前面章节分析完成。"
                    },
                    modeKey = currentModeKey,
                )
            )
        }

        if (reusableWindow != null) return

        analyzeJob = scope.launch {
            // 首次分析时在 IO 线程扫描目录，不阻塞主线程
            val resolvedTracks = if (tracks.isEmpty()) {
                runCatching { listMusicFiles() }.getOrElse { emptyList() }
            } else tracks
            if (resolvedTracks.isEmpty()) {
                saveChapterAnalysis(
                    ChapterAnalysis(
                        bookName = book.name,
                        chapterTitle = currentChapter?.title.orEmpty(),
                        chapterIndex = startChapterIndex,
                        status = STATUS_FAILED,
                        statusMessage = "读取背景音乐目录失败",
                        modeKey = modeKey(),
                    )
                )
                return@launch
            }
            indices.forEach { index ->
                if (!isActiveQueue(queueId)) return@forEach
                val old = chapterAnalysis(book.name, index)
                if (!force && old?.status == STATUS_DONE && old.modeKey == currentModeKey) return@forEach

                val chapterKey = "${book.bookUrl}#$index"
                if (!analyzingChapterKeys.add(chapterKey)) return@forEach
                try {
                    saveQueueChapterAnalysis(
                        queueId,
                        ChapterAnalysis(
                            bookName = book.name,
                            chapterTitle = if (index == safeStartChapterIndex) currentChapter?.title.orEmpty() else "",
                            chapterIndex = index,
                            status = STATUS_ANALYZING,
                            statusMessage = if (index == safeStartChapterIndex) {
                                "当前章优先分析：后台任务已开始，正在读取章节正文。"
                            } else {
                                "后台分析任务已开始，正在读取章节正文。"
                            },
                            modeKey = currentModeKey,
                        )
                    )
                    analyzeChapter(book, index, if (index == safeStartChapterIndex) currentChapter else null, resolvedTracks, queueId)
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val message = e.localizedMessage.orEmpty().ifBlank { e::class.java.simpleName }
                    AppLog.putDebug("AI背景音乐：章节 ${index + 1} 分析异常：$message")
                    saveQueueChapterAnalysis(
                        queueId,
                        ChapterAnalysis(
                            bookName = book.name,
                            chapterTitle = if (index == safeStartChapterIndex) currentChapter?.title.orEmpty() else "",
                            chapterIndex = index,
                            status = STATUS_FAILED,
                            statusMessage = "后台分析异常：$message",
                            modeKey = currentModeKey,
                        )
                    )
                } finally {
                    analyzingChapterKeys.remove(chapterKey)
                }
            }
        }
    }

    fun onProgress(position: Int) {
        if (!enabled) return
        if (manualPaused) return
        playForPosition(position)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun toggleManualPlayback(book: Book?, chapterIndex: Int, chapter: TextChapter?, position: Int): Boolean {
        if (!enabled) return false
        if (mediaPlayer?.isPlaying == true) {
            manualPaused = true
            pause()
            return false
        }
        manualPaused = false
        ensureAnalysis(book, chapterIndex, chapter, force = false)
        currentPlaylist = chapterPlaylist(book?.name.orEmpty(), chapterIndex)
        if (currentPlaylist.isEmpty()) {
            postEvent(EventBus.AI_BGM_PLAY_STATE, false)
            return false
        }
        playForPosition(position)
        return true
    }


    private fun analyzeChapter(
        book: Book,
        chapterIndex: Int,
        currentChapter: TextChapter?,
        tracks: List<MusicTrack>,
        queueId: Long,
    ) {
        if (!isActiveQueue(queueId)) return
        val dbChapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
        val title = currentChapter?.title ?: dbChapter?.title.orEmpty()
        val candidate = chapterContentCandidate(book, dbChapter, currentChapter)
        val invalidReason = invalidContentReason(candidate.content)
        if (invalidReason != null) {
            val message = "等待有效整章正文后再分析。来源=${candidate.source}，文本长度=${candidate.content.length}，原因=$invalidReason"
            AppLog.putDebug("AI背景音乐：$message")
            markWaiting(book.name, title, chapterIndex, message, queueId)
            return
        }

        saveQueueChapterAnalysis(
            queueId,
            ChapterAnalysis(
                bookName = book.name,
                chapterTitle = title,
                chapterIndex = chapterIndex,
                status = STATUS_ANALYZING,
                statusMessage = "AI 正在根据整章内容分析场景音乐。来源=${candidate.source}，文本长度=${candidate.content.length}",
                modeKey = modeKey(),
            )
        )

        val result = buildPlaylistWithAi(book.name, chapterIndex, title, candidate.content, tracks)
        if (!isActiveQueue(queueId)) return
        if (result.items.isEmpty()) {
            saveQueueChapterAnalysis(
                queueId,
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = title,
                    chapterIndex = chapterIndex,
                    status = STATUS_WAITING,
                    statusMessage = result.statusMessage,
                    modeKey = modeKey(),
                )
            )
            return
        }

        saveQueueChapterAnalysis(
            queueId,
            ChapterAnalysis(
                bookName = book.name,
                chapterTitle = title,
                chapterIndex = chapterIndex,
                status = STATUS_DONE,
                statusMessage = result.statusMessage,
                items = result.items,
                modeKey = modeKey(),
            )
        )
        if (chapterIndex == ReadBook.durChapterIndex) {
            currentPlaylist = result.items
            if (pendingAutoPlay && !manualPaused) {
                pendingAutoPlay = false
                playForPosition(0)
            }
        }
    }

    private data class ContentCandidate(val source: String, val content: String)

    private fun chapterContentCandidate(
        book: Book,
        dbChapter: io.legado.app.data.entities.BookChapter?,
        currentChapter: TextChapter?
    ): ContentCandidate {
        val fromCurrent = normalizeChapterContent(currentChapter?.getContent().orEmpty())
        if (invalidContentReason(fromCurrent) == null) return ContentCandidate("当前整章排版文本", fromCurrent)

        val fromCache = normalizeChapterContent(
            if (dbChapter != null) BookHelp.getContent(book, dbChapter).orEmpty() else ""
        )
        if (invalidContentReason(fromCache) == null) return ContentCandidate("章节正文缓存", fromCache)

        return when {
            fromCurrent.isNotBlank() -> ContentCandidate("当前整章排版文本", fromCurrent)
            else -> ContentCandidate("章节正文缓存", fromCache)
        }
    }

    private fun normalizeChapterContent(content: String): String {
        return content
            .replace(Regex("\\[\\[(emo|emotion|bgm):[^\\]]+\\]\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[ \\t\\u000B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun invalidContentReason(content: String): String? {
        val compact = content.replace(Regex("\\s+"), "")
        if (compact.isBlank()) return "空文本"
        if (compact.length < 120) return "文本过短"
        if (compact.matches(Regex("^[\\p{Punct}\\p{IsPunctuation}，。！？；：、（）【】《》\u201C\u201D\u2018\u2019…—·]+$"))) return "纯标点"
        if (content.matches(Regex("^(\\s|<[^>]+>|\\[\\[[^\\]]+\\]\\])+$"))) return "纯标签"
        return null
    }

    private fun markWaiting(bookName: String, chapterTitle: String, chapterIndex: Int, message: String, queueId: Long? = null) {
        val analysis = ChapterAnalysis(
            bookName = bookName,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            status = STATUS_WAITING,
            statusMessage = message,
            modeKey = modeKey(),
        )
        if (queueId != null) {
            saveQueueChapterAnalysis(queueId, analysis)
        } else {
            saveChapterAnalysis(analysis)
        }
    }

    private fun isActiveQueue(queueId: Long): Boolean {
        return activeAnalysisWindow?.queueId == queueId
    }

    private fun saveQueueChapterAnalysis(queueId: Long, analysis: ChapterAnalysis) {
        if (isActiveQueue(queueId)) {
            saveChapterAnalysis(analysis)
        }
    }

    private fun emptyAnalysisDebug(title: String, bookName: String?): String {
        val trackCount = runCatching { listMusicFiles().size }.getOrElse { -1 }
        val dir = musicDir.trim().ifBlank { "未设置" }
        val url = modelUrl.trim().ifBlank { "未设置" }
        val model = modelName.trim().ifBlank { "未设置" }
        val storedJson = appCtx.getPrefString(KEY_PLAYLIST).orEmpty()
        val storedAnalyses = loadAnalyses()
        val storedSummary = storedAnalyses.takeLast(5).joinToString("；") {
            "${it.bookName.ifBlank { "未知书名" }}#${it.chapterIndex + 1}:${it.status}"
        }.ifBlank { "无" }
        return listOf(
            "$title 暂无记录，但已进入诊断。",
            "appVersion=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
            "bookName=${bookName.orEmpty().ifBlank { "未知" }}",
            "enabled=$enabled",
            "musicDir=$dir",
            "musicFileCount=$trackCount",
            "frequency=$frequency",
            "preloadChapters=$preloadChapters",
            "preloadWholeBook=$preloadWholeBook",
            "modelUrl=$url",
            "modelName=$model",
            "storedJsonLength=${storedJson.length}",
            "storedAnalysisCount=${storedAnalyses.size}",
            "storedRecent=$storedSummary",
            "提示：如果 enabled=false，请先打开智能背景音乐总开关并保存；如果 musicFileCount=0，请重新选择背景音乐目录。"
        ).joinToString("\n")
    }

    fun playlistText(bookName: String? = null): String {
        val currentKey = modeKey()
        val analyses = allAnalyses(bookName)
        if (analyses.isEmpty()) return emptyAnalysisDebug("背景音乐播放列表", bookName)

        // 检查是否有当前modeKey匹配的已完成分析
        val matchedAnalyses = analyses.filter { it.status == STATUS_DONE && it.modeKey == currentKey }
        val unmatchedCount = analyses.count { it.status == STATUS_DONE && it.modeKey != currentKey }

        val headerTip = when {
            unmatchedCount > 0 && matchedAnalyses.isEmpty() ->
                "⚠️ 检测到 $unmatchedCount 章旧配置记录（设置已变更），请点击「重新分析」刷新播放列表。\n\n"
            matchedAnalyses.isEmpty() -> ""
            else -> ""
        }

        // 只显示当前modeKey匹配的分析结果
        val content = if (matchedAnalyses.isNotEmpty()) {
            matchedAnalyses.joinToString("\n\n") { analysis ->
                val title = analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }
                val header = "$title：共 ${analysis.items.size} 个${analysis.items.firstOrNull()?.unitType.orEmpty().ifBlank { "场景" }}"
                val status = analysis.statusMessage.takeIf { it.isNotBlank() }?.let { "$it\n" }.orEmpty()
                val body = analysis.items.joinToString("\n") {
                    "场景 ${it.sceneIndex}｜${it.mood.orEmpty().ifBlank { "未识别氛围" }}｜${it.musicName.orEmpty().ifBlank { "未匹配音乐" }}"
                }
                "$header\n$status$body"
            }
        } else if (unmatchedCount > 0) {
            // 当前无匹配但有旧记录
            analyses.filter { it.status == STATUS_DONE }.joinToString("\n\n") { analysis ->
                "${analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}（旧配置）"
            }
        } else {
            analyses.joinToString("\n\n") { analysis ->
                when (analysis.status) {
                    STATUS_ANALYZING -> "${analysis.chapterTitle.ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}\n${analysis.statusMessage}"
                    STATUS_WAITING, STATUS_FAILED -> "${analysis.chapterTitle.ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}\n${analysis.statusMessage}"
                    else -> "${analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}：共 ${analysis.items.size} 个场景"
                }
            }
        }

        return headerTip + content
    }

    fun playlistDetailText(bookName: String? = null): String {
        val currentKey = modeKey()
        val analyses = allAnalyses(bookName)
        if (analyses.isEmpty()) return emptyAnalysisDebug("AI 分析详情", bookName)

        // 检查是否有当前modeKey匹配的已完成分析
        val matchedAnalyses = analyses.filter { it.status == STATUS_DONE && it.modeKey == currentKey }
        val unmatchedCount = analyses.count { it.status == STATUS_DONE && it.modeKey != currentKey }

        val headerTip = when {
            unmatchedCount > 0 && matchedAnalyses.isEmpty() ->
                "⚠️ 检测到 $unmatchedCount 章旧配置记录（设置已变更），请点击「重新分析」刷新。\n\n"
            matchedAnalyses.isEmpty() -> ""
            else -> ""
        }

        val content = if (matchedAnalyses.isNotEmpty()) {
            matchedAnalyses.joinToString("\n\n") { analysis ->
                if (analysis.items.isEmpty()) {
                    "${analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}\n${analysis.statusMessage.orEmpty()}"
                } else {
                    val items = analysis.items.joinToString("\n\n") {
                        "场景 ${it.sceneIndex}\n范围：${it.start}-${it.end}\n氛围：${it.mood.orEmpty().ifBlank { "未识别" }}\n音乐：${it.musicName.orEmpty().ifBlank { "未匹配音乐" }}\n理由：${it.reason.orEmpty().ifBlank { "暂无理由" }}\n片段：${it.sourceText.orEmpty()}"
                    }
                    "${analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}：共 ${analysis.items.size} 个场景\n$items"
                }
            }
        } else {
            analyses.joinToString("\n\n") { analysis ->
                "${analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}\n${analysis.statusMessage.orEmpty()}"
            }
        }

        return headerTip + content
    }

    fun chapterPlaylist(bookName: String, chapterIndex: Int): List<PlaylistItem> {
        val analysis = chapterAnalysis(bookName, chapterIndex)
        // 优先使用当前modeKey匹配的分析结果
        return if (analysis?.modeKey == modeKey() && analysis.status == STATUS_DONE) {
            analysis.items
        } else {
            emptyList()
        }
    }


    private fun safeSubstring(text: String, start: Int, end: Int): String {
        if (text.isEmpty()) return ""
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(0, text.length)
        return if (safeEnd > safeStart) {
            text.substring(safeStart, safeEnd)
        } else {
            ""
        }
    }

    /**
     * 返回缓存的音乐文件列表，目录变更时自动重新扫描
     */
    private fun getCachedMusicFiles(): List<MusicTrack> {
        val dir = musicDir.trim()
        if (dir != cachedMusicDir) {
            cachedMusicDir = dir
            cachedTracks = runCatching { listMusicFiles() }.getOrElse { emptyList() }
        }
        return cachedTracks
    }

    fun listMusicFiles(): List<MusicTrack> {
        val dir = musicDir.trim()
        if (dir.isBlank()) return emptyList()
        return if (dir.startsWith("content://")) {
            val root = DocumentFile.fromTreeUri(appCtx, Uri.parse(dir)) ?: return emptyList()

            fun collectMusicFiles(file: DocumentFile, out: MutableList<MusicTrack>) {
                file.listFiles().forEach { child ->
                    when {
                        child.isFile && child.name?.isMusicFile() == true -> {
                            out.add(MusicTrack(child.name.orEmpty(), child.uri.toString()))
                        }
                        child.isDirectory -> {
                            collectMusicFiles(child, out)
                        }
                    }
                }
            }

            val tracks = arrayListOf<MusicTrack>()
            collectMusicFiles(root, tracks)
            tracks.sortedBy { it.name }
        } else {
            File(dir).walkTopDown()
                .filter { it.isFile && it.name.isMusicFile() }
                .map { MusicTrack(it.name, Uri.fromFile(it).toString()) }
                .sortedBy { it.name }
                .toList()
        }
    }

    private fun playForPosition(position: Int) {
        val item = currentPlaylist.firstOrNull { position in it.start..max(it.start, it.end) }
            ?: currentPlaylist.firstOrNull()
            ?: return
        if (item.musicUri == currentMusicUri && mediaPlayer?.isPlaying == true) return
        playUri(item.musicUri)
    }

    private fun playUri(uri: String) {
        runCatching {
            crossfadeJob?.cancel()
            val oldPlayer = mediaPlayer
            fadingPlayer = oldPlayer
            currentMusicUri = uri
            mediaPlayer = MediaPlayer().apply {
                setDataSource(appCtx, Uri.parse(uri))
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener {
                    if (manualPaused || mediaPlayer !== it) {
                        runCatching { it.release() }
                        return@setOnPreparedListener
                    }
                    it.start()
                    crossfadeJob = scope.launch {
                        crossfade(oldPlayer, it)
                    }
                    postEvent(EventBus.AI_BGM_PLAY_STATE, true)
                }
                prepareAsync()
            }
        }
    }

    private suspend fun crossfade(oldPlayer: MediaPlayer?, newPlayer: MediaPlayer) {
        val fadeDuration = 2000L
        val steps = 40
        val stepMs = fadeDuration / steps
        val targetVolume = volume / 100f
        for (i in 0..steps) {
            val fraction = i.toFloat() / steps
            val fadeOut = targetVolume * (1f - fraction)
            val fadeIn = targetVolume * fraction
            runCatching { oldPlayer?.setVolume(fadeOut, fadeOut) }
            runCatching { newPlayer.setVolume(fadeIn, fadeIn) }
            delay(stepMs)
        }
        runCatching { oldPlayer?.release() }
        fadingPlayer = null
        runCatching { newPlayer.setVolume(targetVolume, targetVolume) }
    }

    private fun pause() {
        crossfadeJob?.cancel()
        runCatching { mediaPlayer?.pause() }
        postEvent(EventBus.AI_BGM_PLAY_STATE, false)
    }

    fun stop() {
        crossfadeJob?.cancel()
        analyzeJob?.cancel()
        analyzeJob = null
        pendingAutoPlay = false
        runCatching { fadingPlayer?.release() }
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        manualPaused = false
        fadingPlayer = null
        mediaPlayer = null
        currentMusicUri = null
        analyzingChapterKeys.clear()
        activeAnalysisWindow = null
        postEvent(EventBus.AI_BGM_PLAY_STATE, false)
    }

    private fun invalidateRuntimePlaylist(stopMusic: Boolean = true) {
        currentPlaylist = emptyList()
        if (stopMusic) {
            stop()
        } else {
            currentMusicUri = null
        }
    }

    private fun updateVolume() {
        val value = volume / 100f
        mediaPlayer?.setVolume(value, value)
    }

    private fun savePlaylist(list: List<PlaylistItem>) {
        val old = loadAnalyses().toMutableList()
        list.groupBy { it.bookName to it.chapterIndex }.forEach { (key, items) ->
            val index = old.indexOfFirst { it.bookName == key.first && it.chapterIndex == key.second }
            val analysis = ChapterAnalysis(
                bookName = key.first,
                chapterTitle = items.firstOrNull()?.chapterTitle.orEmpty(),
                chapterIndex = key.second,
                status = STATUS_DONE,
                statusMessage = "已完成，分成 ${items.size} 个播放单元。",
                items = items,
                modeKey = modeKey(),
            )
            if (index >= 0) old[index] = analysis else old.add(analysis)
            runtimeAnalyses[analysisKey(analysis.bookName, analysis.chapterIndex)] = analysis
        }
        saveAnalyses(old.sortedBy { it.chapterIndex })
        if (pendingAutoPlay && !manualPaused) {
            pendingAutoPlay = false
            list.groupBy { it.bookName to it.chapterIndex }.forEach { (key, items) ->
                currentPlaylist = items
                playForPosition(0)
            }
        }
    }

    private fun loadPlaylist(): List<PlaylistItem> {
        val key = modeKey()
        return loadAnalyses()
            .filter { it.status == STATUS_DONE && it.modeKey == key }
            .flatMap { it.items }
    }

    private fun saveChapterAnalysis(analysis: ChapterAnalysis) {
        val old = loadAnalyses().toMutableList()
        val index = old.indexOfFirst {
            sameBookName(it.bookName, analysis.bookName) && it.chapterIndex == analysis.chapterIndex
        }
        if (index >= 0) old[index] = analysis else old.add(analysis)
        runtimeAnalyses[analysisKey(analysis.bookName, analysis.chapterIndex)] = analysis
        saveAnalyses(old.sortedWith(compareBy({ it.bookName }, { it.chapterIndex })))
    }

    private fun chapterAnalysis(bookName: String, chapterIndex: Int): ChapterAnalysis? {
        return loadAnalyses().firstOrNull { sameBookName(it.bookName, bookName) && it.chapterIndex == chapterIndex }
    }

    private fun allAnalyses(bookName: String?): List<ChapterAnalysis> {
        val analyses = loadAnalyses()
        val window = activeAnalysisWindow?.takeIf { window ->
            bookName.isNullOrBlank() || sameBookName(window.bookName, bookName)
        }
        val source = if (window != null) {
            analyses.filter {
                sameBookName(it.bookName, window.bookName) &&
                        it.chapterIndex >= window.startChapterIndex &&
                        it.chapterIndex < window.endExclusive
            }.ifEmpty { analyses }
        } else {
            analyses
        }
        val filtered = source
            .filter { bookName.isNullOrBlank() || sameBookName(it.bookName, bookName) }
            .sortedBy { it.chapterIndex }
        return filtered.ifEmpty {
            if (bookName.isNullOrBlank()) emptyList() else source.sortedBy { it.chapterIndex }
        }
    }

    private fun loadAnalyses(): List<ChapterAnalysis> {
        val json = appCtx.getPrefString(KEY_PLAYLIST).orEmpty()
        val stored = if (json.isBlank()) emptyList() else runCatching {
            val array = JsonParser.parseString(json).asJsonArray
            val first = array.firstOrNull()?.asJsonObject
            val isLegacyPlaylistItems = first?.let {
                it.has("musicName") || it.has("musicUri") || it.has("sceneIndex") || (it.has("start") && it.has("end"))
            } == true
            if (isLegacyPlaylistItems) {
                array.mapNotNull { item ->
                    runCatching { GSON.fromJson(item, PlaylistItem::class.java).normalized() }.getOrNull()
                }.groupBy { it.bookName to it.chapterIndex }.map { (key, items) ->
                    ChapterAnalysis(
                        bookName = key.first,
                        chapterTitle = items.firstOrNull()?.chapterTitle.orEmpty(),
                        chapterIndex = key.second,
                        status = STATUS_DONE,
                        statusMessage = "已完成，分成 ${items.size} 个播放单元。",
                        items = items,
                        modeKey = items.firstOrNull()?.modeKey ?: modeKey(),
                    )
                }
            } else {
                array.mapNotNull { item -> jsonElementToChapterAnalysis(item) }
            }
        }.getOrElse { e ->
            AppLog.putDebug("AI背景音乐：播放列表记录读取失败，已进入诊断。${e.localizedMessage.orEmpty()}")
            emptyList()
        }
        if (runtimeAnalyses.isEmpty()) return stored
        val merged = stored.toMutableList()
        runtimeAnalyses.values.forEach { analysis ->
            val index = merged.indexOfFirst { sameBookName(it.bookName, analysis.bookName) && it.chapterIndex == analysis.chapterIndex }
            if (index >= 0) merged[index] = analysis else merged.add(analysis)
        }
        return merged.sortedWith(compareBy({ it.bookName }, { it.chapterIndex }))
    }

    private fun saveAnalyses(analyses: List<ChapterAnalysis>) {
        val json = GSON.toJson(analyses)
        val ok = appCtx.defaultSharedPreferences.edit()
            .putString(KEY_PLAYLIST, json)
            .commit()
        if (!ok) {
            AppLog.putDebug("AI背景音乐：播放列表记录写入 SharedPreferences 失败，已保留内存记录。")
        }
    }

    private fun jsonElementToChapterAnalysis(element: JsonElement): ChapterAnalysis? {
        return runCatching {
            val obj = element.asJsonObject
            val items = obj["items"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { item ->
                    runCatching { GSON.fromJson(item, PlaylistItem::class.java).normalized() }.getOrNull()
                }
                .orEmpty()
            ChapterAnalysis(
                bookName = obj.stringValue("bookName", "book", "name"),
                chapterTitle = obj.stringValue("chapterTitle", "title"),
                chapterIndex = obj.intValue("chapterIndex", "index"),
                status = obj.stringValue("status").ifBlank {
                    if (items.isEmpty()) STATUS_WAITING else STATUS_DONE
                },
                statusMessage = obj.stringValue("statusMessage", "message").ifBlank {
                    if (items.isEmpty()) "AI 还在分析，请稍后刷新。" else "已完成，分成 ${items.size} 个播放单元。"
                },
                items = items,
                modeKey = obj.stringValue("modeKey").ifBlank { items.firstOrNull()?.modeKey.orEmpty().ifBlank { modeKey() } },
                updatedAt = obj.longValue("updatedAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
            ).normalizedOrNull()
        }.getOrNull()
    }

    private fun com.google.gson.JsonObject.intValue(vararg keys: String): Int {
        keys.forEach { key ->
            val value = get(key)
            if (value != null && value.isJsonPrimitive) return runCatching { value.asInt }.getOrDefault(0)
        }
        return 0
    }

    private fun com.google.gson.JsonObject.longValue(vararg keys: String): Long {
        keys.forEach { key ->
            val value = get(key)
            if (value != null && value.isJsonPrimitive) return runCatching { value.asLong }.getOrDefault(0L)
        }
        return 0L
    }

    private fun analysisKey(bookName: String, chapterIndex: Int): String {
        return "${normalizeBookMatchKey(bookName)}#$chapterIndex"
    }

    private fun sameBookName(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        val ak = normalizeBookMatchKey(a)
        val bk = normalizeBookMatchKey(b)
        return ak == bk || ak.contains(bk) || bk.contains(ak)
    }

    private fun normalizeBookMatchKey(value: String): String {
        return value
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[()（）\\[\\]【】《》<>〈〉「」『』_-]+"), "")
            .trim()
    }

    private fun ChapterAnalysis.normalizedOrNull(): ChapterAnalysis? {
        if (bookName.isBlank() && chapterTitle.isBlank()) return null
        val safeItems = items.map { it.normalized() }
        val safeStatus = status.orEmpty().ifBlank {
            if (safeItems.isEmpty()) STATUS_WAITING else STATUS_DONE
        }
        return copy(
            status = safeStatus,
            chapterTitle = chapterTitle.orEmpty(),
            statusMessage = statusMessage.orEmpty().ifBlank {
                if (safeStatus == STATUS_DONE) "已完成，分成 ${safeItems.size} 个播放单元。"
                else "AI 还在分析，请你耐心等待，心急吃不了热豆腐"
            },
            items = safeItems,
            modeKey = modeKey.orEmpty().ifBlank { safeItems.firstOrNull()?.modeKey.orEmpty().ifBlank { modeKey() } },
        )
    }

    private fun PlaylistItem.normalized(): PlaylistItem {
        return copy(
            bookName = bookName.orEmpty(),
            chapterTitle = chapterTitle.orEmpty(),
            unitType = unitType.orEmpty(),
            musicName = musicName.orEmpty(),
            musicUri = musicUri.orEmpty(),
            reason = reason.orEmpty(),
            mood = mood.orEmpty(),
            sourceText = sourceText.orEmpty(),
            status = status.orEmpty().ifBlank { STATUS_DONE },
            statusMessage = statusMessage.orEmpty(),
            modeKey = modeKey.orEmpty(),
        )
    }

    private fun splitUnits(title: String, content: String): List<UnitText> {
        if (content.isBlank()) return listOf(UnitText(0, 0, title))
        return when (frequency) {
            FREQUENCY_BOOK -> listOf(UnitText(0, content.length, content.take(800)))
            FREQUENCY_CHAPTER -> listOf(UnitText(0, content.length, "$title\n${content.take(1200)}"))
            else -> splitScenes(content)
        }
    }

    private fun splitScenes(content: String): List<UnitText> {
        val paragraphs = content.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(UnitText(0, content.length, content.take(1200)))
        val units = arrayListOf<UnitText>()
        var cursor = 0
        var buffer = StringBuilder()
        var start = 0
        paragraphs.forEach { paragraph ->
            if (buffer.isEmpty()) start = cursor
            buffer.append(paragraph).append('\n')
            cursor += paragraph.length + 1
            if (buffer.length >= 900) {
                units.add(UnitText(start, cursor, buffer.toString()))
                buffer = StringBuilder()
            }
        }
        if (buffer.isNotEmpty()) units.add(UnitText(start, cursor, buffer.toString()))
        return units
    }

    private fun chooseTrack(tracks: List<MusicTrack>, text: String, index: Int): MusicTrack {
        val keyword = sceneKeywords.firstOrNull { text.contains(it.first) }?.second
        return tracks.firstOrNull { track ->
            keyword != null && track.name.contains(keyword, ignoreCase = true)
        } ?: tracks[index % tracks.size]
    }

    private fun buildReason(text: String, musicName: String, sceneIndex: Int, trackIndex: Int): String {
        val hit = sceneKeywords.firstOrNull { text.contains(it.first) }
        val sceneGroup = if (frequency == FREQUENCY_SCENE) {
            "第 ${sceneIndex + 1} 个场景，当前每 $scenesPerMusic 个场景共用一种音乐"
        } else {
            "第 ${trackIndex + 1} 个播放单元"
        }
        return if (hit != null) {
            "$sceneGroup；根据场景关键词「${hit.first}」匹配到 $musicName"
        } else {
            "$sceneGroup；按当前切换频率从音乐库轮换选择 $musicName"
        }
    }

    private fun buildPlaylistWithAi(
        bookName: String,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>
    ): PlaylistBuildResult {
        val aiResult = requestAiScenes(chapterTitle, content, tracks)
        val scenes = aiResult.getOrNull()
        if (scenes.isNullOrEmpty()) {
            val reason = aiResult.exceptionOrNull()?.localizedMessage
                ?: "模型没有返回可解析的 scenes JSON。"
            AppLog.putDebug("AI背景音乐：AI 场景分析失败，使用本地兜底生成播放列表。$reason")
            val fallback = buildPlaylist(bookName, chapterIndex, chapterTitle, content, tracks)
                .map {
                    it.copy(
                        reason = "AI 分析失败，已用本地规则兜底。${it.reason}",
                        statusMessage = "本地规则兜底"
                    )
                }
            return PlaylistBuildResult(
                items = fallback,
                statusMessage = if (fallback.isEmpty()) {
                    "AI 场景分析失败，且本地规则也无法生成播放列表：$reason"
                } else {
                    "AI 场景分析失败，已用本地规则按整章文本生成 ${fallback.size} 个播放单元。原因：$reason"
                },
                usedFallback = true,
            )
        }

        val usedTracks = arrayListOf<MusicTrack>()
        val playlist = scenes.mapIndexed { index, scene ->
            val start = scene.startText.takeIf { it.isNotBlank() }
                ?.let { content.indexOf(it).takeIf { pos -> pos >= 0 } }
                ?: ((content.length * index) / scenes.size)
            val end = scene.endText.takeIf { it.isNotBlank() }
                ?.let { content.indexOf(it, start.coerceAtLeast(0)).takeIf { pos -> pos >= 0 }?.plus(it.length) }
                ?: ((content.length * (index + 1)) / scenes.size)
            val musicGroup = if (frequency == FREQUENCY_SCENE) index / scenesPerMusic else index
            val track = usedTracks.getOrNull(musicGroup) ?: chooseAiTrack(tracks, scene, musicGroup)
                .also { usedTracks.add(it) }
            val safeEnd = if (end > start) end else ((content.length * (index + 1)) / scenes.size)
            PlaylistItem(
                bookName = bookName,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                sceneIndex = index + 1,
                unitType = frequencyLabel(frequency),
                start = start.coerceAtLeast(0),
                end = safeEnd.coerceAtLeast(start),
                musicName = track.name,
                musicUri = track.uri,
                reason = scene.reason.ifBlank { "AI 根据场景氛围「${scene.mood}」选择 ${track.name}" },
                mood = scene.mood.ifBlank { detectMood(safeSubstring(content, start, safeEnd)) },
                sourceText = safeSubstring(content, start, safeEnd).take(220),
                status = STATUS_DONE,
                statusMessage = "AI 已匹配",
                modeKey = modeKey(),
            )
        }
        return PlaylistBuildResult(
            items = playlist,
            statusMessage = "AI 已完成，按整章场景分成 ${playlist.size} 个播放单元。",
        )
    }

    private fun requestAiScenes(
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>
    ): Result<List<AiScene>> = runCatching {
        require(modelUrl.isNotBlank()) { "请先填写模型地址" }
        require(modelName.isNotBlank()) { "请先填写模型名" }

        val normalLimit = promptMusicCandidateLimit.coerceIn(50, 500)
        val attempts = listOf(
            AiSceneRequestConfig(
                compact = false,
                candidateLimit = normalLimit,
                chapterCharLimit = 4200,
                maxTokens = 3200,
                callTimeoutSeconds = 180,
                readTimeoutSeconds = 180,
            ),
            AiSceneRequestConfig(
                compact = true,
                candidateLimit = minOf(normalLimit, 120),
                chapterCharLimit = 3600,
                maxTokens = 2600,
                callTimeoutSeconds = 150,
                readTimeoutSeconds = 150,
            ),
            AiSceneRequestConfig(
                compact = true,
                candidateLimit = minOf(normalLimit, 60),
                chapterCharLimit = 3200,
                maxTokens = 2200,
                callTimeoutSeconds = 120,
                readTimeoutSeconds = 120,
            ),
            AiSceneRequestConfig(
                compact = true,
                candidateLimit = minOf(normalLimit, 35),
                chapterCharLimit = 2200,
                maxTokens = 1600,
                callTimeoutSeconds = 90,
                readTimeoutSeconds = 90,
            )
        )
        var lastFailure = "模型没有返回可解析的 scenes JSON。"
        attempts.forEachIndexed { attemptIndex, config ->
            val responseText = runCatching {
                executeAiSceneRequest(chapterTitle, content, tracks, config)
            }.getOrElse { e ->
                lastFailure = aiRequestFailureMessage(e, attemptIndex < attempts.lastIndex)
                AppLog.putDebug("AI背景音乐：${lastFailure}")
                return@forEachIndexed
            }
            val chatContent = extractChatContent(responseText)
            val scenes = parseAiScenes(chatContent.content)
                .filter { it.musicName.isNotBlank() || it.mood.isNotBlank() }
            if (scenes.isNotEmpty()) {
                return@runCatching scenes
            }

            lastFailure = aiSceneFailureMessage(chatContent, responseText, attemptIndex < attempts.lastIndex)
        }
        throw IllegalStateException(lastFailure)
    }

    private fun executeAiSceneRequest(
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>,
        config: AiSceneRequestConfig,
    ): String {
        val promptTracks = promptCandidateTracks(chapterTitle, content, tracks, config.candidateLimit)
        val trackNames = promptTracks.joinToString("\n") { "- ${it.name}" }
        val modeText = when (frequency) {
            FREQUENCY_BOOK -> "整本书一种基调音乐，当前章节只输出 1 个整体场景。"
            FREQUENCY_CHAPTER -> "每章一种背景音乐，当前章节只输出 1 个整体场景。"
            else -> "按剧情场景切分；当前设置为每 $scenesPerMusic 个场景共用一种音乐。"
        }
        val userRules = prompts.trim().take(if (config.compact) 700 else 1800)
        val outputLimit = when (frequency) {
            FREQUENCY_BOOK, FREQUENCY_CHAPTER -> 1
            else -> if (config.compact) 8 else 12
        }
        val userPrompt = if (config.compact) {
            """
                只输出 JSON 对象，不要 Markdown，不要解释，不要分析过程。
                JSON 格式：{"scenes":[{"startText":"","endText":"","mood":"","reason":"","musicName":""}]}
                scenes 数量：1 到 $outputLimit 个。
                musicName 必须完全复制候选文件名之一。
                startText/endText 尽量使用正文真实短句；不确定可留空。

                用户规则摘要：
                $userRules

                播放模式：$modeText
                章节标题：$chapterTitle
                候选音乐（从完整音乐库 ${tracks.size} 首中筛选 ${promptTracks.size} 首）：
                $trackNames

                正文：
                ${content.take(config.chapterCharLimit)}
            """.trimIndent()
        } else {
            """
                $userRules

                播放模式：$modeText
                章节标题：$chapterTitle

                本地背景音乐文件名候选（共 ${tracks.size} 首，已按章节内容筛选 ${promptTracks.size} 首）：
                $trackNames

                章节正文：
                ${content.take(config.chapterCharLimit)}

                任务：阅读整章正文，判断这一章有几个剧情场景；每个场景都要从"本地背景音乐文件名"中选择一首最贴合的音乐。
                要求：
                1. 只输出 JSON，不要 Markdown，不要解释，不要分析过程。
                2. JSON 必须是 {"scenes":[...]}。
                3. musicName 必须从上面的文件名中原样复制，不能自己编音乐名。
                4. startText/endText 用正文中真实出现的短句，作为这个场景的边界；找不到可留空。
                5. 至少输出 1 个场景，最多输出 $outputLimit 个场景。

                每项格式：
                {"startText":"场景开头短句","endText":"场景结尾短句","mood":"氛围标签","reason":"简短理由","musicName":"音乐文件名"}
            """.trimIndent()
        }
        val body = GSON.toJson(
            mapOf(
                "model" to modelName.trim(),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "你只返回可解析 JSON。不要输出思考过程、解释、Markdown 或额外文本。"),
                    mapOf("role" to "user", "content" to userPrompt)
                ),
                "temperature" to if (config.compact) 0 else 0.2,
                "max_tokens" to config.maxTokens
            )
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(normalizeChatCompletionsUrl(modelUrl))
            .apply {
                if (modelKey.isNotBlank()) header("Authorization", normalizeBearerToken(modelKey))
            }
            .post(body)
            .build()
        val aiClient = okHttpClient.newBuilder()
            .callTimeout(config.callTimeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        aiClient.newCall(request).execute().use { response ->
            val responseText = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "模型请求失败 HTTP ${response.code}，${responseText.take(500)}"
                )
            }
            return responseText
        }
    }

    private fun promptCandidateTracks(
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>,
        limitOverride: Int? = null,
    ): List<MusicTrack> {
        val limit = (limitOverride ?: promptMusicCandidateLimit).coerceIn(30, 500)
        if (tracks.size <= limit) return tracks

        val chapterText = listOf(
            chapterTitle,
            detectMood(content),
            content.take(2400),
            content.takeLast(1200),
        ).joinToString("_")

        val scored = tracks
            .map { it to scoreMusicTrack(it.name, chapterText) }
            .sortedWith(compareByDescending<Pair<MusicTrack, Int>> { it.second }.thenBy { it.first.name })

        val matched = scored
            .filter { it.second > 0 }
            .map { it.first }
            .take((limit * 0.85f).toInt().coerceAtLeast(1))

        val fill = tracks
            .filterNot { track -> matched.any { it.name == track.name } }
            .take(limit - matched.size)

        return (matched + fill).take(limit)
    }

    private fun parseAiScenes(contentText: String): List<AiScene> {
        jsonCandidates(contentText).forEach { candidate ->
            runCatching {
                return jsonElementToScenes(JsonParser.parseString(candidate))
            }
        }
        return emptyList()
    }

    private fun jsonCandidates(raw: String): List<String> {
        val clean = raw
            .replace("```json", "```", ignoreCase = true)
            .trim()
        val list = arrayListOf<String>()
        if ("```" in clean) {
            clean.split("```")
                .map { it.trim() }
                .filter { it.startsWith("[") || it.startsWith("{") }
                .forEach { list.add(it) }
        }
        list.add(clean)
        val arrayStart = clean.indexOf('[')
        val arrayEnd = clean.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            list.add(clean.substring(arrayStart, arrayEnd + 1))
        }
        val objectStart = clean.indexOf('{')
        val objectEnd = clean.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            list.add(clean.substring(objectStart, objectEnd + 1))
        }
        return list.distinct()
    }

    private fun jsonElementToScenes(element: JsonElement): List<AiScene> {
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj["scenes"]?.isJsonArray == true -> obj["scenes"].asJsonArray
                    obj["items"]?.isJsonArray == true -> obj["items"].asJsonArray
                    obj["data"]?.isJsonArray == true -> obj["data"].asJsonArray
                    else -> JsonArray().apply { add(element) }
                }
            }
            else -> JsonArray()
        }
        return array.mapNotNull { item ->
            runCatching {
                val obj = item.asJsonObject
                AiScene(
                    startText = obj.stringValue("startText", "start_text", "start", "begin", "开头", "起始文本", "场景开头"),
                    endText = obj.stringValue("endText", "end_text", "end", "结尾", "结束文本", "场景结尾"),
                    mood = obj.stringValue("mood", "emotion", "atmosphere", "氛围", "情绪", "场景氛围"),
                    reason = obj.stringValue("reason", "why", "理由", "选择理由"),
                    musicName = obj.stringValue(
                        "musicName",
                        "music_name",
                        "musicFile",
                        "music_file",
                        "music",
                        "track",
                        "bgm",
                        "backgroundMusic",
                        "背景音乐",
                        "音乐"
                    )
                )
            }.getOrNull()
        }
    }

    private fun com.google.gson.JsonObject.stringValue(vararg keys: String): String {
        keys.forEach { key ->
            val value = get(key)
            if (value != null && value.isJsonPrimitive) return value.asString.orEmpty()
        }
        return ""
    }

    private fun aiSceneFailureMessage(
        chatContent: AiChatContent,
        responseText: String,
        willRetry: Boolean,
    ): String {
        val reason = when {
            chatContent.finishReason.equals("length", ignoreCase = true) ->
                "模型输出被截断（finish_reason=length），正式 JSON 没有生成完整"
            chatContent.content.isBlank() ->
                "模型 message.content 为空"
            chatContent.usedReasoningContent ->
                "模型把内容放进 reasoning_content，但里面没有可解析的场景 JSON"
            else ->
                "模型返回内容无法解析为场景 JSON"
        }
        val preview = chatContent.content.ifBlank { responseText }
            .replace(Regex("\\s+"), " ")
            .take(260)
        val retryText = if (willRetry) "，正在自动使用紧凑请求重试" else ""
        return "$reason$retryText。响应预览：$preview"
    }

    private fun aiRequestFailureMessage(e: Throwable, willRetry: Boolean): String {
        val raw = e.localizedMessage.orEmpty().ifBlank { e::class.java.simpleName }
        val lower = raw.lowercase()
        val reason = when {
            "timeout" in lower || "timed out" in lower ->
                "模型请求超时"
            "canceled" in lower || "cancelled" in lower ->
                "模型请求被取消"
            else ->
                "模型请求失败：$raw"
        }
        val retryText = if (willRetry) "，正在自动降低正文长度和音乐候选数量重试" else ""
        return "$reason$retryText"
    }

    private fun extractChatContent(responseText: String): AiChatContent {
        return runCatching {
            val choice = JsonParser.parseString(responseText)
                .asJsonObject["choices"].asJsonArray[0]
                .asJsonObject
            val finishReason = choice.stringValue("finish_reason")
            val message = choice["message"]?.asJsonObject
            val content = message?.stringValue("content").orEmpty()
            val reasoningContent = message?.stringValue("reasoning_content").orEmpty()
            if (content.isNotBlank()) {
                AiChatContent(content = content, finishReason = finishReason)
            } else {
                AiChatContent(
                    content = reasoningContent,
                    finishReason = finishReason,
                    usedReasoningContent = reasoningContent.isNotBlank(),
                )
            }
        }.getOrDefault(AiChatContent(content = responseText))
    }


    private fun musicFileNameOnly(value: String): String {
        return value.substringAfterLast('/').substringAfterLast('\\').trim()
    }

    private fun stripMusicExtension(value: String): String {
        return musicFileNameOnly(value)
            .replace(Regex("\\.(mp3|wav|m4a|aac|ogg|flac)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun normalizeMusicMatchKey(value: String): String {
        return stripMusicExtension(value)
            .lowercase()
            .replace(Regex("[\\s\\u3000_\\-·.()（）\\[\\]【】]+"), "")
            .trim()
    }

    private fun extractMusicTags(value: String): List<String> {
        return stripMusicExtension(value)
            .split("_", " ", "　", "-", "·", "/", "\\")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.matches(Regex("^\\d+$")) }
            .filterNot { it.equals("mp3", true) || it.equals("wav", true) || it.equals("m4a", true) || it.equals("aac", true) || it.equals("ogg", true) || it.equals("flac", true) }
            .distinct()
    }

    private fun findRequestedMusicTrack(tracks: List<MusicTrack>, requested: String): MusicTrack? {
        val raw = requested.trim()
        if (raw.isBlank()) return null

        tracks.firstOrNull { it.name == raw || it.uri == raw }?.let { return it }

        val rawFile = musicFileNameOnly(raw)
        tracks.firstOrNull { it.name == rawFile }?.let { return it }

        val key = normalizeMusicMatchKey(raw)
        if (key.isBlank()) return null

        tracks.firstOrNull { normalizeMusicMatchKey(it.name) == key }?.let { return it }

        return tracks.firstOrNull {
            val trackKey = normalizeMusicMatchKey(it.name)
            trackKey.isNotBlank() && (trackKey.contains(key) || key.contains(trackKey))
        }
    }

    private fun scoreMusicTrack(trackName: String, sceneText: String): Int {
        val trackKey = normalizeMusicMatchKey(trackName)
        val sceneKey = normalizeMusicMatchKey(sceneText)
        if (trackKey.isBlank() || sceneKey.isBlank()) return 0

        var score = 0

        if (trackKey == sceneKey) score += 1000
        if (trackKey.contains(sceneKey) || sceneKey.contains(trackKey)) score += 300

        val trackTags = extractMusicTags(trackName)
        val sceneTags = extractMusicTags(sceneText)

        trackTags.forEach { tag ->
            val tagKey = normalizeMusicMatchKey(tag)
            if (tagKey.isBlank()) return@forEach

            when {
                sceneTags.any { normalizeMusicMatchKey(it) == tagKey } -> {
                    score += 20 + tagKey.length.coerceAtMost(8)
                }
                sceneKey.contains(tagKey) -> {
                    score += 8 + tagKey.length.coerceAtMost(6)
                }
            }
        }

        val trackMain = stripMusicExtension(trackName)
        val sceneMain = stripMusicExtension(sceneText)
        val commonPrefixLen = trackMain.zip(sceneMain)
            .takeWhile { it.first == it.second }
            .size

        if (commonPrefixLen >= 2) {
            score += commonPrefixLen.coerceAtMost(20)
        }

        return score
    }

    private fun chooseAiTrack(tracks: List<MusicTrack>, scene: AiScene, musicGroup: Int): MusicTrack {
        if (tracks.isEmpty()) return MusicTrack("", "")

        val requested = scene.musicName.trim()
        findRequestedMusicTrack(tracks, requested)?.let { return it }

        if (requested.isNotBlank()) {
            AppLog.putDebug("AI背景音乐：模型返回的音乐名未精确命中，改用宽松标签匹配：$requested")
        }

        val sceneText = listOf(
            scene.musicName,
            scene.mood,
            scene.reason,
            scene.startText,
            scene.endText
        ).joinToString("_")

        val best = tracks
            .map { it to scoreMusicTrack(it.name, sceneText) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

        if (best != null) return best

        return tracks.getOrElse(musicGroup.mod(tracks.size)) { tracks.first() }
    }

    private fun detectMood(text: String): String {
        val hits = sceneKeywords
            .filter { text.contains(it.first) }
            .map { it.second }
            .distinct()
            .take(3)
        return hits.joinToString("、").ifBlank {
            when {
                text.contains("？") || text.contains("?") -> "对话、疑问"
                text.contains("！") || text.contains("!") -> "情绪、强调"
                else -> "日常、叙事"
            }
        }
    }

    private fun modeKey(): String {
        val safeUrl = runCatching { normalizeChatCompletionsUrl(modelUrl) }.getOrElse { modelUrl.trim() }
        return listOf(
            "frequency=$frequency",
            "scenesPerMusic=$scenesPerMusic",
            "musicDir=${musicDir.trim()}",
            "prompt=${selectedPromptName()}",
            "promptHash=${prompts.hashCode()}",
            "modelUrl=$safeUrl",
            "modelName=${modelName.trim()}",
        ).joinToString("|")
    }

    private fun frequencyLabel(value: Int): String = when (value) {
        FREQUENCY_BOOK -> "整本书"
        FREQUENCY_CHAPTER -> "章节"
        else -> "场景"
    }

    private fun String.isMusicFile(): Boolean {
        return endsWith(".mp3", true) ||
            endsWith(".m4a", true) ||
            endsWith(".aac", true) ||
            endsWith(".wav", true) ||
            endsWith(".ogg", true) ||
            endsWith(".flac", true)
    }

    data class MusicTrack(val name: String, val uri: String)
    private data class UnitText(val start: Int, val end: Int, val text: String)

    private val sceneKeywords = listOf(
        "战" to "战",
        "杀" to "战",
        "剑" to "战",
        "血" to "紧张",
        "逃" to "紧张",
        "惊" to "悬疑",
        "夜" to "夜",
        "雨" to "雨",
        "哭" to "悲",
        "泪" to "悲",
        "笑" to "轻松",
        "温柔" to "温柔",
        "安静" to "安静",
    )
}
