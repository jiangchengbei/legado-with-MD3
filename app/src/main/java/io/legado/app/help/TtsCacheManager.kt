package io.legado.app.help

import io.legado.app.data.appDb
import io.legado.app.ui.book.read.config.TtsCacheDetailDialog
import io.legado.app.utils.MD5Utils
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TTS 音频缓存索引管理器 —— 常驻内存的单例缓存索引。
 *
 * 核心优化：
 * 1. 首次构建 MD5 反向索引后常驻内存，不因 Dialog 销毁而丢失。
 * 2. 删除缓存时只做增量移除，不重建全部索引。
 * 3. 新增缓存文件时（如预下载完成）可增量添加索引条目。
 * 4. 维护文件快照，打开弹窗时可先返回旧数据，后台做增量 diff 刷新。
 */
object TtsCacheManager {

    data class IndexEntry(
        val bookName: String, val bookUrl: String, val chapterTitle: String,
        val chapterIndex: Int = 0
    )

    /** MD5 -> (bookName, bookUrl, chapterTitle) 反向索引 */
    private val md5Index = ConcurrentHashMap<String, IndexEntry>()

    /** bookUrl -> (bookName, totalChapters) */
    private val bookInfo = ConcurrentHashMap<String, Pair<String, Int>>()

    /** 上次的文件快照：文件名 -> 文件大小（用于增量 diff） */
    @Volatile
    var fileSnapshot: Map<String, Long> = emptyMap()
        private set

    /** 上次计算的分组结果，可直接用于快速渲染 */
    @Volatile
    var lastGroups: List<TtsCacheDetailDialog.CacheGroup> = emptyList()
        private set

    /** 索引是否已构建完成 */
    @Volatile
    var indexBuilt: Boolean = false
        private set

    /** 是否正在后台构建索引 */
    @Volatile
    var isBuilding: Boolean = false
        private set

    fun getMd5Index(): Map<String, IndexEntry> = md5Index
    fun getBookInfo(): Map<String, Pair<String, Int>> = bookInfo

    /**
     * 新增或更新单个 MD5 索引条目。
     * 适用于 HttpReadAloudService 预下载完成时增量添加。
     */
    fun addEntry(titleMd5: String, bookName: String, bookUrl: String, chapterTitle: String, chapterIndex: Int = 0) {
        md5Index[titleMd5] = IndexEntry(bookName, bookUrl, chapterTitle, chapterIndex)
        val current = bookInfo[bookUrl]
        if (current == null || current.first != bookName) {
            val total = try {
                appDb.bookChapterDao.getChapterCount(bookUrl)
            } catch (_: Exception) {
                0
            }
            bookInfo[bookUrl] = Pair(bookName, total)
        }
    }

    /**
     * 批量移除 MD5 索引条目。
     * 适用于删除缓存后，避免下次打开弹窗时重建全部索引。
     */
    fun removeEntries(titleMd5s: Collection<String>) {
        titleMd5s.forEach { md5Index.remove(it) }
    }

    /**
     * 清空全部索引和快照。
     */
    fun clear() {
        md5Index.clear()
        bookInfo.clear()
        fileSnapshot = emptyMap()
        lastGroups = emptyList()
        indexBuilt = false
    }

    /**
     * 后台预构建索引（如果尚未构建且不在构建中）。
     * 应在 ReadAloudDialog 打开时调用，确保缓存管理弹窗打开时索引已就绪。
     */
    fun preBuildIfNeeded() {
        if (indexBuilt || isBuilding) return
        buildFullIndex()
        // 构建完成后立即生成分组数据，供后续弹窗直接渲染
        val files = scanMp3Files()
        if (files.isNotEmpty()) {
            val groups = buildGroupsFromFiles(files)
            updateSnapshot(files, groups)
        }
    }

