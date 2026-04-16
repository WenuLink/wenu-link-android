package org.WenuLink.mavlink.messages

import com.MAVLink.common.msg_command_long

/**
 * Message bindings for
 * [MAV_CMD_DO_SET_MODE](https://mavlink.io/en/messages/common.html#MAV_CMD_DO_SET_MODE).
 *
 * @param mode          Mode flags. [com.MAVLink.enums.MAV_MODE] values can be used to set some mode flag combinations.
 * @param customMode    Custom system-specific mode (see target autopilot specifications for mode
 *                      information). If MAV_MODE_FLAG_CUSTOM_MODE_ENABLED is set in param1 (mode)
 *                      this mode is used: otherwise the field is ignored.
 * @param customSubMode Custom sub mode - this is system specific, please refer to the individual
 *                      autopilot specifications for details.
 */
data class DoSetModeCommandLong(val mode: Int, val customMode: Long, val customSubMode: Long) {
    constructor(msg: msg_command_long) : this(
        mode = msg.param1.toInt(),
        customMode = msg.param2.toLong(),
        customSubMode = msg.param3.toLong()
    )
}

/**
 * Message bindings for
 * [MAV_CMD_COMPONENT_ARM_DISARM](https://mavlink.io/en/messages/common.html#MAV_CMD_COMPONENT_ARM_DISARM).
 *
 * @param arm   Arm (MAV_BOOL_FALSE: disarm). Values not equal to 0 or 1 are invalid.
 * @param force 0: arm-disarm unless prevented by safety checks (i.e. when landed), 21196: force
 *              arming/disarming (e.g. allow arming to override preflight checks and disarming in
 *              flight)
 */
data class ComponentArmDisarmCommandLong(val arm: Boolean?, val force: Int) {
    constructor(msg: msg_command_long) : this(
        arm = MessageUtils.toBoolean(msg.param1),
        force = msg.param2.toInt()
    )
}
