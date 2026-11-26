package com.fengkao.timetable.data.electricity

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ElectricityStore(private val directory: File) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(directory, FILE_NAME)
    private val temporaryFile = File(directory, "$FILE_NAME.tmp")
    private val json = Json { ignoreUnknownKeys = true }

    fun save(snapshot: ElectricitySnapshot) {
        synchronized(lock) {
            val bytes = json.encodeToString(ElectricitySnapshot.serializer(), snapshot)
                .toByteArray(Charsets.UTF_8)
            if (!directory.exists() && !directory.mkdirs()) throw IOException("宿舍电费缓存目录创建失败")
            try {
                FileOutputStream(temporaryFile).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                try {
                    Files.move(
                        temporaryFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporaryFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                if (temporaryFile.exists()) temporaryFile.delete()
            }
        }
    }

    fun load(): ElectricitySnapshot? = synchronized(lock) {
        runCatching {
            if (!file.isFile) null
            else json.decodeFromString(ElectricitySnapshot.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
            runCatching { temporaryFile.delete() }
        }
    }

    internal companion object {
        const val FILE_NAME = "electricity_cache.json"
        private val lock = Any()
    }
}
