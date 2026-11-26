package com.fengkao.timetable.ui.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.grades.GradeRecord
import com.fengkao.timetable.data.grades.GradeSnapshot
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(
    state: GradeUiState,
    onSelectTerm: (TermSpec) -> Unit,
    onRefresh: (TermSpec) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val terms = remember(state.snapshots.keys, state.selectedTerm) {
        (recentTerms(state.selectedTerm) + state.snapshots.keys)
            .distinct()
            .sortedByDescending(TermSpec::rank)
    }
    val snapshot = state.snapshot

    PullToRefreshBox(
        isRefreshing = state.busy,
        onRefresh = { onRefresh(state.selectedTerm) },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GradesHeader(state, snapshot, onRefresh) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(terms, key = { "${it.xnm}_${it.term}" }) { term ->
                        FilterChip(
                            selected = term == state.selectedTerm,
                            onClick = { onSelectTerm(term) },
                            enabled = !state.busy,
                            label = { Text(shortTermLabel(term)) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardWhite,
                                labelColor = InkMid,
                                selectedContainerColor = InkDark,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
            state.message?.let { message ->
                item {
                    Text(
                        message,
                        color = if (state.error) MaterialTheme.colorScheme.error else InkMid,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            snapshot?.let { current -> item { GradeSummary(current) } }
            if (snapshot != null && snapshot.records.isNotEmpty()) {
                item {
                    Text(
                        "课程成绩",
                        color = InkDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                itemsIndexed(snapshot.records, key = { index, record ->
                    "${record.courseId.ifBlank { record.title }}_${record.gradeNature}_$index"
                }) { _, record ->
                    GradeRow(record)
                }
                item {
                    Text(
                        if (snapshot.usedFallbackGradePoint) {
                            "教务未提供完整绩点，当前按（百分制成绩 − 50）÷ 10 回算；60 分为 1.0，低于 60 分为 0。等级制成绩不回算。"
                        } else {
                            "均分按课程学分加权；绩点优先采用教务官方值，缺失值不参与统计。"
                        },
                        color = InkGray,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                }
            } else if (!state.busy) {
                item { EmptyGrades(state, snapshot, onImport) }
            }
        }
    }
}

@Composable
private fun GradesHeader(
    state: GradeUiState,
    snapshot: GradeSnapshot?,
    onRefresh: (TermSpec) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.EmojiEvents, null, tint = InkDark, modifier = Modifier.size(27.dp))
                Text("成绩", color = InkDark, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${snapshot?.records?.size ?: 0} 门", color = InkGray, fontSize = 13.sp)
            }
            IconButton(onClick = { onRefresh(state.selectedTerm) }, enabled = !state.busy) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BrandGreen)
                } else {
                    Icon(Icons.Outlined.Refresh, "刷新成绩", tint = BrandGreen)
                }
            }
        }
        Text(
            when {
                state.busy -> "正在读取教务成绩…"
                snapshot != null -> "更新于 ${formatUpdateTime(snapshot.fetchedAt)}"
                else -> "尚未读取这个学期"
            },
            color = InkGray,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun GradeSummary(snapshot: GradeSnapshot) {
    Surface(color = CardWhite, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryValue("加权均分", snapshot.weightedAverage()?.let { "%.2f".format(it) } ?: "--", Modifier.weight(1f))
                SummaryValue("平均绩点", snapshot.gpa()?.let { "%.2f".format(it) } ?: "--", Modifier.weight(1f))
                SummaryValue("总学分", "%.1f".format(snapshot.totalCredits()), Modifier.weight(1f))
                SummaryValue("通过", "${snapshot.passedCount()}/${snapshot.totalCount()}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, color = InkDark, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = InkGray, fontSize = 11.sp)
    }
}

@Composable
private fun EmptyGrades(state: GradeUiState, snapshot: GradeSnapshot?, onImport: () -> Unit) {
    Surface(color = CardWhite, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.offline && snapshot == null) "暂无本地成绩缓存" else "本学期暂无成绩",
                color = InkDark,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (snapshot != null) "本学期可能尚未公布成绩，公布后点击右上角刷新即可。" else "请确认已登录教务系统；如果接口不兼容当前页面，可改用网页登录读取。",
                color = InkGray,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("使用教务网页登录读取")
            }
        }
    }
}

@Composable
private fun GradeRow(record: GradeRecord) {
    val barColor = scoreColor(record)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    record.title,
                    color = InkDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        record.nature.ifBlank { record.category },
                        record.credit.takeIf { it > 0 }?.let { "%.1f 学分".format(it) },
                        record.gradePoint?.let { "绩点 %.2f".format(it) } ?: "绩点 --",
                        record.teacher,
                    ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    color = InkGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (record.gradeNature.isNotBlank()) {
                    Text(record.gradeNature, color = barColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                record.grade.ifBlank { "未公布" },
                color = barColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(color = InkGray.copy(alpha = 0.14f))
    }
}

private fun scoreColor(record: GradeRecord): Color {
    val score = record.score
    return when {
        score != null && score < 60 -> Color(0xFFD64B4B)
        score != null && score < 70 -> Color(0xFFE07A35)
        score != null && score < 85 -> Color(0xFFB88916)
        score != null -> BrandGreen
        record.grade.contains("优秀") -> BrandGreen
        record.grade.contains("良好") -> Color(0xFF3983B8)
        record.grade.contains("及格") || record.grade.contains("通过") -> Color(0xFFB88916)
        record.grade.contains("不及格") || record.grade.contains("不通过") -> Color(0xFFD64B4B)
        else -> InkGray
    }
}

private fun recentTerms(selected: TermSpec): List<TermSpec> {
    val latest = listOf(TermCalculator.autoTerm(), selected).maxBy(TermSpec::rank)
    return (0 until 10).map { offset ->
        val rank = latest.rank - offset
        TermSpec((rank - 1) / 3, (rank - 1) % 3 + 1)
    }
}

private fun shortTermLabel(term: TermSpec): String {
    val start = String.format("%02d", term.xnm % 100)
    val end = String.format("%02d", (term.xnm + 1) % 100)
    return if (term.term == 3) "$start-$end 夏季" else "$start-$end 第${term.term}学期"
}

private fun formatUpdateTime(timestamp: Long): String =
    if (timestamp <= 0L) "--"
    else runCatching {
        DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }.getOrDefault("--")
