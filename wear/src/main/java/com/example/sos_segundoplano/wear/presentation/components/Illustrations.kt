package com.example.sos_segundoplano.wear.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.wear.presentation.theme.MotoAmber
import com.example.sos_segundoplano.wear.presentation.theme.MotoBlueGray
import com.example.sos_segundoplano.wear.presentation.theme.MotoCardBlue
import com.example.sos_segundoplano.wear.presentation.theme.MotoProgressGreen
import com.example.sos_segundoplano.wear.presentation.theme.MotoWhite

@Composable
internal fun MotorcycleIllustration(modifier: Modifier = Modifier, compact: Boolean = false) {
    Canvas(modifier.size(if (compact) 64.dp else 88.dp)) {
        val unit = size.minDimension
        val tireWidth = 5.dp.toPx()
        val wheelRadius = unit * 0.155f
        val rear = Offset(size.width * 0.22f, size.height * 0.70f)
        val front = Offset(size.width * 0.79f, size.height * 0.70f)
        drawCircle(MotoBlueGray.copy(alpha = 0.20f), wheelRadius * 1.28f, rear)
        drawCircle(MotoBlueGray.copy(alpha = 0.20f), wheelRadius * 1.28f, front)
        drawCircle(MotoWhite, wheelRadius, rear, style = Stroke(tireWidth, cap = StrokeCap.Round))
        drawCircle(MotoWhite, wheelRadius, front, style = Stroke(tireWidth, cap = StrokeCap.Round))
        drawCircle(MotoProgressGreen, wheelRadius * 0.24f, rear)
        drawCircle(MotoProgressGreen, wheelRadius * 0.24f, front)
        val engineTopLeft = Offset(size.width * 0.39f, size.height * 0.53f)
        drawRoundRect(MotoCardBlue, engineTopLeft, androidx.compose.ui.geometry.Size(size.width * 0.22f, size.height * 0.17f), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        drawRoundRect(MotoWhite.copy(alpha = 0.88f), engineTopLeft, androidx.compose.ui.geometry.Size(size.width * 0.22f, size.height * 0.17f), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
        drawLine(MotoProgressGreen, rear, Offset(size.width * 0.47f, size.height * 0.61f), tireWidth, StrokeCap.Round)
        drawLine(MotoProgressGreen, Offset(size.width * 0.47f, size.height * 0.61f), Offset(size.width * 0.62f, size.height * 0.46f), tireWidth, StrokeCap.Round)
        drawLine(MotoProgressGreen, Offset(size.width * 0.62f, size.height * 0.46f), front, tireWidth * 0.8f, StrokeCap.Round)
        drawLine(MotoWhite, Offset(size.width * 0.67f, size.height * 0.40f), front, tireWidth * 0.72f, StrokeCap.Round)
        val tank = Path().apply {
            moveTo(size.width * 0.43f, size.height * 0.42f)
            cubicTo(size.width * 0.49f, size.height * 0.31f, size.width * 0.64f, size.height * 0.31f, size.width * 0.68f, size.height * 0.43f)
            lineTo(size.width * 0.60f, size.height * 0.52f)
            lineTo(size.width * 0.46f, size.height * 0.50f)
            close()
        }
        drawPath(tank, MotoProgressGreen)
        drawPath(tank, MotoWhite.copy(alpha = 0.70f), style = Stroke(1.5.dp.toPx()))
        drawLine(MotoWhite, Offset(size.width * 0.29f, size.height * 0.39f), Offset(size.width * 0.49f, size.height * 0.41f), tireWidth * 1.35f, StrokeCap.Round)
        drawLine(MotoProgressGreen, Offset(size.width * 0.24f, size.height * 0.42f), Offset(size.width * 0.34f, size.height * 0.39f), tireWidth, StrokeCap.Round)
        drawLine(MotoWhite, Offset(size.width * 0.64f, size.height * 0.38f), Offset(size.width * 0.72f, size.height * 0.25f), tireWidth * 0.70f, StrokeCap.Round)
        drawLine(MotoWhite, Offset(size.width * 0.69f, size.height * 0.25f), Offset(size.width * 0.82f, size.height * 0.25f), tireWidth * 0.65f, StrokeCap.Round)
        drawCircle(MotoAmber, unit * 0.045f, Offset(size.width * 0.73f, size.height * 0.35f))
        drawLine(MotoBlueGray, Offset(size.width * 0.51f, size.height * 0.67f), Offset(size.width * 0.72f, size.height * 0.62f), tireWidth * 0.65f, StrokeCap.Round)
        drawLine(MotoWhite.copy(alpha = 0.8f), Offset(size.width * 0.43f, size.height * 0.58f), Offset(size.width * 0.58f, size.height * 0.58f), 1.6.dp.toPx(), StrokeCap.Round)
    }
}
