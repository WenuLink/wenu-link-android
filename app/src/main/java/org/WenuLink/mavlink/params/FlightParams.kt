package org.WenuLink.mavlink.params

import com.MAVLink.common.msg_command_long

/**
 * Parameter bindings for
 * [MAV_CMD_DO_SET_MODE](https://mavlink.io/en/messages/common.html#MAV_CMD_DO_SET_MODE).
 *
 * @param mode          Mode flags. [com.MAVLink.enums.MAV_MODE] values can be used to set some mode flag combinations.
 * @param customMode    Custom system-specific mode (see target autopilot specifications for mode
 *                      information). If MAV_MODE_FLAG_CUSTOM_MODE_ENABLED is set in param1 (mode)
 *                      this mode is used: otherwise the field is ignored.
 * @param customSubMode Custom sub mode - this is system specific, please refer to the individual
 *                      autopilot specifications for details.
 */
data class DoSetModeParams(val mode: Int, val customMode: Long, val customSubMode: Long) {
    companion object {
        fun from(msg: msg_command_long) = DoSetModeParams(
            mode = msg.param1.toInt(),
            customMode = msg.param2.toLong(),
            customSubMode = msg.param3.toLong()
        )
    }
}

/**
 * Parameter bindings for
 * [MAV_CMD_COMPONENT_ARM_DISARM](https://mavlink.io/en/messages/common.html#MAV_CMD_COMPONENT_ARM_DISARM).
 *
 * @param arm   Arm (MAV_BOOL_FALSE: disarm). Values not equal to 0 or 1 are invalid.
 * @param force 0: arm-disarm unless prevented by safety checks (i.e. when landed), 21196: force
 *              arming/disarming (e.g. allow arming to override preflight checks and disarming in
 *              flight)
 */
data class ComponentArmDisarmParams(val arm: Boolean?, val force: Int) {
    companion object {
        fun from(msg: msg_command_long) = ComponentArmDisarmParams(
            arm = ParamUtils.toBoolean(msg.param1),
            force = msg.param2.toInt()
        )
    }
}
