package com.team.travelmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.team.travelmap.curation.hot.HotPlaces

/**
 * 3번 "핫" 배지(작은 알약). 핫이 아니거나 설정에서 끄면 그리지 않는다.
 * 시연용 예시 응답이 섞였으면 글자를 "핫 · 예시"로, 모양도 테두리만(속이 빈) 배지로 바꿔 실제 리뷰와 구분한다.
 * 이모지는 차량 글꼴에 없을 수 있어 아이콘을 쓴다.
 */
@Composable
internal fun HotBadge(stat: HotPlaces.HotStat?, enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled || stat == null || !stat.isHot) return
    val colors = LocalChaumColors.current
    val sample = stat.includesSample
    val fg = if (sample) colors.error else colors.onPrimary
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = "핫: ${stat.label}" }
            .then(if (sample) Modifier.border(1.dp, colors.error, shape) else Modifier.background(colors.error, shape))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (sample) "핫 · 예시" else "핫", style = LocalChaumType.current.label, color = fg)
    }
}
