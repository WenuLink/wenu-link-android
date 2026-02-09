package org.WenuLink.adapters.mission

data class AssembledMission(
    val nodes: List<MissionNode>,
    val nWaypoints: Int,
    val rtlWhenFinish: Boolean
)
