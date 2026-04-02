package org.WenuLink.adapters.mission

import org.WenuLink.adapters.aircraft.Coordinates3D

class MissionAssembler {
    private val nodes = mutableListOf<MissionNode>()
    private var rtlWhenFinish = false
    var nWaypoints = 0
        private set

    fun getNode(nId: Int) = nodes[nId]

    fun hasNodes() = !nodes.isEmpty()

    fun size(): Int = nodes.size

    fun reset() {
        nodes.clear()
        rtlWhenFinish = false
    }

    fun addTakeoff(coordinates: Coordinates3D) {
        nodes.add(MissionNode.Takeoff(coordinates))
    }

    fun addWaypoint(coordinates: Coordinates3D) {
        nodes.add(MissionNode.Waypoint(coordinates))
        nWaypoints += 1
    }

    fun addActionToLast(action: MissionAction) {
        (nodes.lastOrNull() as? MissionNode.Waypoint)
            ?.actions
            ?.add(action)
    }

    fun setRTLWhenFinish() {
        rtlWhenFinish = true
    }

    fun build(): AssembledMission = AssembledMission(nodes.toList(), nWaypoints, rtlWhenFinish)
}