    /**
     * 根据文件列表和当前索引构建分组结果。
     */
    fun buildGroupsFromFiles(mp3Files: Array<File>): List<TtsCacheDetailDialog.CacheGroup> {
        val resolvedMd5s = md5Index
        val bookInfoMap = bookInfo

        val titleMd5ToFiles = mutableMapOf<String, MutableList<File>>()
        val orphanFiles = mutableListOf<File>()
        for (file in mp3Files) {
            val nameWithoutExt = file.nameWithoutExtension
            if (nameWithoutExt == "silent") continue
            val underscoreIdx = nameWithoutExt.indexOf('_')
            if (underscoreIdx <= 0) {
                orphanFiles.add(file)
            } else {
                val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                titleMd5ToFiles.getOrPut(titleMd5) { mutableListOf() }.add(file)
            }
        }

        if (titleMd5ToFiles.isEmpty() && orphanFiles.isEmpty()) return emptyList()

        val bookUrlToMd5s = mutableMapOf<String, MutableSet<String>>()
        val unknownMd5s = mutableSetOf<String>()

        for ((titleMd5, _) in titleMd5ToFiles) {
            val resolved = resolvedMd5s[titleMd5]
            if (resolved != null) {
                bookUrlToMd5s.getOrPut(resolved.bookUrl) { mutableSetOf() }.add(titleMd5)
            } else {
                unknownMd5s.add(titleMd5)
            }
        }

        val result = mutableListOf<TtsCacheDetailDialog.CacheGroup>()

        for ((bookUrl, md5Set) in bookUrlToMd5s) {
            val info = bookInfoMap[bookUrl]
            val bookName = info?.first ?: "未知书籍"
            val totalChapters = info?.second ?: 0
            var fileCount = 0
            var totalSize = 0L
            val chapterDetails = mutableListOf<TtsCacheDetailDialog.ChapterCacheInfo>()

            for (md5 in md5Set) {
                val files = titleMd5ToFiles[md5] ?: continue
                val size = files.sumOf { it.length() }
                fileCount += files.size
                totalSize += size
                val entry = resolvedMd5s[md5]
                chapterDetails.add(
                    TtsCacheDetailDialog.ChapterCacheInfo(
                        chapterTitle = entry?.chapterTitle ?: md5,
                        titleMd5 = md5,
                        fileCount = files.size,
                        size = size,
                        chapterIndex = entry?.chapterIndex ?: Int.MAX_VALUE
                    )
                )
            }

            result.add(
                TtsCacheDetailDialog.CacheGroup(
                    bookName = bookName,
                    bookUrl = bookUrl,
                    chapterCount = md5Set.size,
                    totalChapterCount = totalChapters,
                    fileCount = fileCount,
                    totalSize = totalSize,
                    titleMd5Set = md5Set,
                    chapterDetail = chapterDetails.sortedBy { it.chapterIndex }
                )
            )
        }

        if (unknownMd5s.isNotEmpty() || orphanFiles.isNotEmpty()) {
            var fileCount = 0
            var totalSize = 0L
            val chapterDetails = mutableListOf<TtsCacheDetailDialog.ChapterCacheInfo>()

            for (md5 in unknownMd5s) {
                val files = titleMd5ToFiles[md5] ?: continue
                val size = files.sumOf { it.length() }
                fileCount += files.size
                totalSize += size
                chapterDetails.add(
                    TtsCacheDetailDialog.ChapterCacheInfo(
                        chapterTitle = "未知章节($md5)",
                        titleMd5 = md5,
                        fileCount = files.size,
                        size = size
                    )
                )
            }

            if (orphanFiles.isNotEmpty()) {
                val orphanMd5 = "__orphan__"
                val orphanSize = orphanFiles.sumOf { it.length() }
                fileCount += orphanFiles.size
                totalSize += orphanSize
                titleMd5ToFiles[orphanMd5] = orphanFiles.toMutableList()
                chapterDetails.add(
                    TtsCacheDetailDialog.ChapterCacheInfo(
                        chapterTitle = "格式异常文件",
                        titleMd5 = orphanMd5,
                        fileCount = orphanFiles.size,
                        size = orphanSize
                    )
                )
                unknownMd5s.add(orphanMd5)
            }

            result.add(
                TtsCacheDetailDialog.CacheGroup(
                    bookName = "未知来源",
                    chapterCount = unknownMd5s.size,
                    totalChapterCount = 0,
                    fileCount = fileCount,
                    totalSize = totalSize,
                    titleMd5Set = unknownMd5s,
                    chapterDetail = chapterDetails.sortedBy { it.chapterIndex }
                )
            )
        }

        return result.sortedByDescending { it.totalSize }
    }

    /**
     * 构建完整的 MD5 反向索引（首次或强制重建）。
     * 该操作较耗时，应在后台线程执行。
     */
    fun buildFullIndex() {
        if (isBuilding) return
        isBuilding = true
        try {
            val allBooks = appDb.bookDao.all
            for (book in allBooks) {
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                bookInfo[book.bookUrl] = Pair(book.name, chapters.size)
                for ((ci, chapter) in chapters.withIndex()) {
                    val md5 = MD5Utils.md5Encode16(chapter.title.trim())
                    if (!md5Index.containsKey(md5)) {
                        md5Index[md5] = IndexEntry(book.name, book.bookUrl, chapter.title, ci)
                    }
                }
            }
            indexBuilt = true
        } finally {
            isBuilding = false
        }
    }

    /**
     * 获取 httpTTS 缓存目录。
     */
    fun getCacheDir(): File {
        val baseDir = appCtx.externalCacheDir ?: appCtx.cacheDir
        return File(baseDir, "httpTTS")
    }

    /**
     * 扫描当前缓存目录，返回文件列表。
     */
    fun scanMp3Files(): Array<File> {
        val dir = getCacheDir()
        if (!dir.exists() || !dir.isDirectory) return emptyArray()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".mp3") } ?: emptyArray()
    }

    /**
     * 计算目录指纹（用于快速判断是否需要全量重建）。
     */
    fun calcFingerprint(files: Array<File>): Long {
        if (files.isEmpty()) return 0L
        val count = files.size.toLong()
        val sizeApprox = files.sumOf { it.length() } / (1024 * 1024)
        return count * 1_000_000 + sizeApprox
    }

    /**
     * 更新文件快照和分组结果。
     */
    fun updateSnapshot(files: Array<File>, groups: List<TtsCacheDetailDialog.CacheGroup>) {
        fileSnapshot = files.associate { it.name to it.length() }
        lastGroups = groups
    }

    /**
     * 对比当前文件列表与上次快照，返回新增和删除的文件名集合。
     */
    fun diffFiles(currentFiles: Array<File>): Pair<Set<String>, Set<String>> {
        val currentNames = currentFiles.map { it.name }.toSet()
        val oldNames = fileSnapshot.keys
        val added = currentNames - oldNames
        val removed = oldNames - currentNames
        return added to removed
    }
}
