package com.fengkao.timetable.ui.grades

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fengkao.timetable.ui.login.GradeWebImportActivity
import com.fengkao.timetable.ui.theme.TimetableTheme

class GradesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimetableTheme {
                GradesPage(onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val model: GradeViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) model.reload()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        GradesScreen(
            state = state,
            onSelectTerm = model::selectTerm,
            onRefresh = model::refresh,
            onImport = {
                importLauncher.launch(
                    Intent(context, GradeWebImportActivity::class.java)
                        .putExtra(GradeWebImportActivity.EXTRA_YEAR, state.selectedTerm.xnm)
                        .putExtra(GradeWebImportActivity.EXTRA_TERM, state.selectedTerm.term),
                )
            },
            modifier = Modifier.padding(padding),
        )
    }
}
