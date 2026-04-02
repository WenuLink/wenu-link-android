package org.WenuLink.parameters

import com.MAVLink.enums.MAV_PARAM_TYPE

/**
 * ArduPilot parameters take for functionality with GCS
 */
object ArduPilotParametersProvider : ParameterProvider {
    override fun provide(): List<ParameterSpec> {
        /* ---------- internal storage ---------- */
        val values = mutableMapOf<String, ParamValue>()

        fun numberParam(
            name: String,
            type: Int,
            semantic: SemanticType,
            reader: ((ParamValue?) -> Unit) -> Unit
        ) = SimpleParameter(
            name,
            type,
            semantic,
            reader
        ) { v, cb ->
            values[name] = v
            cb(null)
        }

        fun intParam(name: String, initial: Int) = numberParam(
            name,
            MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT32,
            SemanticType.INT
        ) { cb -> cb(values[name] ?: ParamValue.IntVal(initial)) }

        fun floatParam(name: String, initial: Float) = numberParam(
            name,
            MAV_PARAM_TYPE.MAV_PARAM_TYPE_REAL32,
            SemanticType.FLOAT
        ) { cb -> cb(values[name] ?: ParamValue.FloatVal(initial)) }

        fun int8Param(name: String, initial: Int) = numberParam(
            name,
            MAV_PARAM_TYPE.MAV_PARAM_TYPE_INT8,
            SemanticType.INT
        ) { cb -> cb(values[name] ?: ParamValue.IntVal(initial)) }

        fun boolParam(name: String, initial: Boolean) = SimpleParameter(
            name,
            MAV_PARAM_TYPE.MAV_PARAM_TYPE_UINT8,
            SemanticType.BOOL,
            { cb ->
                cb(values[name] ?: ParamValue.BoolVal(initial))
            },
            { v, cb ->
                values[name] = ParamValue.BoolVal((v as ParamValue.BoolVal).v)
                cb(null)
            }
        )

        return listOf(
            /* ===============================
             * RC MAP
             * =============================== */
            intParam("RCMAP_ROLL", 1),
            intParam("RCMAP_PITCH", 2),
            intParam("RCMAP_YAW", 3),
            intParam("RCMAP_THROTTLE", 4),

            /* ===============================
             * RC CHANNEL 0
             * =============================== */
            intParam("RC0_MIN", 1000),
            intParam("RC0_MAX", 2000),
            intParam("RC0_TRIM", 0),
            boolParam("RC0_REV", false),

            /* ===============================
             * RC CHANNEL 1
             * =============================== */
            intParam("RC1_MIN", 1000),
            intParam("RC1_MAX", 2000),
            intParam("RC1_TRIM", 0),
            boolParam("RC1_REV", false),

            /* ===============================
             * RC CHANNEL 2
             * =============================== */
            intParam("RC2_MIN", 1000),
            intParam("RC2_MAX", 2000),
            intParam("RC2_TRIM", 0),
            boolParam("RC2_REV", false),

            /* ===============================
             * RC CHANNEL 3
             * =============================== */
            intParam("RC3_MIN", 1000),
            intParam("RC3_MAX", 2000),
            intParam("RC3_TRIM", 0),
            boolParam("RC3_REV", false),

            /* ===============================
             * RC CHANNEL 4
             * =============================== */
            intParam("RC4_MIN", 1000),
            intParam("RC4_MAX", 2000),
            intParam("RC4_TRIM", 0),
            boolParam("RC4_REV", false),

            /* ===============================
             * COMPASS
             * =============================== */
            int8Param("COMPASS_ENABLE", 1),

            intParam("COMPASS_DEV_ID", 97539),
            int8Param("COMPASS_USE", 1),
            int8Param("COMPASS_ORIENT", 0),
            int8Param("COMPASS_EXTERNAL", 1),
            intParam("COMPASS_OFS_X", 5),
            intParam("COMPASS_OFS_Y", 13),
            intParam("COMPASS_OFS_Z", -18),
            floatParam("COMPASS_DIA_X", 1f),
            floatParam("COMPASS_DIA_Y", 1f),
            floatParam("COMPASS_DIA_Z", 1f),
            floatParam("COMPASS_ODI_X", 0f),
            floatParam("COMPASS_ODI_Y", 0f),
            floatParam("COMPASS_ODI_Z", 0f),

            intParam("COMPASS_DEV_ID2", 131874),
            int8Param("COMPASS_USE2", 1),
            int8Param("COMPASS_ORIENT2", 1),
            int8Param("COMPASS_EXTERN2", 1),
            intParam("COMPASS_OFS2_X", 5),
            intParam("COMPASS_OFS2_Y", 13),
            intParam("COMPASS_OFS2_Z", -18),
            floatParam("COMPASS_DIA2_X", 1f),
            floatParam("COMPASS_DIA2_Y", 1f),
            floatParam("COMPASS_DIA2_Z", 1f),
            floatParam("COMPASS_ODI2_X", 0f),
            floatParam("COMPASS_ODI2_Y", 0f),
            floatParam("COMPASS_ODI2_Z", 0f),

            intParam("COMPASS_DEV_ID3", 263178),
            int8Param("COMPASS_USE3", 1),
            int8Param("COMPASS_ORIENT3", 1),
            int8Param("COMPASS_EXTERN3", 1),
            intParam("COMPASS_OFS3_X", 5),
            intParam("COMPASS_OFS3_Y", 13),
            intParam("COMPASS_OFS3_Z", -18),
            floatParam("COMPASS_DIA3_X", 1f),
            floatParam("COMPASS_DIA3_Y", 1f),
            floatParam("COMPASS_DIA3_Z", 1f),
            floatParam("COMPASS_ODI3_X", 0f),
            floatParam("COMPASS_ODI3_Y", 0f),
            floatParam("COMPASS_ODI3_Z", 0f),

            floatParam("COMPASS_DEC", 0f),
            int8Param("COMPASS_LEARN", 0),
            int8Param("COMPASS_AUTODEC", 1),

            /* ===============================
             * FLIGHT MODES
             * =============================== */
            intParam("FLTMODE1", 4),
            intParam("FLTMODE2", 0),
            intParam("FLTMODE3", 5),
            intParam("FLTMODE4", 9),
            intParam("FLTMODE5", 3),
            intParam("FLTMODE6", 6),

            /* ===============================
             * BATTERY / SAFETY
             * =============================== */
            intParam("BATT_MONITOR", 0),
            intParam("ARMING_CHECK", 0),

            /* ===============================
             * AHRS
             * =============================== */
            intParam("AHRS_ORIENTATION", 0),
            floatParam("AHRS_GPS_GAIN", 1f),
            int8Param("AHRS_GPS_USE", 1),
            int8Param("AHRS_GPS_MINSATS", 6),
            int8Param("AHRS_EKF_TYPE", 10),
            int8Param("AHRS_OPTIONS", 0),
            int8Param("EAHRS_TYPE", 0),

            /* ===============================
             * INS_ACC
             * =============================== */
            floatParam("INS_STILL_THRESH", 2.5f),
            int8Param("INS_TRIM_OPTION", 1),
            int8Param("INS_FAST_SAMPLE", 1),
            int8Param("INS_ENABLE_MASK", 127),

            int8Param("INS_ACC_TRIMOPTION", 1),
            int8Param("INS_ACC_BODYFIX", 2),

            intParam("INS_ACC_ID", 2753028),
            int8Param("INS_USE", 1),
            floatParam("INS_POS1_X", 0f),
            floatParam("INS_POS1_Y", 0f),
            floatParam("INS_POS1_Z", 0f),
            floatParam("INS_ACCSCAL_X", 1.001f),
            floatParam("INS_ACCSCAL_Y", 1.001f),
            floatParam("INS_ACCSCAL_Z", 1.001f),
            floatParam("INS_ACCOFFS_X", 0.001f),
            floatParam("INS_ACCOFFS_Y", 0.001f),
            floatParam("INS_ACCOFFS_Z", 0.001f),
            floatParam("INS_ACC1_CALTEMP", -300f),

            intParam("INS_ACC2_ID", 2753036),
            int8Param("INS_USE2", 1),
            floatParam("INS_POS2_X", 0f),
            floatParam("INS_POS2_Y", 0f),
            floatParam("INS_POS2_Z", 0f),
            floatParam("INS_ACC2SCAL_X", 1.001f),
            floatParam("INS_ACC2SCAL_Y", 1.001f),
            floatParam("INS_ACC2SCAL_Z", 1.001f),
            floatParam("INS_ACC2OFFS_X", 0.001f),
            floatParam("INS_ACC2OFFS_Y", 0.001f),
            floatParam("INS_ACC2OFFS_Z", 0.001f),
            floatParam("INS_ACC2_CALTEMP", -300f),

            intParam("INS_ACC3_ID", 0),
            int8Param("INS_USE3", 1),
            floatParam("INS_POS3_X", 0f),
            floatParam("INS_POS3_Y", 0f),
            floatParam("INS_POS3_Z", 0f),
            floatParam("INS_ACC3SCAL_X", 1f),
            floatParam("INS_ACC3SCAL_Y", 1f),
            floatParam("INS_ACC3SCAL_Z", 1f),
            floatParam("INS_ACC3OFFS_X", 0f),
            floatParam("INS_ACC3OFFS_Y", 0f),
            floatParam("INS_ACC3OFFS_Z", 0f),
            floatParam("INS_ACC3_CALTEMP", -300f),

            /* ===============================
             * INS_GYRO
             * =============================== */
            int8Param("INS_GYRO_RATE", 0),
            int8Param("INS_GYRO_CAL", 0),

            intParam("INS_GYR_ID", 2752772),
            floatParam("INS_GYRO1_CALTEMP", -300f),
            floatParam("INS_GYROFFS_X", 0f),
            floatParam("INS_GYROFFS_Y", 0f),
            floatParam("INS_GYROFFS_Z", 0f),

            intParam("INS_GYR2_ID", 2752780),
            floatParam("INS_GYRO2_CALTEMP", -300f),
            floatParam("INS_GYR2OFFS_X", 0f),
            floatParam("INS_GYR2OFFS_Y", 0f),
            floatParam("INS_GYR2OFFS_Z", 0f),

            intParam("INS_GYR3_ID", 0),
            floatParam("INS_GYRO3_CALTEMP", -300f),
            floatParam("INS_GYRO3FFS_X", 0f),
            floatParam("INS_GYRO3FFS_Y", 0f),
            floatParam("INS_GYRO3FFS_Z", 0f),

            /* ===============================
             * INS_TCAL
             * =============================== */
            int8Param("TCAL_ENABLED", 0),
            boolParam("INS_TCAL1_ENABLE", false),
            boolParam("INS_TCAL2_ENABLE", false),
            boolParam("INS_TCAL3_ENABLE", false),
            intParam("INS_TCAL_OPTIONS", 0),

            /* ===============================
             * BAROMETER
             * =============================== */
            intParam("BARO1_DEVID", 65540),
            intParam("BARO2_DEVID", 65796),
            intParam("BARO3_DEVID", 0),
            floatParam("BARO_ALT_OFFSET", 0f),
            floatParam("BARO1_GND_PRESS", 101035f),
            floatParam("BARO2_GND_PRESS", 101035f),

            /* ===============================
             * CALIBRATION STATUS?
             * =============================== */
            floatParam("COMPASS_CAL_FIT", 16f),
            int8Param("ESC_CALIBRATION", 0),
            int8Param("EK3_MAG_CAL", 3),
            int8Param("EK3_SRC1_POSXY", 3),
            intParam("STAT_RUNTIME", 43),
            intParam("FORMAT_VERSION", 120)
        )
    }
}
