package com.c_ajmd_w_and_lntano.tomatoclock.presentation

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

// --- 基础配置与配色 ---
enum class TimerPhase { FOCUS, REST, LONG_REST }
val PureBlackBg = Color(0xFF000000)
val MatteSurface = Color(0xFF222222)
val MutedText = Color(0xFFB0B0B0)
val MutedSubText = Color(0xFF777777)
val ThemeColors = listOf(
    Color(0xFF7A8D9E), Color(0xFF8A9A7C), Color(0xFFB58A8A), Color(0xFFD4C19C),
    Color(0xFF64B5F6), Color(0xFFFF8A65), Color(0xFFBA68C8)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedPrefs = getSharedPreferences("TomatoClockPrefs", Context.MODE_PRIVATE)

        setContent {
            var currentScreen by remember { mutableStateOf("main") }
            var appThemeColor by remember { mutableStateOf(ThemeColors[3]) }

            var focusTime by remember { mutableIntStateOf(25) }
            var restTime by remember { mutableIntStateOf(5) }
            var longRestTime by remember { mutableIntStateOf(30) }
            var isLongRestEnabled by remember { mutableStateOf(false) }
            var longRestInterval by remember { mutableIntStateOf(4) }
            var timeStep by remember { mutableIntStateOf(5) }

            var totalFocusMinutes by remember { mutableIntStateOf(sharedPrefs.getInt("total_mins", 0)) }
            var lastSessionMins by remember { mutableIntStateOf(0) }

            MaterialTheme(colors = Colors(primary = appThemeColor, background = PureBlackBg)) {
                Box(modifier = Modifier.fillMaxSize().background(PureBlackBg)) {
                    BackHandler(enabled = currentScreen != "main") { currentScreen = "main" }

                    Crossfade(targetState = currentScreen, label = "screen") { screen ->
                        when (screen) {
                            "main" -> MainDashboard(
                                appThemeColor, focusTime, restTime, longRestTime, isLongRestEnabled, longRestInterval, timeStep,
                                onNavigate = { currentScreen = it },
                                onFocusChange = { focusTime = it },
                                onRestChange = { restTime = it },
                                onLongRestChange = { longRestTime = it },
                                onToggleLongRest = { isLongRestEnabled = it },
                                onIntervalChange = { longRestInterval = it }
                            )
                            "timer" -> ActiveTimerPager(
                                appThemeColor, focusTime, restTime, longRestTime, isLongRestEnabled, longRestInterval,
                                onFinish = { earnedMins ->
                                    lastSessionMins = earnedMins
                                    totalFocusMinutes += earnedMins
                                    sharedPrefs.edit().putInt("total_mins", totalFocusMinutes).apply()
                                    currentScreen = "result"
                                }
                            )
                            "stats" -> StatsScreen(totalFocusMinutes) { currentScreen = "main" }
                            "settings" -> SettingsScreen(appThemeColor, timeStep, { appThemeColor = it }, { timeStep = it }) { currentScreen = "main" }
                            "result" -> ResultSummary(lastSessionMins, appThemeColor) { currentScreen = "main" }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActiveTimerPager(theme: Color, fM: Int, rM: Int, lrM: Int, lrE: Boolean, lrI: Int, onFinish: (Int) -> Unit) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 2 })
    var isRunning by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf(TimerPhase.FOCUS) }
    var currentCycle by remember { mutableIntStateOf(1) }
    var timeLeft by remember { mutableIntStateOf(fM * 60) }
    var totalStageSecs by remember { mutableIntStateOf(fM * 60) }

    // 改用秒级统计，彻底修复“统计不准”和“跳过虚增”的问题
    var totalEarnedSecs by remember { mutableLongStateOf(0L) }
    var targetEndTime by remember { mutableLongStateOf(0L) }
    var isSkipping by remember { mutableStateOf(false) }

    // 预告功能逻辑
    val nextPhaseLabel = remember(phase, currentCycle, lrE, lrI) {
        when (phase) {
            TimerPhase.FOCUS -> {
                if (currentCycle % lrI == 0) (if (lrE) "长休 (${lrM}m)" else "结束专注")
                else "短休 (${rM}m)"
            }
            TimerPhase.REST, TimerPhase.LONG_REST -> "专注 (${fM}m)"
        }
    }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            targetEndTime = System.currentTimeMillis() + timeLeft * 1000L
            while (isActive && isRunning) {
                val now = System.currentTimeMillis()
                if (now < targetEndTime) {
                    timeLeft = ((targetEndTime - now) / 1000).toInt()
                    delay(250)
                } else {
                    // 自然结束时的逻辑
                    when (phase) {
                        TimerPhase.FOCUS -> {
                            if (!isSkipping) totalEarnedSecs += (fM * 60)

                            if (currentCycle % lrI == 0) {
                                if (lrE) {
                                    phase = TimerPhase.LONG_REST
                                    totalStageSecs = lrM * 60
                                    timeLeft = totalStageSecs
                                } else {
                                    // 自动结束：长休关闭时，完成最后一轮专注即停止
                                    isRunning = false
                                    onFinish((totalEarnedSecs / 60).toInt())
                                    return@LaunchedEffect
                                }
                            } else {
                                phase = TimerPhase.REST
                                totalStageSecs = rM * 60
                                timeLeft = totalStageSecs
                            }
                        }
                        TimerPhase.REST -> {
                            currentCycle++
                            phase = TimerPhase.FOCUS
                            totalStageSecs = fM * 60
                            timeLeft = totalStageSecs
                        }
                        TimerPhase.LONG_REST -> {
                            // 自动结束：长休阶段完成后停止
                            isRunning = false
                            onFinish((totalEarnedSecs / 60).toInt())
                            return@LaunchedEffect
                        }
                    }
                    isSkipping = false
                    targetEndTime = System.currentTimeMillis() + timeLeft * 1000L
                }
            }
        }
    }

    HorizontalPager(state = pagerState) { page ->
        if (page == 0) {
            TimerControlsPage(
                running = isRunning, theme = theme, nextPhase = nextPhaseLabel,
                onToggle = { isRunning = !isRunning },
                onSkip = {
                    // 修复统计：跳过时只加上实际跑掉的时间
                    if (phase == TimerPhase.FOCUS) {
                        totalEarnedSecs += (totalStageSecs - timeLeft)
                    }
                    isSkipping = true
                    timeLeft = 0
                    targetEndTime = System.currentTimeMillis()
                    if (!isRunning) isRunning = true
                },
                onStop = {
                    if (phase == TimerPhase.FOCUS) {
                        totalEarnedSecs += (totalStageSecs - timeLeft)
                    }
                    onFinish((totalEarnedSecs / 60).toInt())
                }
            )
        } else {
            TimerDisplayPage(timeLeft, totalStageSecs, phase, currentCycle, lrI, theme)
        }
    }
}

