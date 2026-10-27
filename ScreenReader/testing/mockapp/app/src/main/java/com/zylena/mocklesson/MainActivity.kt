package com.zylena.mocklesson

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Built like the Sabbath School reading screen: days side by side in a pager, each day one long
 * scrolling page that runs under a see-through title bar, the paragraphs in one block. It logs
 * where every paragraph is, so the test can check that the one ZYLENA reads is fully visible.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = intent.getIntExtra("page", 0)
        val bottom = intent.getBooleanExtra("bottom", false)
        setContent { MaterialTheme { LessonScreen(page, bottom) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(startPage: Int, startAtBottom: Boolean) {
    val pager = rememberPagerState(initialPage = startPage, pageCount = { DAYS.size })
    val lists = remember { List(DAYS.size) { LazyListState() } }
    val positions = remember { HashMap<String, IntArray>() }
    var barBottom by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    val navBars = WindowInsets.navigationBars
    val density = LocalDensity.current
    val collapsed by remember {
        derivedStateOf {
            val s = lists[pager.currentPage]
            s.firstVisibleItemIndex > 0 || s.firstVisibleItemScrollOffset > 500
        }
    }

    LaunchedEffect(Unit) {
        DAYS.forEachIndexed { p, d -> d.paras.forEachIndexed { i, t -> Log.i("MOCK", "text p=$p i=$i ${t.take(40)}") } }
        if (startAtBottom) {
            delay(600)
            lists[startPage].scrollToItem(3)
        }
        var last = ""
        while (true) {
            delay(100)
            val p = pager.currentPage
            val moving = pager.isScrollInProgress || lists[p].isScrollInProgress
            // The navigation bar's height is read each time: it is not known yet when the screen first draws.
            val bottom = view.rootView.height - navBars.getBottom(density)
            val b = StringBuilder("pos page=$p moving=${if (moving) 1 else 0} bar=$barBottom bottom=$bottom")
            for (i in DAYS[p].paras.indices) {
                val a = positions["$p:$i"] ?: continue
                b.append(' ').append(i).append('=').append(a[0]).append(':').append(a[1])
            }
            val line = b.toString()
            if (line != last) {
                Log.i("MOCK", line)
                last = line
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFFFFBF5))) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 2,
        ) { p ->
            DayPage(DAYS[p], lists[p]) { i, top, bottom -> positions["$p:$i"] = intArrayOf(top, bottom) }
        }
        TopAppBar(
            title = {
                if (collapsed) {
                    Row(Modifier.clickable { }, verticalAlignment = Alignment.CenterVertically) {
                        Text(DAYS[pager.currentPage].title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Arrow Drop Down")
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Arrow Back") }
            },
            actions = {
                IconButton(onClick = { }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Videos") }
                IconButton(onClick = { }) { Icon(Icons.Filled.MoreVert, contentDescription = "more") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xF2FFFBF5)),
            modifier = Modifier.onGloballyPositioned { barBottom = (it.positionInWindow().y + it.size.height).roundToInt() },
        )
    }
}

@Composable
fun DayPage(day: Day, state: LazyListState, report: (Int, Int, Int) -> Unit) {
    val screenH = LocalConfiguration.current.screenHeightDp
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        item(key = "cover") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((screenH * 0.5f).dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF34405E), Color(0xFF8C6A3A)))),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectionContainer { Text(day.date.uppercase(), color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp) }
                    SelectionContainer { Text("​" + day.title, color = Color.White, fontSize = 30.sp, lineHeight = 32.sp) }
                }
            }
        }
        // All paragraphs in one block, as the real app does.
        item(key = "blocks") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                day.paras.forEachIndexed { i, t ->
                    SelectionContainer {
                        Text(
                            "​" + t,
                            color = Color(0xFF2B2622),
                            fontSize = 18.sp,
                            lineHeight = 28.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .onGloballyPositioned { c ->
                                    val y = c.positionInWindow().y.roundToInt()
                                    report(i, y, y + c.size.height)
                                },
                        )
                    }
                }
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(64.dp)) }
        item(key = "spacer-system") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars)) }
    }
}
