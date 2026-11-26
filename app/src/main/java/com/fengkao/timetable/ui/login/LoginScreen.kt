package com.fengkao.timetable.ui.login

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fengkao.timetable.ui.MainViewModel
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.BrandGreenSoft
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid
import com.fengkao.timetable.ui.xuexitong.XuexitongLogin
import com.fengkao.timetable.ui.xuexitong.XuexitongViewModel

@Composable
fun LoginScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val xuexitongModel: XuexitongViewModel = viewModel()
    val xuexitong by xuexitongModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val webImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.reloadAfterWebImport()
    }
    LaunchedEffect(xuexitong.loggedIn) {
        if (xuexitong.loggedIn && state.schedule?.courses.isNullOrEmpty() != false) {
            viewModel.importXuexitongSchedule()
        }
    }

    Box(
        Modifier.fillMaxSize().background(com.fengkao.timetable.ui.theme.SageBg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp)
        ) {
            Spacer(Modifier.height(52.dp))
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(BrandGreenSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CalendarMonth, null, tint = BrandGreen, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("泥石", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = InkDark)
            Text("登录郑州师范学院教务系统，自动同步课表和成绩", fontSize = 13.sp, color = InkMid)
            Spacer(Modifier.height(22.dp))

            AcademicLoginForm(viewModel)

            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = { webImportLauncher.launch(Intent(context, ScheduleWebImportActivity::class.java)) },
                enabled = !state.loginBusy && !state.syncing,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("使用教务网页登录导入", color = BrandGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(18.dp))
            Text("其他登录方式", color = InkGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            XuexitongLogin(
                state = xuexitong,
                model = xuexitongModel,
                showHeading = false,
                submitLabel = "使用学习通登录",
            )
            xuexitong.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    color = if (xuexitong.error) MaterialTheme.colorScheme.error else InkMid,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.syncMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = InkGray, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { viewModel.reopenWelcome() }, enabled = !state.loginBusy,
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("欢迎与初次设置", fontSize = 12.sp, color = InkMid)
            }
        }
    }
}

@Composable
internal fun AcademicLoginForm(
    viewModel: MainViewModel,
    submitLabel: String = "登录并导入课表与成绩",
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var account by rememberSaveable { mutableStateOf(state.settings.account) }
    var password by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardWhite).padding(20.dp),
    ) {
        Text("教务账号登录", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkDark)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text("学号") },
            placeholder = { Text("请输入学号", color = InkGray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            placeholder = { Text("请输入密码", color = InkGray) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Icon(
                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    "显示/隐藏密码",
                    tint = InkGray,
                    modifier = Modifier.size(20.dp).clickable { showPassword = !showPassword },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { remember = !remember }.padding(top = 2.dp),
        ) {
            Checkbox(
                checked = remember,
                onCheckedChange = { remember = it },
                colors = CheckboxDefaults.colors(checkedColor = BrandGreen),
            )
            Text("记住密码并自动更新课表", fontSize = 13.sp, color = InkMid)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.login(account.trim(), password, state.settings.baseUrl, remember) },
            enabled = !state.loginBusy && account.isNotBlank() && password.isNotBlank(),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (state.loginBusy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(submitLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        state.loginError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Text("账号密码仅保存在本机；不勾选记住密码时只用于本次登录。", color = InkGray, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
