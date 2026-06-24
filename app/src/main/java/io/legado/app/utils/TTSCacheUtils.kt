package io.legado.app.utils

import io.legado.app.help.TtsCacheManager
import java.io.File

object TTSCacheUtils {

    fun clearTtsCache() {
        val baseDir = TtsCacheManager.getBaseDir()
        // 清理音频文件
        FileUtils.delete(baseDir.absolutePath + File.separator + "httpTTS")
        // 清理数据库索引 (可选，建议一起清，防止索引还在但文件没了)
        FileUtils.delete(baseDir.absolutePath + File.separator + "httpTTS_cache")
    }

}