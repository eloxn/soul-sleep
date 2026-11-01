package app.glyph

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val Void = Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF181818), Color(0xFF050505)))
private val Bone = Color(0xFFEDEDED); private val Ash = Color(0xFF7C7C7C)

/** A blob whose silhouette is a sum of rotating harmonics: calm when idle, thrashing while fetching,
 *  collapsed to a tight sphere (with a countdown) during the 60s rest. Drag it to tilt it in 3D. */
@Composable
fun Orb(busy: Boolean, rest: Int, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "t").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "p")
    val amp by animateFloatAsState(when { rest > 0 -> 0.02f; busy -> 0.30f; else -> 0.11f }, spring(0.45f, 40f), label = "a")
    val shrink by animateFloatAsState(if (rest > 0) 0.6f else 1f, spring(stiffness = 60f), label = "s")
    var tilt by remember { mutableStateOf(Offset.Zero) }
    val tx by animateFloatAsState(tilt.x, spring(stiffness = 80f), label = "tx")
    val ty by animateFloatAsState(tilt.y, spring(stiffness = 80f), label = "ty")
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures(onDragEnd = { tilt = Offset.Zero }) { _, d ->
                tilt = Offset((tilt.x + d.x * .15f).coerceIn(-35f, 35f), (tilt.y + d.y * .15f).coerceIn(-35f, 35f))
            }
        }.graphicsLayer { rotationY = tx; rotationX = -ty; cameraDistance = 14f * density; scaleX = shrink; scaleY = shrink },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center; val r0 = size.minDimension * .36f; val n = 120
            for (layer in 3 downTo 0) {            // 3 ghost outlines trail behind the solid core -> depth
                val path = Path()
                for (i in 0..n) {
                    val a = i * 2 * PI.toFloat() / n
                    val k = 1f + amp * (.55f * sin(3 * a + t + layer * .5f) + .35f * sin(5 * a - 1.3f * t) + .2f * sin(8 * a + 2 * t))
                    val r = r0 * k * (1f - layer * .07f)
                    val x = c.x + cos(a) * r; val y = c.y + sin(a) * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                if (layer == 0) drawPath(path, Brush.radialGradient(
                    listOf(Color.White, Color(0xFFBEBEBE), Color(0xFF2A2A2A), Color.Black),
                    center = Offset(c.x - r0 * .3f + tx * 2, c.y - r0 * .35f - ty * 2), radius = r0 * 1.5f))
                else drawPath(path, Color.White.copy(alpha = .05f * layer), style = Stroke(1.5f * density))
            }
        }
        if (rest > 0) Text("$rest", color = Color.Black, fontSize = 44.sp, fontWeight = FontWeight.Thin)
    }
}

@Composable
fun Screen(vm: Vm) {
    val tracks by vm.tracks.collectAsState(); val st by vm.status.collectAsState()
    val rest by vm.rest.collectAsState(); val served by vm.served.collectAsState()
    val ls = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(Void).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(24.dp, 16.dp, 24.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("soul sleep", color = Bone, fontSize = 32.sp, fontWeight = FontWeight.Thin, letterSpacing = 3.sp)
                val folder by vm.folderName.collectAsState()
                Text(
                    text = if (folder != null) "FOLDER: $folder" else "SELECT MUSIC FOLDER",
                    color = Ash,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { vm.requestPickFolder() }.padding(vertical = 2.dp)
                )
            }
            Text("ADD ALL", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Color.White, Color(0xFF8E8E8E))))
                    .clickable { vm.addAll() }.padding(16.dp, 10.dp))
        }
        Orb(st.values.any { it is St.Work }, rest, Modifier.fillMaxWidth().height(250.dp))
        Text(if (rest > 0) "cooling down — resuming in ${rest}s" else "${served % 50} / 50 before rest", color = Ash, fontSize = 12.sp,
            letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (tracks.isEmpty()) {
            val folder by vm.folderName.collectAsState()
            Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (folder == null) "TAP HERE TO SELECT MUSIC FOLDER" else "NO SONGS FOUND IN $folder",
                    color = Ash,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.clickable { vm.requestPickFolder() }
                )
            }
        } else {
            LazyColumn(state = ls, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                    val tilt by remember { derivedStateOf {   // rows lean away from the viewport centre like a rotating drum
                        val li = ls.layoutInfo; val v = li.visibleItemsInfo.firstOrNull { x -> x.index == i }
                        if (v == null) 0f else {
                            val mid = (li.viewportStartOffset + li.viewportEndOffset) / 2f
                            ((v.offset + v.size / 2f - mid) / (li.viewportEndOffset - li.viewportStartOffset)).coerceIn(-1f, 1f) * -26f
                        }
                    } }
                    Row(
                        Modifier.fillMaxWidth().graphicsLayer { rotationX = tilt; cameraDistance = 12f * density; alpha = 1f - abs(tilt) / 60f }
                            .clip(RoundedCornerShape(22.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF222222), Color(0xFF0A0A0A))))
                            .border(1.dp, Color.White.copy(.1f), RoundedCornerShape(22.dp)).padding(18.dp, 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = Bone, fontSize = 16.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                            Text(t.artist, color = Ash, fontSize = 12.sp, maxLines = 1)
                        }
                        Pill(st[t.id] ?: St.Idle) { vm.add(t) }
                    }
                }
            }
        }
    }
}

/** Pill that morphs into a spinner while working, then into a white badge naming the sync grain. */
@Composable
fun Pill(s: St, onClick: () -> Unit) {
    val w by animateDpAsState(if (s is St.Work) 44.dp else 84.dp, spring(.6f, 300f), label = "w")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.width(w).height(44.dp).clip(shape)
            .background(if (s is St.Done) Brush.horizontalGradient(listOf(Color.White, Color(0xFF9A9A9A))) else Brush.horizontalGradient(listOf(Color(0xFF2E2E2E), Color(0xFF101010))))
            .border(1.dp, Color.White.copy(.25f), shape).clickable(enabled = s !is St.Work, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (s is St.Work) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(when (s) { is St.Done -> if (s.g == Grain.WORD) "WORD" else "LINE"; St.Miss -> "NONE"; St.Fail -> "RETRY"; else -> "ADD" },
            color = if (s is St.Done) Color.Black else Bone, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}
