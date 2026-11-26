package com.fengkao.timetable.data.grades

import android.content.Context
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.TermSpec
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 成绩单本地缓存：按学期保存明细，并保留最近学期单文件缓存以兼容旧调用。 */
class GradeStore(private val directory: File) {
    constructor(context: Context) : this(context.filesDir)

    private val gradesDirectory = File(directory, "grades")
    private val latestFile = File(directory, "grades_cache.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** 返回本次保存是否成为最新学期；旧学期仍会保存，但不会覆盖 latest。 */
    @Synchronized fun save(snapshot: GradeSnapshot): Boolean {
        migrateLegacyLocked()
        val encoded = json.encodeToString(GradeSnapshot.serializer(), snapshot)
        val replaceLatest = TermCalculator.shouldReplaceLatest(
            readLatestLocked()?.let { TermSpec(it.xnm, it.term) },
            TermSpec(snapshot.xnm, snapshot.term),
        )
        writeAtomically(termFile(snapshot.xnm, snapshot.term), encoded)
        if (replaceLatest) writeAtomically(latestFile, encoded)
        return replaceLatest
    }

    /** 兼容旧的无参数调用。 */
    @Synchronized fun load(): GradeSnapshot? = loadLatest()

    @Synchronized fun loadLatest(): GradeSnapshot? {
        runCatching { migrateLegacyLocked() }
        return readLatestLocked()
    }

    @Synchronized fun load(term: TermSpec): GradeSnapshot? {
        runCatching { migrateLegacyLocked() }
        return read(termFile(term.xnm, term.term))
            ?: read(latestFile)?.takeIf { it.xnm == term.xnm && it.term == term.term }
    }

    @Synchronized fun terms(): List<TermSpec> {
        runCatching { migrateLegacyLocked() }
        val result = linkedSetOf<TermSpec>()
        termFiles().forEach { file -> read(file)?.let { result += TermSpec(it.xnm, it.term) } }
        read(latestFile)?.let { result += TermSpec(it.xnm, it.term) }
        return result.sortedBy { it.rank }
    }

    @Synchronized fun clear() {
        runCatching { latestFile.delete() }
        runCatching { File(directory, "${latestFile.name}.tmp").delete() }
        runCatching { gradesDirectory.deleteRecursively() }
    }

    private fun migrateLegacyLocked() {
        val legacy = read(latestFile) ?: return
        val target = termFile(legacy.xnm, legacy.term)
        if (!target.exists()) {
            writeAtomically(target, json.encodeToString(GradeSnapshot.serializer(), legacy))
        }
    }

    private fun readLatestLocked(): GradeSnapshot? =
        (listOfNotNull(read(latestFile)) + termFiles().mapNotNull(::read))
            .maxWithOrNull(compareBy({ TermSpec(it.xnm, it.term).rank }, { it.fetchedAt }))

    private fun termFiles(): List<File> = gradesDirectory.listFiles()
        ?.filter { it.isFile && TERM_FILE_NAME.matches(it.name) }
        .orEmpty()

    private fun termFile(xnm: Int, term: Int): File = File(gradesDirectory, "${xnm}_${term}.json")

    private fun read(target: File): GradeSnapshot? = runCatching {
        if (!target.isFile) null else {
            json.decodeFromString(GradeSnapshot.serializer(), target.readText(Charsets.UTF_8))
        }
    }.getOrNull()

    private fun writeAtomically(target: File, text: String) {
        val parent = target.parentFile ?: throw IOException("成绩缓存目录无效")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建成绩缓存目录")
        val temporary = File.createTempFile(target.name, ".tmp", parent)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) runCatching { temporary.delete() }
        }
    }

    private companion object {
        val TERM_FILE_NAME = Regex("""(\d+)_(\d+)\.json""")
    }
}
