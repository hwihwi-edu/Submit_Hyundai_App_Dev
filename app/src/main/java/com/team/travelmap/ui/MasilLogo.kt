package com.team.travelmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 담당: 1번(화면). 헤더 "마실" 옆 심볼(Codex 승인 시안 national-map-approved 참고): 겹친 산 두 개 + 구불구불한 초록 길.
 * 그림 파일이 아니라 벡터로 그려 투명 배경·주간/야간 색·창 크기에 맞게 깨지지 않는다.
 * 가로:세로 = 100:64. 아래쪽 끝이 글자 기준선에 오도록 높이를 글자 크기에 맞춰 쓴다.
 */
@Composable
internal fun MasilSymbol(modifier: Modifier, pathColor: Color, background: Color) {
    // 산은 길 색을 바탕색 쪽으로 옅게 섞어 쓴다(야간에도 대비 유지).
    val farMountain = lerp(pathColor, background, 0.45f)
    val nearMountain = lerp(pathColor, background, 0.25f)
    Canvas(modifier.semantics { contentDescription = "" }) {
        val sx = size.width / 100f
        val sy = size.height / 64f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        // 뒤 큰 산(오른쪽)
        drawPath(Path().apply {
            moveTo(p(52f, 34f).x, p(52f, 34f).y); lineTo(p(76f, 4f).x, p(76f, 4f).y); lineTo(p(100f, 34f).x, p(100f, 34f).y); close()
        }, farMountain)
        // 앞 작은 산(왼쪽)
        drawPath(Path().apply {
            moveTo(p(36f, 34f).x, p(36f, 34f).y); lineTo(p(52f, 12f).x, p(52f, 12f).y); lineTo(p(68f, 34f).x, p(68f, 34f).y); close()
        }, nearMountain)
        // 구불구불한 길: 산 아래에서 왼쪽으로 돌아 S 자로 내려와 기준선 왼쪽 끝으로
        val road = Path().apply {
            moveTo(p(64f, 35f).x, p(64f, 35f).y)
            cubicTo(p(42f, 33f).x, p(42f, 33f).y, p(20f, 38f).x, p(20f, 38f).y, p(30f, 46f).x, p(30f, 46f).y)
            cubicTo(p(38f, 52f).x, p(38f, 52f).y, p(54f, 53f).x, p(54f, 53f).y, p(42f, 59f).x, p(42f, 59f).y)
            cubicTo(p(34f, 62f).x, p(34f, 62f).y, p(18f, 61f).x, p(18f, 61f).y, p(6f, 61f).x, p(6f, 61f).y)
        }
        drawPath(road, pathColor, style = Stroke(width = 7.5f * sy, cap = StrokeCap.Round))
    }
}
