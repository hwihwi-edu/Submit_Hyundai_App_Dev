package com.team.travelmap.story

/** 저장된 볼륨이 범위를 벗어나도 MediaPlayer에는 유효한 값만 전달한다. */
internal fun normalizedStoryVolume(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