@Composable
fun TimerControlsPage(running: Boolean, theme: Color, nextPhase: String, onToggle: () -> Unit, onSkip: () -> Unit, onStop: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("控制中心", fontSize = 12.sp, color = MutedSubText)
        Spacer(modifier = Modifier.height(4.dp))
        // 预告功能
        Text("下一阶段: $nextPhase", fontSize = 11.sp, color = theme, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = onToggle, modifier = Modifier.size(52.dp), colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface)) {
                Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = theme)
            }
            Button(onClick = onStop, modifier = Modifier.size(52.dp), colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface)) {
                Icon(Icons.Default.Stop, null, tint = Color(0xFFEF5350))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Box(modifier = Modifier.width(86.dp).height(34.dp).clip(CircleShape).background(MatteSurface).clickable { onSkip() }, contentAlignment = Alignment.Center) {
            Text("跳过阶段", fontSize = 11.sp, color = MutedText)
        }
    }
}

@Composable
fun TimerDisplayPage(timeLeft: Int, total: Int, phase: TimerPhase, current: Int, totalCycles: Int, theme: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = if (total > 0) timeLeft.toFloat() / total else 0f,
            modifier = Modifier.fillMaxSize().padding(6.dp),
            strokeWidth = 4.dp, indicatorColor = theme, trackColor = MatteSurface
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", timeLeft / 60, timeLeft % 60),
                fontSize = 44.sp, fontWeight = FontWeight.Medium, color = Color.White
            )
            // 修复 13/5 的显示逻辑
            val displayNum = ((current - 1) % totalCycles) + 1
            val label = when (phase) {
                TimerPhase.FOCUS -> "专注中 ($displayNum/$totalCycles)"
                TimerPhase.REST -> "短休中"
                TimerPhase.LONG_REST -> "长休中"
            }
            Text(label, color = theme, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MainDashboard(
    theme: Color, focus: Int, rest: Int, lrTime: Int, lrEnabled: Boolean, lrInterval: Int, step: Int,
    onNavigate: (String) -> Unit, onFocusChange: (Int) -> Unit, onRestChange: (Int) -> Unit,
    onLongRestChange: (Int) -> Unit, onToggleLongRest: (Boolean) -> Unit, onIntervalChange: (Int) -> Unit
) {
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Button(onClick = { onNavigate("settings") }, modifier = Modifier.size(36.dp), colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface)) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp), tint = MutedText)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.width(84.dp).height(38.dp).clip(CircleShape).background(theme).clickable { onNavigate("timer") }, contentAlignment = Alignment.Center) {
                        Text("开始专注", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onNavigate("stats") }, modifier = Modifier.size(36.dp), colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface)) {
                        Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(20.dp), tint = theme)
                    }
                }
            }
            item { AdjusterItem("专注时长", focus, "min", step, 1..60, onFocusChange) }
            item { AdjusterItem("短休时长", rest, "min", step, 1..30, onRestChange) }
            item {
                ToggleChip(
                    checked = lrEnabled,
                    onCheckedChange = onToggleLongRest,
                    label = { Text("长休息", fontSize = 12.sp) },
                    toggleControl = { Switch(checked = lrEnabled, onCheckedChange = null, colors = SwitchDefaults.colors(checkedThumbColor = theme)) },
                    modifier = Modifier.fillMaxWidth(0.94f),
                    colors = ToggleChipDefaults.toggleChipColors(checkedStartBackgroundColor = MatteSurface, uncheckedStartBackgroundColor = MatteSurface)
                )
            }
            if (lrEnabled) {
                item { AdjusterItem("长休时长", lrTime, "min", step, 5..60, onLongRestChange) }
                item { AdjusterItem("长休间隔", lrInterval, "轮专注", 1, 1..10, onIntervalChange) }
            }
        }
    }
}

