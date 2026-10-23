package com.yy.suidu

import java.io.File

object ImportedImages {
    fun cleanUnused(root: File, id: String, references: List<String>): Int {
        val base = root.canonicalFile
        val directory = File(base, id).canonicalFile
        require(directory.parentFile == base) { "图片目录无效" }
        val keep = references.map { File(it).canonicalFile }.toSet()
        require(keep.all { it.parentFile == directory }) { "图片引用越过草稿目录" }
        return directory.listFiles().orEmpty().count { file ->
            file.isFile && file.canonicalFile.parentFile == directory && file.canonicalFile !in keep && file.delete()
        }
    }
}
