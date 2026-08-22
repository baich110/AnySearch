package com.anysearch.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val SlideUpEnter: EnterTransition = slideInVertically { it } + fadeIn(tween(300, delayMillis = 100))
val SlideUpExit: ExitTransition = slideOutVertically { -it } + fadeOut(tween(200))
val SlideRightEnter: EnterTransition = slideInHorizontally { it } + fadeIn(tween(300, delayMillis = 100))
val SlideLeftExit: ExitTransition = slideOutHorizontally { -it } + fadeOut(tween(200))

fun Modifier.gradientBackground(colors: List<Color>): Modifier = composed {
    drawBehind {
        val brush = Brush.linearGradient(colors)
        drawRect(brush)
    }
}

@Composable
fun FloatingAnimation(offsetY: Dp = 8.dp): Offset {
    val infiniteTransition = rememberInfiniteTransition(label = "floating")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "float"
    )
    return Offset(0f, offsetY.value * offset)
}

@Composable
fun ShimmerBox(modifier: Modifier = Modifier, height: Dp = 16.dp, width: Dp? = null) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by infiniteTransition.animateFloat(
        initialValue = -1000f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "shimmer_translate"
    )
    val shimmerColors = listOf(Color(0x80CCCCCC), Color(0x20CCCCCC), Color(0x80CCCCCC))
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 1000f, 0f),
        end = Offset(translateAnim + 1000f, 0f)
    )
    Canvas(
        modifier = modifier
            .height(height)
            .then(width?.let { Modifier.width(it) } ?: Modifier)
            .clip(MaterialTheme.shapes.small)
    ) { drawRect(brush, size = Size(size.width, size.height)) }
}

@Composable
fun ShimmerLoading() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShimmerBox(height = 48.dp)
        ShimmerBox(height = 16.dp, width = 300.dp)
        ShimmerBox(height = 16.dp)
        ShimmerBox(height = 16.dp, width = 200.dp)
        Spacer(modifier = Modifier.height(16.dp))
        ShimmerBox(height = 100.dp)
        ShimmerBox(height = 16.dp, width = 250.dp)
        ShimmerBox(height = 16.dp)
        ShimmerBox(height = 16.dp, width = 180.dp)
    }
}
