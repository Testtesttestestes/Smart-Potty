package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

// Data structure representing a confetti particle
class ConfettiParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Color,
    val size: Float,
    var rotation: Float,
    val rotationSpeed: Float,
    val shapeCircle: Boolean
)

@Composable
fun SplashScreen(
    onAnimationComplete: () -> Unit
) {
    val density = LocalDensity.current
    var elapsedTime by remember { mutableStateOf(0f) }
    val particles = remember { mutableStateListOf<ConfettiParticle>() }
    var confettiSpawned by remember { mutableStateOf(false) }

    // Standard high-accuracy loop to coordinate animations over 3.2 seconds
    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        var lastTime = startTime
        while (true) {
            val currTime = System.currentTimeMillis()
            val dt = (currTime - lastTime) / 1000f
            lastTime = currTime
            elapsedTime = (currTime - startTime) / 1000f

            // Trigger confetti blast at 2.0 seconds
            if (elapsedTime >= 2.0f && !confettiSpawned) {
                confettiSpawned = true
                // We will populate particles within the Draw block once actual screen/canvas bounds are known.
            }

            // Update particle positions
            if (confettiSpawned) {
                val gravity = 900f // pixels/sec^2 gravity
                for (p in particles) {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    p.vy += gravity * dt
                    p.rotation += p.rotationSpeed * dt
                }
            }

            if (elapsedTime >= 3.2f) { // Extra 0.2s padding so confetti clears/shows fully
                onAnimationComplete()
                break
            }
            delay(16) // ~60fps smooth loop
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFCFDFE),
                        Color(0xFFF1F4FA)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "ПОДГОТОВКА ГОРШКА...",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF5C6BC0),
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Canvas holding the potty cross-section animation, stream of water, balls, and confetti
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }

                // Once constraints are loaded, make sure particles are populated if confettiTime is reached
                if (confettiSpawned && particles.isEmpty()) {
                    val colors = listOf(
                        Color(0xFFFF1744), // Pinkish red
                        Color(0xFF00E676), // Green
                        Color(0xFF29B6F6), // Sky Blue
                        Color(0xFFFFEA00), // Sunshine Yellow
                        Color(0xFFD500F9), // Neon Purple
                        Color(0xFFFF9100), // Vibrant Orange
                        Color(0xFF00E5FF)  // Bright Cyan
                    )
                    
                    // Left shooter eruption
                    repeat(45) {
                        val angle = Random.nextFloat() * 45f - 60f // -15 to -60 degrees (up and right)
                        val angleRad = Math.toRadians(angle.toDouble())
                        val velocity = Random.nextFloat() * 500f + 500f // 500 to 1000 pixels/sec
                        val vx = (velocity * Math.cos(angleRad)).toFloat()
                        val vy = (velocity * Math.sin(angleRad)).toFloat()

                        particles.add(
                            ConfettiParticle(
                                x = 0f,
                                y = heightPx * 0.7f,
                                vx = vx,
                                vy = vy,
                                color = colors.random(),
                                size = Random.nextFloat() * 12f + 8f,
                                rotation = Random.nextFloat() * 360f,
                                rotationSpeed = Random.nextFloat() * 300f - 150f,
                                shapeCircle = Random.nextBoolean()
                            )
                        )
                    }

                    // Right shooter eruption
                    repeat(45) {
                        val angle = Random.nextFloat() * 45f - 120f // -120 to -165 degrees (up and left)
                        val angleRad = Math.toRadians(angle.toDouble())
                        val velocity = Random.nextFloat() * 500f + 500f // 500 to 1000 pixels/sec
                        val vx = (velocity * Math.cos(angleRad)).toFloat()
                        val vy = (velocity * Math.sin(angleRad)).toFloat()

                        particles.add(
                            ConfettiParticle(
                                x = widthPx,
                                y = heightPx * 0.7f,
                                vx = vx,
                                vy = vy,
                                color = colors.random(),
                                size = Random.nextFloat() * 12f + 8f,
                                rotation = Random.nextFloat() * 360f,
                                rotationSpeed = Random.nextFloat() * 300f - 150f,
                                shapeCircle = Random.nextBoolean()
                            )
                        )
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2
                    val cy = size.height / 2

                    // Converting dimensions to precise pixels for drawing
                    val pWidth = 180.dp.toPx()
                    val pHeight = 140.dp.toPx()
                    val innerWidth = 110.dp.toPx()
                    val innerHeight = 70.dp.toPx()

                    // Path representing the inner bowl (crucial for liquid clipping)
                    val innerBowlPath = Path().apply {
                        moveTo(cx - innerWidth / 2, cy - 20.dp.toPx())
                        cubicTo(
                            cx - innerWidth / 2 + 10.dp.toPx(), cy + innerHeight - 10.dp.toPx(),
                            cx + innerWidth / 2 - 10.dp.toPx(), cy + innerHeight - 10.dp.toPx(),
                            cx + innerWidth / 2, cy - 20.dp.toPx()
                        )
                        lineTo(cx + innerWidth / 2, cy - 20.dp.toPx())
                        lineTo(cx - innerWidth / 2, cy - 20.dp.toPx())
                        close()
                    }

                    // Path representing the full outer potty structure in cross-section
                    val outerPottyPath = Path().apply {
                        // Left backrest/handle curve
                        moveTo(cx - pWidth / 2, cy - 40.dp.toPx())
                        // Curve to bottom left foot
                        quadraticBezierTo(
                            cx - pWidth / 2 - 10.dp.toPx(), cy + pHeight / 2,
                            cx - pWidth / 2 + 15.dp.toPx(), cy + pHeight / 2
                        )
                        // Bottom support line (with a little arc in the middle for feet)
                        lineTo(cx - innerWidth / 2, cy + pHeight / 2)
                        quadraticBezierTo(
                            cx, cy + pHeight / 2 - 15.dp.toPx(),
                            cx + innerWidth / 2, cy + pHeight / 2
                        )
                        lineTo(cx + pWidth / 2 - 15.dp.toPx(), cy + pHeight / 2)
                        // Curve to top right seat rim
                        quadraticBezierTo(
                            cx + pWidth / 2 + 10.dp.toPx(), cy + pHeight / 2,
                            cx + pWidth / 2, cy - 20.dp.toPx()
                        )
                        // Rim line
                        lineTo(cx + innerWidth / 2 + 5.dp.toPx(), cy - 25.dp.toPx())
                        lineTo(cx + innerWidth / 2, cy - 20.dp.toPx())
                        // Cut inside down into inner bowl curve shape
                        cubicTo(
                            cx + innerWidth / 2 - 10.dp.toPx(), cy + innerHeight - 10.dp.toPx(),
                            cx - innerWidth / 2 + 10.dp.toPx(), cy + innerHeight - 10.dp.toPx(),
                            cx - innerWidth / 2, cy - 20.dp.toPx()
                        )
                        // Left rim line to backrest top
                        lineTo(cx - innerWidth / 2 - 5.dp.toPx(), cy - 25.dp.toPx())
                        close()
                    }

                    // 1. Draw Potty structural body background (subtle transparency representing translucent light-blue plastic)
                    drawPath(
                        path = outerPottyPath,
                        color = Color(0x1F26A69A) // Semi-transparent cute teal
                    )

                    // Draw the potty contours (white/grey outlines for technical cross-section style)
                    drawPath(
                        path = outerPottyPath,
                        color = Color(0xFFA0BEC4),
                        style = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round)
                    )

                    // 2. Liquid logic
                    // Liquid fills from t = 0s to 1.2s
                    val fillProgress = (elapsedTime / 1.2f).coerceIn(0f, 1f)
                    val liquidMaxHeight = cy + innerHeight - 15.dp.toPx()
                    val liquidMinHeight = cy + innerHeight - 10.dp.toPx()
                    val liquidLevelY = liquidMinHeight - fillProgress * (innerHeight - 15.dp.toPx())

                    // Define yellow liquid paint
                    val liquidColor = Color(0xFFFFD54F) // Cute golden yellow

                    clipPath(innerBowlPath) {
                        if (fillProgress > 0f) {
                            // Dynamic surface wave effect inside the container
                            val waveOffset = (elapsedTime * 8f)
                            val wavyPath = Path().apply {
                                val startY = liquidLevelY + sin(waveOffset) * 4.dp.toPx()
                                moveTo(cx - innerWidth, startY)
                                
                                // Draw a series of wavy lines across
                                for (xCoord in (cx - innerWidth).toInt()..(cx + innerWidth).toInt() step 10) {
                                    val localY = liquidLevelY + sin((xCoord * 0.05f) + waveOffset) * 3.dp.toPx()
                                    lineTo(xCoord.toFloat(), localY)
                                }
                                lineTo(cx + innerWidth, cy + pHeight)
                                lineTo(cx - innerWidth, cy + pHeight)
                                close()
                            }
                            drawPath(
                                path = wavyPath,
                                color = liquidColor
                            )
                        }
                    }

                    // 3. Falling Liquid Stream (trickling down from top screen to water level during fill)
                    if (fillProgress < 1.0f) {
                        val streamAlpha = (1.0f - fillProgress).coerceIn(0f, 1f)
                        val streamWidth = (4.dp.toPx() * (1f - fillProgress)).coerceAtLeast(1.5f.dp.toPx())
                        val streamSine = sin(elapsedTime * 25f) * 2.dp.toPx()
                        
                        drawLine(
                            color = liquidColor.copy(alpha = streamAlpha * 0.9f),
                            start = Offset(cx + streamSine, cy - 140.dp.toPx()),
                            end = Offset(cx + streamSine, liquidLevelY - 2.dp.toPx()),
                            strokeWidth = streamWidth,
                            cap = StrokeCap.Round
                        )
                    }

                    // 4. Brown balls logic ("коричневые шарики добавляются сверху")
                    // We define three balls settling over t = 1.0s to 2.0s
                    val ballColor = Color(0xFF795548) // Rich cocoa brown
                    val ballOutlineColor = Color(0xFF5D4037)
                    val ballRadius = 9.dp.toPx()

                    // Ball 1: drops from t = 1.0s to 1.5s. Rests slightly left.
                    if (elapsedTime >= 1.0f) {
                        val ballProgress = ((elapsedTime - 1.0f) / 0.5f).coerceIn(0f, 1f)
                        val startY = cy - 120.dp.toPx()
                        val endY = liquidLevelY - 2.dp.toPx() // Float on liquid surface
                        val currentX = cx - 18.dp.toPx()
                        val currentY = startY + ballProgress * (endY - startY)

                        drawCircle(
                            color = ballColor,
                            radius = ballRadius,
                            center = Offset(currentX, currentY)
                        )
                        drawCircle(
                            color = ballOutlineColor,
                            radius = ballRadius,
                            center = Offset(currentX, currentY),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        // Add cute specular highlight
                        drawCircle(
                            color = Color.White.copy(alpha = 0.5f),
                            radius = ballRadius * 0.3f,
                            center = Offset(currentX - ballRadius * 0.3f, currentY - ballRadius * 0.3f)
                        )
                    }

                    // Ball 2: drops from t = 1.3s to 1.8s. Rests slightly right.
                    if (elapsedTime >= 1.3f) {
                        val ballProgress = ((elapsedTime - 1.3f) / 0.5f).coerceIn(0f, 1f)
                        val startY = cy - 120.dp.toPx()
                        val endY = liquidLevelY - 1.dp.toPx()
                        val currentX = cx + 22.dp.toPx()
                        val currentY = startY + ballProgress * (endY - startY)

                        drawCircle(
                            color = ballColor,
                            radius = ballRadius,
                            center = Offset(currentX, currentY)
                        )
                        drawCircle(
                            color = ballOutlineColor,
                            radius = ballRadius,
                            center = Offset(currentX, currentY),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.5f),
                            radius = ballRadius * 0.3f,
                            center = Offset(currentX - ballRadius * 0.3f, currentY - ballRadius * 0.3f)
                        )
                    }

                    // Ball 3: drops from t = 1.6s to 2.0s. Rests on top / middle.
                    if (elapsedTime >= 1.6f) {
                        val ballProgress = ((elapsedTime - 1.6f) / 0.4f).coerceIn(0f, 1f)
                        val startY = cy - 120.dp.toPx()
                        // Rests slightly above water line nested on the first two balls
                        val endY = liquidLevelY - ballRadius - 2.dp.toPx()
                        val currentX = cx + 2.dp.toPx()
                        val currentY = startY + ballProgress * (endY - startY)

                        drawCircle(
                            color = ballColor,
                            radius = ballRadius,
                            center = Offset(currentX, currentY)
                        )
                        drawCircle(
                            color = ballOutlineColor,
                            radius = ballRadius,
                            center = Offset(currentX, currentY),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.5f),
                            radius = ballRadius * 0.3f,
                            center = Offset(currentX - ballRadius * 0.3f, currentY - ballRadius * 0.3f)
                        )
                    }

                    // 5. Confetti Rendering
                    if (confettiSpawned) {
                        for (p in particles) {
                            if (p.x < -50 || p.x > size.width + 50 || p.y > size.height + 50) continue
                            
                            val path = Path().apply {
                                if (p.shapeCircle) {
                                    addOval(
                                        Rect(
                                            offset = Offset(p.x - p.size / 2, p.y - p.size / 2),
                                            size = Size(p.size, p.size)
                                        )
                                    )
                                } else {
                                    // Rectangle particle with rotation
                                    addRect(
                                        Rect(
                                            offset = Offset(p.x - p.size / 2, p.y - p.size / 3),
                                            size = Size(p.size, p.size * 0.6f)
                                        )
                                    )
                                }
                            }

                            // Perform rotation matrix operation on the path
                            val matrix = Matrix().apply {
                                reset()
                                translate(p.x, p.y)
                                rotateZ(p.rotation)
                                translate(-p.x, -p.y)
                            }
                            path.transform(matrix)

                            drawPath(
                                path = path,
                                color = p.color
                            )
                        }
                    }
                }
            }
        }
    }
}
