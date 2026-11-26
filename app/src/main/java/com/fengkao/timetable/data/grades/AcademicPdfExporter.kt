package com.fengkao.timetable.data.grades

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fengkao.timetable.data.model.CachedSchedule
import java.io.File
import java.io.FileOutputStream

/** 不依赖第三方库的本地 PDF 导出：课表和成绩单。 */
object AcademicPdfExporter {
    fun schedule(context: Context, schedule: CachedSchedule): String {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 11f; isAntiAlias = true }
        val title = Paint(paint).apply { textSize = 18f; isFakeBoldText = true }
        var page = 1
        var y = 48f
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, page).create()
        var pdfPage = doc.startPage(pageInfo)
        var canvas = pdfPage.canvas
        canvas.drawText("泥石 · 课程表", 40f, y, title); y += 28f
        canvas.drawText("${schedule.student.name}  ${schedule.student.studentId}", 40f, y, paint); y += 18f
        canvas.drawText("${schedule.xnm}-${schedule.xnm + 1} 学年 第 ${schedule.term} 学期", 40f, y, paint); y += 24f
        schedule.courses.forEach { course ->
            if (y > 800f) {
                doc.finishPage(pdfPage)
                page += 1
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, page).create()
                pdfPage = doc.startPage(pageInfo)
                canvas = pdfPage.canvas
                y = 48f
            }
            canvas.drawText("周${course.dayOfWeek}  第${course.startPeriod}-${course.endPeriod}节  ${course.name}", 40f, y, paint); y += 15f
            canvas.drawText("${course.teacher}  ${course.place}", 56f, y, paint); y += 19f
        }
        doc.finishPage(pdfPage)
        return save(context, doc, "泥石-课程表-${schedule.xnm}-${schedule.term}.pdf")
    }

    fun grades(context: Context, snapshot: GradeSnapshot): String {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 11f; isAntiAlias = true }
        val title = Paint(paint).apply { textSize = 18f; isFakeBoldText = true }
        var y = 48f
        var page = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, page).create()
        var pdfPage = doc.startPage(pageInfo)
        var canvas = pdfPage.canvas
        canvas.drawText("泥石 · 学业成绩", 40f, y, title); y += 28f
        canvas.drawText(snapshot.studentName, 40f, y, paint); y += 18f
        canvas.drawText(snapshot.termLabel(), 40f, y, paint); y += 18f
        canvas.drawText("GPA ${snapshot.gpa()?.let { "%.2f".format(it) } ?: "--"}    学分 ${"%.1f".format(snapshot.totalCredits())}", 40f, y, paint); y += 24f
        snapshot.records.forEach { record ->
            if (y > 800f) {
                doc.finishPage(pdfPage)
                page += 1
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, page).create()
                pdfPage = doc.startPage(pageInfo)
                canvas = pdfPage.canvas
                y = 48f
            }
            val point = record.gradePoint?.let { "%.2f".format(it) } ?: "--"
            canvas.drawText("${record.title}    ${record.grade.ifBlank { "--" }}    绩点 $point", 40f, y, paint); y += 15f
            canvas.drawText("${record.teacher}    ${record.credit} 学分", 56f, y, paint); y += 19f
        }
        doc.finishPage(pdfPage)
        return save(context, doc, "泥石-学业成绩-${snapshot.xnm}-${snapshot.term}.pdf")
    }

    private fun save(context: Context, doc: PdfDocument, name: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/泥石")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建下载文件")
                context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
                uri.toString()
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "泥石").apply { mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { doc.writeTo(it) }
                file.absolutePath
            }
        } finally {
            doc.close()
        }
    }
}
