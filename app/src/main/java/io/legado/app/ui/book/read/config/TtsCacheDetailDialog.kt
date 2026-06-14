package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.help.TtsCacheManager
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.themeColor
import io.legado.app.utils.putPrefInt
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 音频缓存详情对话框 - 按书本分组显示缓存，
 * 展示每本书的章节缓存进度，支持选择、删除和合并。
 *
 * 功能：
 * 1. 章节详情展开后支持单选/多选
 * 2. 支持删除选中章节缓存
 * 3. 支持将单章所有音频合并为一个完整音频，以章节名称命名
 *
 * 性能优化：
 * 1. 使用 TtsCacheManager 单例维护常驻内存的 MD5 索引和分组数据。
 * 2. 打开弹窗时直接渲染上次数据，后台增量刷新，无需等待。
 * 3. 删除缓存只做增量移除，不重建全部索引。
 * 4. 监听 TTS_CACHE_PROGRESS 事件实时更新缓存数量。
 */
class TtsCacheDetailDialog : DialogFragment() {

    companion object {
        fun invalidateCache() {
            TtsCacheManager.clear()
        }
    }

    private var cacheListContainer: LinearLayout? = null
    private var loadingIndicator: View? = null
    private var tvCacheProgress: TextView? = null
    private var tvCacheSummary: TextView? = null

    // 选择状态管理
    private val selectedChapterMd5s = mutableSetOf<String>()
    // 记录每个 group 的展开状态（bookUrl -> expanded）
    private val expandedGroups = mutableMapOf<String, Boolean>()

    /**
     * 书本分组缓存信息
     */
    data class CacheGroup(
        val bookName: String,
        val bookUrl: String = "",
        val chapterCount: Int,
        val totalChapterCount: Int = 0,
        val fileCount: Int,
        val totalSize: Long,
        val titleMd5Set: Set<String>,
        val chapterDetail: List<ChapterCacheInfo> = emptyList()
    )

