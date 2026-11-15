package com.team.travelmap

import com.team.travelmap.core.trip.IcTurn
import com.team.travelmap.core.trip.UpcomingTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpcomingTurnTest {
    @Test fun `IC 진출까지 거리는 앞 회전부터의 거리를 누적한다`() {
        val turns = listOf(
            UpcomingTurn(null, 80.0),
            UpcomingTurn(IcTurn.ENTER, 1_700.0),
            UpcomingTurn(null, 9_300.0),
            UpcomingTurn(IcTurn.EXIT, 8_000.0),
        )
        assertEquals(19_080, UpcomingTurn.metersTo(turns, IcTurn.EXIT))
        assertEquals(1_780, UpcomingTurn.metersTo(turns, IcTurn.ENTER))
        assertNull(UpcomingTurn.metersTo(turns.take(2), IcTurn.EXIT))
    }
}