@Composable
fun AdjusterItem(label: String, value: Int, unit: String, step: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(0.94f).height(46.dp).padding(vertical = 1.dp)) {
        Button(onClick = { onValueChange((value - step).coerceIn(range)) }, modifier = Modifier.weight(1f).fillMaxHeight(), colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface), shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 4.dp, bottomEnd = 4.dp)) { Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp)) }
        Box(modifier = Modifier.weight(1.5f).fillMaxHeight().padding(horizontal = 2.dp).background(MatteSurface, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 8.sp, color = MutedSubText)
                Text("$value$unit", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Button(onClick = { onValueChange((value + step).coerceIn(range)) }, modifier = Modifier.weight(1f).fillMaxHeight(), colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface), shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp, topStart = 4.dp, bottomStart = 4.dp)) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun SettingsScreen(currentTheme: Color, step: Int, onThemeChange: (Color) -> Unit, onStepChange: (Int) -> Unit, onBack: () -> Unit) {
    var r by remember(currentTheme) { mutableIntStateOf((currentTheme.red * 255).toInt()) }
    var g by remember(currentTheme) { mutableIntStateOf((currentTheme.green * 255).toInt()) }
    var b by remember(currentTheme) { mutableIntStateOf((currentTheme.blue * 255).toInt()) }

    ScalingLazyColumn(modifier = Modifier.fillMaxSize().background(PureBlackBg), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("增减步长", fontSize = 11.sp, color = MutedSubText, modifier = Modifier.padding(top = 10.dp)) }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(1, 5, 10).forEach { sValue ->
                    val isSelected = step == sValue
                    Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(if (isSelected) currentTheme else MatteSurface).clickable { onStepChange(sValue) }, contentAlignment = Alignment.Center) {
                        Text("${sValue}m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.Black else Color.White)
                    }
                }
            }
        }
        item { Text("预设主题", fontSize = 11.sp, color = MutedSubText) }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ThemeColors.take(4).forEach { color ->
                    Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(color).clickable { onThemeChange(color) }) {
                        if (color == currentTheme) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.align(Alignment.Center).size(16.dp))
                    }
                }
            }
        }
        item { Text("自定义 RGB 调色盘", fontSize = 11.sp, color = MutedSubText, modifier = Modifier.padding(top = 6.dp)) }
        item { AdjusterItem("红 (R)", r, "", 5, 0..255) { r = it; onThemeChange(Color(r, g, b)) } }
        item { AdjusterItem("绿 (G)", g, "", 5, 0..255) { g = it; onThemeChange(Color(r, g, b)) } }
        item { AdjusterItem("蓝 (B)", b, "", 5, 0..255) { b = it; onThemeChange(Color(r, g, b)) } }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { Button(onClick = onBack, colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface)) { Text("保存") } }
    }
}

@Composable
fun StatsScreen(totalMins: Int, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(PureBlackBg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("累计专注", color = MutedSubText, fontSize = 12.sp)
        Text("${totalMins / 60}h ${totalMins % 60}m", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colors.primary)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(backgroundColor = MatteSurface)) { Text("返回") }
    }
}

@Composable
fun ResultSummary(minutes: Int, themeColor: Color, onConfirm: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(PureBlackBg).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.CheckCircle, null, tint = themeColor, modifier = Modifier.size(40.dp))
        Text("阶段完成！", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("本次总计有效专注 $minutes 分钟", color = MutedSubText, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(15.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(0.8f), colors = ButtonDefaults.buttonColors(backgroundColor = themeColor)) {
            Text("收下成果", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}