    data class ChapterCacheInfo(
        val chapterTitle: String,
        val titleMd5: String,
        val fileCount: Int,
        val size: Long,
        val chapterIndex: Int = 0
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireActivity(), R.style.dialog_style)
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_bottom_sheet_dialog)
            setWindowAnimations(R.style.dialog_style)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
            attributes = attributes?.apply {
                verticalMargin = 0f
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_tts_cache_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cacheListContainer = view.findViewById(R.id.cache_list_container)
        loadingIndicator = view.findViewById(R.id.loading_container)
        tvCacheProgress = view.findViewById(R.id.tv_cache_progress)
        tvCacheSummary = view.findViewById(R.id.tv_cache_summary)
        val tvCacheTitle = view.findViewById<TextView>(R.id.tv_cache_title)

        tvCacheTitle.setOnClickListener {
            val dir = getTtsCacheDir()
            toastOnUi("缓存路径：${dir?.absolutePath ?: "无"}")
        }

        view.findViewById<MaterialButton>(R.id.btn_preload_num).setOnClickListener {
            showPreloadNumPicker()
        }
        view.findViewById<MaterialButton>(R.id.btn_retention_time).setOnClickListener {
            showRetentionTimePicker()
        }

        view.findViewById<MaterialButton>(R.id.btn_clear_all).setOnClickListener {
            clearAllCache()
        }

        view.findViewById<MaterialButton>(R.id.btn_start_cache).setOnClickListener {
            startCacheCurrentBook()
        }

        updatePreloadRetentionButtons()
        loadCacheData()
        observeCacheProgress()
        scheduleSizeCalibration()
    }

    /**
     * 监听实时缓存进度事件
     */
    private fun observeCacheProgress() {
        observeEvent<TtsCacheProgress>(EventBus.TTS_CACHE_PROGRESS) { progress ->
            updateProgressText(progress)
            // 如果正在下载的是当前已显示的书籍，增量刷新对应条目
            if (progress.bookUrl.isNotBlank()) {
                incrementalUpdateForBook(progress.bookUrl, progress.titleMd5)
            }
        }
    }

    private fun updateProgressText(progress: TtsCacheProgress) {
        tvCacheProgress?.apply {
            visibility = View.VISIBLE
            text = if (progress.total > 0) {
                "正在缓存 ${progress.bookName}：第 ${progress.current}/${progress.total} 章"
            } else {
                "正在缓存 ${progress.bookName}"
            }
        }
    }

    /**
     * 后台 IO 协程校准缓存文件实际大小与快照标注大小的差异。
     * 仅在有快照数据时执行一次，不轮询不阻塞 UI。
     */
    private fun scheduleSizeCalibration() {
        if (TtsCacheManager.fileSnapshot.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (TtsCacheManager.calibrateSizes()) {
                withContext(Dispatchers.Main) {
                    if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext
                    val groups = TtsCacheManager.lastGroups
                    if (groups.isNotEmpty()) {
                        renderGroups(cacheListContainer!!, groups)
                    }
                }
            }
        }
    }

    /**
     * 开始缓存当前书籍
     */
    private fun startCacheCurrentBook() {
        val book = io.legado.app.model.ReadBook.book ?: run {
            toastOnUi("无法获取当前书籍信息")
            return
        }
        val preloadNum = io.legado.app.help.config.AppConfig.audioPreDownloadNum
        if (preloadNum <= 0) {
            toastOnUi("预加载数量为 0，请先设置预加载数量")
            return
        }

        val startIndex = book.durChapterIndex
        val endIndex = minOf(book.totalChapterNum - 1, startIndex + preloadNum)
        val chapterCount = endIndex - startIndex + 1

        try {
            val context = requireContext()
            io.legado.app.model.ReadAloud.startCache(context, startIndex, endIndex)
            toastOnUi("开始缓存 第${startIndex + 1}-${endIndex + 1} 章（共${chapterCount}章）")
        } catch (e: Exception) {
            toastOnUi("开始缓存失败：${e.localizedMessage}")
        }
    }

    /**
     * 加载缓存数据：
     * 1. 若 TtsCacheManager 已有分组数据，直接渲染，不显示 loading。
     * 2. 若没有分组数据（首次或刚清空），先快速扫描文件不等待索引构建，同时后台构建索引。
     */
    private fun loadCacheData() {
        val container = cacheListContainer ?: return
        val lastGroups = TtsCacheManager.lastGroups

        if (lastGroups.isNotEmpty()) {
            // 直接显示已有数据，不显示 loading
            loadingIndicator?.visibility = View.GONE
            container.visibility = View.VISIBLE
            renderGroups(container, lastGroups)
            // 后台增量刷新
            refreshInBackground()
        } else {
            // 没有缓存数据：快速扫描文件先渲染（即使索引未构建），不阻塞 UI
            container.removeAllViews()
            loadingIndicator?.visibility = View.VISIBLE
            container.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val mp3Files = TtsCacheManager.scanMp3Files()
                if (mp3Files.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                        loadingIndicator?.visibility = View.GONE
                        container.visibility = View.VISIBLE
                        renderGroups(cacheListContainer!!, emptyList())
                    }
                    return@launch
                }
                // 如果索引尚未构建，触发后台构建（不等待）
                if (!TtsCacheManager.indexBuilt && !TtsCacheManager.isBuilding) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        TtsCacheManager.buildFullIndex()
                        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@launch
                        val updatedFiles = TtsCacheManager.scanMp3Files()
                        val updatedGroups = TtsCacheManager.buildGroupsFromFiles(updatedFiles)
                        TtsCacheManager.updateSnapshot(updatedFiles, updatedGroups)
                        withContext(Dispatchers.Main) {
                            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                            renderGroups(cacheListContainer!!, updatedGroups)
                        }
                    }
                }
                // 先用当前索引（可能部分构建）快速渲染
                val groups = collectCacheGroups()
                TtsCacheManager.updateSnapshot(mp3Files, groups)
                withContext(Dispatchers.Main) {
                    if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                    loadingIndicator?.visibility = View.GONE
                    container.visibility = View.VISIBLE
                    renderGroups(cacheListContainer!!, groups)
                }
            }
        }
    }

    private fun refreshInBackground() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val groups = collectCacheGroups()
            TtsCacheManager.updateSnapshot(TtsCacheManager.scanMp3Files(), groups)
            withContext(Dispatchers.Main) {
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                loadingIndicator?.visibility = View.GONE
                cacheListContainer?.visibility = View.VISIBLE
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    /**
     * 收集缓存分组：优先使用 TtsCacheManager 的常驻索引，支持增量更新。
     */
    private fun collectCacheGroups(): List<CacheGroup> {
        val mp3Files = TtsCacheManager.scanMp3Files()
        if (mp3Files.isEmpty()) return emptyList()

        val (addedFiles, _) = TtsCacheManager.diffFiles(mp3Files)

        // 对于新增的文件，如果其 titleMd5 不在索引中，尝试通过当前阅读书籍快速补全
        if (addedFiles.isNotEmpty()) {
            val currentBook = io.legado.app.model.ReadBook.book
            val currentChapter = io.legado.app.model.ReadBook.curTextChapter?.chapter
            if (currentBook != null && currentChapter != null) {
                for (fileName in addedFiles) {
                    val nameWithoutExt = File(fileName).nameWithoutExtension
                    if (nameWithoutExt == "silent") continue
                    val underscoreIdx = nameWithoutExt.indexOf('_')
                    if (underscoreIdx <= 0) continue
                    val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                    if (TtsCacheManager.getMd5Index().containsKey(titleMd5)) continue
                    // 尝试匹配当前章节
                    val currentMd5 = MD5Utils.md5Encode16(currentChapter.title.trim())
                    if (titleMd5 == currentMd5) {
                        TtsCacheManager.addEntry(
                            titleMd5,
                            currentBook.name,
                            currentBook.bookUrl,
                            currentChapter.title,
                            currentChapter.index
                        )
                    }
                }
            }
        }

        return TtsCacheManager.buildGroupsFromFiles(mp3Files)
    }

    /**
     * 增量更新某一本书的缓存显示（用于实时进度）。
     * 当收到缓存进度事件时，只重新扫描该 bookUrl 对应的文件，更新对应 UI 条目。
     */
    private fun incrementalUpdateForBook(bookUrl: String, titleMd5: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // 确保该 titleMd5 已在索引中
            if (!TtsCacheManager.getMd5Index().containsKey(titleMd5)) {
                val currentBook = io.legado.app.model.ReadBook.book
                val currentChapter = io.legado.app.model.ReadBook.curTextChapter?.chapter
                if (currentBook != null && currentChapter != null) {
                    val md5 = MD5Utils.md5Encode16(currentChapter.title.trim())
                    if (md5 == titleMd5) {
                        TtsCacheManager.addEntry(
                            titleMd5,
                            currentBook.name,
                            currentBook.bookUrl,
                            currentChapter.title,
                            currentChapter.index
                        )
                    }
                }
            }

            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = TtsCacheManager.buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    private fun renderGroups(container: LinearLayout, groups: List<CacheGroup>) {
        container.removeAllViews()
        updateCacheStats(groups)

        if (groups.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "暂无缓存数据"
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 48)
                setTextColor(
                    requireContext().themeColor(
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                textSize = 14f
            }
            container.addView(tv)
            return
        }

        for (group in groups) {
            if (container.childCount > 0) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        setMargins(16, 0, 16, 0)
                    }
                    setBackgroundColor(
                        requireContext().themeColor(
                            com.google.android.material.R.attr.colorOutlineVariant
                        )
                    )
                }
                container.addView(divider)
            }

            val bookHeader = layoutInflater.inflate(R.layout.item_tts_cache_book, container, false)
            val groupKey = group.bookUrl.ifBlank { group.bookName }

            bookHeader.findViewById<TextView>(R.id.tv_book_name).text = group.bookName
            val progressText = if (group.totalChapterCount > 0) {
                "已缓存 ${group.chapterCount}/${group.totalChapterCount} 章"
            } else {
                "已缓存 ${group.chapterCount} 章"
            }
            bookHeader.findViewById<TextView>(R.id.tv_chapter_progress).text = progressText
            bookHeader.findViewById<TextView>(R.id.tv_file_count).text = "${group.fileCount} 个音频"
            bookHeader.findViewById<TextView>(R.id.tv_size).text = formatFileSize(group.totalSize)

            val expandBtn = bookHeader.findViewById<MaterialButton>(R.id.btn_expand)
            val chapterContainer = bookHeader.findViewById<LinearLayout>(R.id.chapter_container)
            val actionBar = bookHeader.findViewById<LinearLayout>(R.id.chapter_action_bar)

            val isExpanded = expandedGroups[groupKey] == true
            if (isExpanded) {
                expandBtn.text = "收起章节详情 ▴"
                renderChapterDetails(chapterContainer, group, actionBar)
                chapterContainer.visibility = View.VISIBLE
                updateActionBarVisibility(actionBar, group)
            } else {
                expandBtn.text = "展开章节详情 ▾"
                chapterContainer.visibility = View.GONE
                actionBar.visibility = View.GONE
            }

            expandBtn.setOnClickListener {
                val nowExpanded = expandedGroups[groupKey] != true
                expandedGroups[groupKey] = nowExpanded
                if (nowExpanded) {
                    expandBtn.text = "收起章节详情 ▴"
                    renderChapterDetails(chapterContainer, group, actionBar)
                    chapterContainer.visibility = View.VISIBLE
                    updateActionBarVisibility(actionBar, group)
                } else {
                    expandBtn.text = "展开章节详情 ▾"
                    chapterContainer.visibility = View.GONE
                    actionBar.visibility = View.GONE
                }
            }

            bookHeader.findViewById<MaterialButton>(R.id.btn_clear_book).setOnClickListener {
                clearGroup(group = group)
            }

            container.addView(bookHeader)
        }
    }

    private fun updateActionBarVisibility(actionBar: LinearLayout, group: CacheGroup) {
        val hasSelection = group.chapterDetail.any { it.titleMd5 in selectedChapterMd5s }
        actionBar.visibility = View.VISIBLE
        updateActionButtonsState(actionBar, group)
    }

    private fun updateActionButtonsState(actionBar: LinearLayout, group: CacheGroup) {
        val selectAllBtn = actionBar.findViewById<MaterialButton>(R.id.btn_select_all)
        val deleteBtn = actionBar.findViewById<MaterialButton>(R.id.btn_delete_selected)
        val mergeBtn = actionBar.findViewById<MaterialButton>(R.id.btn_merge_selected)

        val allMd5s = group.chapterDetail.map { it.titleMd5 }.toSet()
        val selectedInGroup = allMd5s.intersect(selectedChapterMd5s)
        val allSelected = selectedInGroup.size == allMd5s.size && allMd5s.isNotEmpty()

        selectAllBtn.text = if (allSelected) "取消全选" else "全选"
        selectAllBtn.setOnClickListener {
            if (allSelected) {
                selectedChapterMd5s.removeAll(allMd5s)
            } else {
                selectedChapterMd5s.addAll(allMd5s)
            }
            refreshCurrentGroupUI(group)
        }

        val hasSelection = selectedInGroup.isNotEmpty()
        deleteBtn.isEnabled = hasSelection
        deleteBtn.alpha = if (hasSelection) 1.0f else 0.5f
        deleteBtn.setOnClickListener {
            if (hasSelection) {
                deleteSelectedChapters(group)
            }
        }

        // 合并：单选或多选都支持
        mergeBtn.isEnabled = hasSelection
        mergeBtn.alpha = if (hasSelection) 1.0f else 0.5f
        mergeBtn.setOnClickListener {
            if (hasSelection) {
                mergeSelectedChapters(group)
            }
        }
    }

    /**
     * 刷新当前 group 的章节列表 UI（保留展开状态）
     */
    private fun refreshCurrentGroupUI(group: CacheGroup) {
        val container = cacheListContainer ?: return
        val groupKey = group.bookUrl.ifBlank { group.bookName }
        // 找到对应 book header
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.findViewById<TextView>(R.id.tv_book_name) != null) {
                val bookNameView = child.findViewById<TextView>(R.id.tv_book_name)
                if (bookNameView != null && bookNameView.text == group.bookName) {
                    val chapterContainer = child.findViewById<LinearLayout>(R.id.chapter_container)
                    val actionBar = child.findViewById<LinearLayout>(R.id.chapter_action_bar)
                    if (expandedGroups[groupKey] == true) {
                        renderChapterDetails(chapterContainer, group, actionBar)
                        updateActionBarVisibility(actionBar, group)
                    }
                    break
                }
            }
        }
    }

    private fun renderChapterDetails(container: LinearLayout, group: CacheGroup, actionBar: LinearLayout) {
        container.removeAllViews()

        if (group.chapterDetail.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "无章节缓存数据"
                textSize = 12f
                setTextColor(
                    requireContext().themeColor(
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                setPadding(8, 8, 8, 8)
            }
            container.addView(tv)
            return
        }

        for ((index, chapter) in group.chapterDetail.withIndex()) {
            if (index > 0) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    setBackgroundColor(
                        requireContext().themeColor(
                            com.google.android.material.R.attr.colorOutlineVariant
                        )
                    )
                }
                container.addView(divider)
            }

            val chapterItem = layoutInflater.inflate(
                R.layout.item_tts_cache_chapter, container, false
            )

            val cb = chapterItem.findViewById<CheckBox>(R.id.cb_chapter_select)
            cb.visibility = View.VISIBLE
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = chapter.titleMd5 in selectedChapterMd5s
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedChapterMd5s.add(chapter.titleMd5)
                } else {
                    selectedChapterMd5s.remove(chapter.titleMd5)
                }
                updateActionButtonsState(actionBar, group)
            }

            // 点击整行也可切换选择
            chapterItem.setOnClickListener {
                cb.isChecked = !cb.isChecked
            }

            chapterItem.findViewById<TextView>(R.id.tv_chapter_title).text = chapter.chapterTitle
            chapterItem.findViewById<TextView>(R.id.tv_chapter_file_count).text =
                "${chapter.fileCount} 个音频"
            chapterItem.findViewById<TextView>(R.id.tv_chapter_size).text =
                formatFileSize(chapter.size)

            chapterItem.findViewById<TextView>(R.id.btn_clear_chapter).setOnClickListener {
                deleteChapterCache(chapter)
            }

            container.addView(chapterItem)
        }
    }

    /**
     * 删除选中的章节缓存
     */
    private fun deleteSelectedChapters(group: CacheGroup) {
        val selectedMd5s = selectedChapterMd5s.toSet()
        if (selectedMd5s.isEmpty()) {
            toastOnUi("未选中任何章节")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dir = getTtsCacheDir()
            var deleted = 0
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".mp3")) return@forEach
                val nameWithoutExt = file.nameWithoutExtension
                val underscoreIdx = nameWithoutExt.indexOf('_')
                if (underscoreIdx <= 0) return@forEach
                val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                if (titleMd5 in selectedMd5s && file.delete()) {
                    deleted++
                }
            }
            TtsCacheManager.removeEntries(selectedMd5s)
            selectedChapterMd5s.clear()
            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = TtsCacheManager.buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                toastOnUi("已清理 $deleted 个缓存文件")
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    /**
     * 合并选中的章节音频：将单章所有音频合并为一个完整音频，以章节名称命名
     */
    private fun mergeSelectedChapters(group: CacheGroup) {
        val selectedMd5s = selectedChapterMd5s.toSet()
        if (selectedMd5s.isEmpty()) {
            toastOnUi("未选中任何章节")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dir = getTtsCacheDir()
            var mergedCount = 0
            var totalMergedSize = 0L

            for (md5 in selectedMd5s) {
                // 找出该章节的所有音频文件，按文件名排序
                val chapterFiles = dir.listFiles()?.filter { file ->
                    file.isFile && file.name.endsWith(".mp3") &&
                            file.name.startsWith("${md5}_")
                }?.sortedBy { it.name } ?: continue

                if (chapterFiles.isEmpty()) continue
                if (chapterFiles.size == 1) {
                    // 只有一个文件，无需合并，直接重命名
                    val chapterTitle = group.chapterDetail
                        .find { it.titleMd5 == md5 }?.chapterTitle ?: md5
                    val sanitizedName = sanitizeFileName(chapterTitle)
                    val mergedDir = File(dir, "merged")
                    mergedDir.mkdirs()
                    val mergedFile = File(mergedDir, "${sanitizedName}.mp3")
                    var counter = 1
                    var finalFile = mergedFile
                    while (finalFile.exists()) {
                        finalFile = File(mergedDir, "${sanitizedName}(${counter}).mp3")
                        counter++
                    }
                    chapterFiles[0].copyTo(finalFile)
                    totalMergedSize += finalFile.length()
                    mergedCount++
                    continue
                }

                // 多文件合并
                val chapterTitle = group.chapterDetail
                    .find { it.titleMd5 == md5 }?.chapterTitle ?: md5
                val sanitizedName = sanitizeFileName(chapterTitle)
                val mergedDir = File(dir, "merged")
                mergedDir.mkdirs()
                val mergedFile = File(mergedDir, "${sanitizedName}.mp3")
                var counter = 1
                var finalFile = mergedFile
                while (finalFile.exists()) {
                    finalFile = File(mergedDir, "${sanitizedName}(${counter}).mp3")
                    counter++
                }

                try {
                    mergeMp3Files(chapterFiles, finalFile)
                    totalMergedSize += finalFile.length()
                    mergedCount++
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                        toastOnUi("合并「${chapterTitle}」失败：${e.localizedMessage}")
                    }
                }
            }

            selectedChapterMd5s.clear()
            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = TtsCacheManager.buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                toastOnUi("已合并 $mergedCount 个章节，输出到 merged 目录")
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    /**
     * 合并多个 MP3 文件为一个文件（简单字节拼接）
     */
    private fun mergeMp3Files(files: List<File>, outputFile: File) {
        FileOutputStream(outputFile).use { fos ->
            for (file in files) {
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim()
            .take(200)
    }

    private fun deleteChapterCache(chapter: ChapterCacheInfo) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir = getTtsCacheDir()
            var deleted = 0
            cacheDir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".mp3")) return@forEach
                val nameWithoutExt = file.nameWithoutExtension
                val underscoreIdx = nameWithoutExt.indexOf('_')
                if (underscoreIdx <= 0) return@forEach
                val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                if (titleMd5 == chapter.titleMd5 && file.delete()) {
                    deleted++
                }
            }
            // 增量更新：从索引中移除，并更新分组
            TtsCacheManager.removeEntries(setOf(chapter.titleMd5))
            // 清理选择状态
            selectedChapterMd5s.remove(chapter.titleMd5)
            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = TtsCacheManager.buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                toastOnUi("已清理「${chapter.chapterTitle}」的 $deleted 个缓存文件")
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    private fun getTtsCacheDir(): File {
        val baseDir = appCtx.externalCacheDir ?: appCtx.cacheDir
        return File(baseDir, "httpTTS")
    }

    private fun clearGroup(group: CacheGroup) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dir = getTtsCacheDir()
            if (!dir.exists()) return@launch

            var deleted = 0
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".mp3")) return@forEach
                val nameWithoutExt = file.nameWithoutExtension
                val underscoreIdx = nameWithoutExt.indexOf('_')
                if (underscoreIdx <= 0) return@forEach
                val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                if (titleMd5 in group.titleMd5Set) {
                    if (file.delete()) deleted++
                }
            }

            // 增量更新：只移除该组的 MD5，不重建全部索引
            TtsCacheManager.removeEntries(group.titleMd5Set)
            // 清理选择状态中属于该组的章节
            selectedChapterMd5s.removeAll(group.titleMd5Set)
            // 清理展开状态
            val groupKey = group.bookUrl.ifBlank { group.bookName }
            expandedGroups.remove(groupKey)

            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = TtsCacheManager.buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                toastOnUi("已清理「${group.bookName}」的 $deleted 个缓存文件")
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    private fun clearAllCache() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dir = getTtsCacheDir()
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
            // 同时清理 merged 目录
            val mergedDir = File(dir, "merged")
            if (mergedDir.exists()) {
                mergedDir.listFiles()?.forEach { it.delete() }
                mergedDir.delete()
            }
            withContext(Dispatchers.Main) {
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@withContext
                toastOnUi("已清理全部缓存")
                selectedChapterMd5s.clear()
                expandedGroups.clear()
                TtsCacheManager.clear()
                dismiss()
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun updateCacheStats(groups: List<CacheGroup>) {
        val totalFiles = groups.sumOf { it.fileCount }
        val totalSize = groups.sumOf { it.totalSize }
        val sizeStr = formatFileSize(totalSize)
        tvCacheSummary?.text = "缓存文件数：$totalFiles 个文件 | 缓存总大小：$sizeStr"
    }

    private fun showPreloadNumPicker() {
        NumberPickerDialog(requireContext())
            .setTitle("预加载数量")
            .setMaxValue(200)
            .setMinValue(0)
            .setValue(io.legado.app.help.config.AppConfig.audioPreDownloadNum)
            .show {
                appCtx.putPrefInt(io.legado.app.constant.PreferKey.audioPreDownloadNum, it)
                updatePreloadRetentionButtons()
            }
    }

    private fun showRetentionTimePicker() {
        NumberPickerDialog(requireContext())
            .setTitle("保留时间（分钟）")
            .setMaxValue(10000)
            .setMinValue(0)
            .setValue(io.legado.app.help.config.AppConfig.audioCacheCleanTimeOrgin)
            .show {
                appCtx.putPrefInt(io.legado.app.constant.PreferKey.audioCacheCleanTime, it)
                updatePreloadRetentionButtons()
            }
    }

    private fun updatePreloadRetentionButtons() {
        requireView().findViewById<MaterialButton>(R.id.btn_preload_num)?.text =
            "预加载：${io.legado.app.help.config.AppConfig.audioPreDownloadNum}章"
        val cleanTime = io.legado.app.help.config.AppConfig.audioCacheCleanTimeOrgin
        requireView().findViewById<MaterialButton>(R.id.btn_retention_time)?.text =
            if (cleanTime == 0) "保留：不保留" else "保留：${cleanTime}分钟"
    }
}

data class TtsCacheProgress(
    val bookUrl: String,
    val bookName: String,
    val chapterTitle: String,
    val titleMd5: String,
    val current: Int,
    val total: Int,
    val fileSize: Long = 0
)
