package org.WenuLink.adapters.mission

import org.WenuLink.adapters.aircraft.Coordinates3D

sealed class MissionNode(
    val coordinates3D: Coordinates3D,
    val actions: MutableList<MissionActionCommand> = mutableListOf()
) {
    class Takeoff(takeoffCoordinates3D: Coordinates3D) : MissionNode(takeoffCoordinates3D)
    class Land(landCoordinates3D: Coordinates3D) : MissionNode(landCoordinates3D)
    class Waypoint(wpCoordinates3D: Coordinates3D) : MissionNode(wpCoordinates3D)
}
