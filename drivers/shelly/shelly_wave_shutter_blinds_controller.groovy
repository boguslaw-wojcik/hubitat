/**
 *  Shelly Wave Shutter Z-Wave 800 Driver for Hubitat
 *  Date: 03.05.2025
 *	Author: Bogusław Wójcik
 *
 *	CHANGELOG:
 *  - v0.2.0 - 20.09.2026: Support for Z-Wave JS, for the breaking configuration changes introduced in firmware 14.x, and for new parameters.
 * 	- v0.1.2 - 25.06.2025: Minor safeguard against logging level set by different custom driver.
 *  - v0.1.1 - 19.06.2025: Minor fix when reading enumerated configuration params.
 *  - v0.1.0 - 03.05.2025: Initial working version.
 *
 *  DESCRIPTION:
 *  This is a custom driver for Shelly Wave Shutter that is intended to be used instead of the Hubitat in-built drivers.
 *  As of the time of writing, the in-built driver suffers from multiple issues encountered on S2-included devices from EU distribution.
 *
 *  This custom driver fixes following issues:
 *  - shows properly "opening", "closing", "partially open", "open", and "closed" states,
 *  - calibration button works correctly,
 *  - all parameters defined in the manual are configurable, and the configuration is properly saved on the device.
 *
 *  Additionally:
 *  - the driver properly leverages S2 supervision and infers "closing" and "opening" states from acknowledged commands, thus reducing radio traffic,
 *  - upon hitting the "refresh" action shows the current calibration status,
 *  - lifetime energy consumption is shown.
 *
 *  NOTES:
 *  - The driver has been tested on Shelly Wave Shutter from EU distribution module with firmware version 14.02 and securely paired with Hubitat.
 *  - Firmware 12.x numbers several configuration parameters differently; the driver translates them, but that path is no longer covered by testing.
 *
 *  Copyright 2025 Bogusław Wójcik
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is
 *  distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.transform.Field

@Field static final String VERSION = "0.2.0"

metadata {
    definition(
            name: "Shelly Wave Shutter",
            namespace: "boguslaw-wojcik",
            author: "Bogusław Wójcik",
            singleThreaded: true,
            importUrl: "https://github.com/boguslaw-wojcik/hubitat/blob/main/drivers/shelly/shelly_wave_shutter_blinds_controller.groovy"
    ) {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "WindowShade"

        command "calibrate"
        command "reboot"

        fingerprint mfr: "0460", prod: "0003", deviceId: "0082", inClusters: "0x5E,0x9F,0x55,0x6C", secureInClusters: "0x26,0x71,0x85,0x59,0x8E,0x5A,0x87,0x60,0x73,0x86,0x22,0x70,0x7A,0x72,0x32", controllerType: "ZWV", deviceJoinName: "Shelly Wave Shutter"
    }

    preferences {
        configParams.each { param ->
            if (!param.hidden) {
                input param.input
            }
        }
    }
}

//region Specification

//Command class versions specific for the device.
@Field static final Map commandClassVersions = [
        0x22: 2, // COMMAND_CLASS_APPLICATION_STATUS_V2
        0x86: 3, // COMMAND_CLASS_VERSION_V3
        0x73: 1, // COMMAND_CLASS_POWERLEVEL_V1
        0x60: 4, // COMMAND_CLASS_MULTI_CHANNEL_V4
        0x87: 4, // COMMAND_CLASS_INDICATOR_V4
        0x5A: 1, // COMMAND_CLASS_DEVICE_RESET_LOCALLY_V1
        0x8E: 4, // COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION_V4
        0x85: 3, // COMMAND_CLASS_ASSOCIATION_3
        0x59: 3, // COMMAND_CLASS_ASSOCIATION_GRP_INFO_V3
        0x71: 9, // COMMAND_CLASS_NOTIFICATION_V9
        0x26: 4, // COMMAND_CLASS_SWITCH_MULTILEVEL_V4
        0x25: 2, // COMMAND_CLASS_SWITCH_BINARY_V2
        0x6C: 2, // COMMAND_CLASS_SUPERVISION_V2
        0x55: 2, // COMMAND_CLASS_TRANSPORT_SERVICE_V2
        0x72: 2, // COMMAND_CLASS_MANUFACTURER_SPECIFIC_V2
        0x70: 4, // COMMAND_CLASS_CONFIGURATION_V4
        0x5E: 2, // COMMAND_CLASS_ZWAVEPLUS_INFO_V2
        0x32: 5, // COMMAND_CLASS_METER_V5 (v6 report objects fail to construct under Z-Wave JS: MeterReport.setMeterType(Integer))
        0x7A: 7, // COMMAND_CLASS_FIRMWARE_UPDATE_MD_V7
        0x98: 1, // COMMAND_CLASS_SECURITY_V1
        0x9F: 1, // COMMAND_CLASS_SECURITY_2_V1
]

// Specification of configuration parameters.
@Field static final List<Map> configParams = [
        [
                input : [
                        name        : "configParam5",
                        type        : "enum",
                        title       : "Parameter No. 5 - Push-button (momentary) / bistable (toggle switch) selection",
                        description : "With this parameter, you can select between the switch type: push-button (momentary) or on/off toggle switch connected to SW1 and SW2 inputs.<br><b>NOTE:</b> When set = 2, 1x click on SW1 up - 1x click on SW1 stop - 1x click down",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "momentary switch",
                                1: "toggle switch (contact closed - ON / contact opened - OFF)",
                                2: "single, momentary switch (the switch should be connected to SW1 terminal)"
                        ],
                ],
                num   : 5,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam6",
                        type        : "enum",
                        title       : "Parameter No. 6 – Inputs orientation",
                        description : "This parameter allows to reverse the operation of switches connected to SW1 and SW2 inputs without changing the wiring.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "default (SW1 - O1, I2 - O2)",
                                1: "reversed (SW1 - O2, I2 - O1)",
                        ],
                ],
                num   : 6,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam7",
                        type        : "enum",
                        title       : "Parameter No. 7 - SW (SW1) detach mode",
                        description : "In this mode the input SW (SW1) is separated/not changing the state of the output.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "normal mode",
                                1: "detached mode",
                        ],
                ],
                num   : 7,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam8",
                        type        : "enum",
                        title       : "Parameter No. 8 - SW2 detach mode",
                        description : "In this mode the input SW 2 is separated/not changing the state of the output.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "normal mode",
                                1: "detached mode",
                        ],
                ],
                num   : 8,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam16",
                        type        : "enum",
                        title       : "Parameter No. 16 – Output orientation",
                        description : "This parameter allows to reverse the operation of O1 and O2 without changing the wiring (in case of invalid motor connection) to ensure proper operation.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "default (O1 - UP, O2 - DOWN)",
                                1: "reversed (O1 - DOWN, O2 - UP)",
                        ],
                ],
                num   : 16,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam36",
                        type        : "number",
                        title       : "Parameter No. 36 - Power Consumption Reporting",
                        description : "Choose by how much the power (W) consumption needs to increase or decrease to be reported. Values correspond to percentages, so if 50 is set (by default), the Device will report any power consumption changes of 50 % or more, compared to the last reading.<br>&bull; 0 - Power consumption reporting disabled<br>&bull; 1 % - 100 % Power consumption reporting enabled. New value is reported only when the power consumption in real time changes by more than the percentage value set in this parameter, compared to the previous power consumption reading, starting at 1 % (the lowest value possible).<br><b>NOTE:</b> Power consumption needs to increase or decrease by at least 1 Watt to be reported, REGARDLESS of the percentage set in this parameter.",
                        defaultValue: 50,
                        required    : false,
                        range       : "0..100"
                ],
                num   : 36,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam39",
                        type        : "number",
                        title       : "Parameter No. 39 - Minimum time between reports (O) O1",
                        description : "This parameter determines the minimum time that must elapse before a new power report on O (O1) is sent to the gateway.<br>&bull; 0 - reports are disabled<br>&bull; 1-120 (1-120s) - report interval<br><b>NOTE:</b> This Parameter is in relation to Parameter No. 36.<br><b>NOTE:</b> Setting the value to less than 30s can cause the Z-Wave network congestion state (slow Device response and decreased network stability).<br><b>NOTE:</b> Wave Shutter measures the power consumption on O1 and O2, but as only O1 or only O2 can be active at a time (never both at the same time), the Wave Shutter reports only one value to the gateway, i.e. the sum of the power consumption of O1 and O2, the same for the current.",
                        defaultValue: 30,
                        required    : false,
                        range       : "0..120"
                ],
                num   : 39,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam71",
                        type        : "enum",
                        title       : "Parameter No. 71 - Operating modes",
                        description : "Choose between the three operating modes. In shutter mode, you can select up/down/stop. In venetian mode, an additional widget/endpoint is displayed in the UI interface, which you can use to control the tilt position of the slats. In manual time set mode, the movement times are taken from the device parameters instead of from calibration.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "Shutter mode",
                                1: "Venetian mode with (up/down and slats rotation)",
                                2: "Manual time set mode",
                        ],
                ],
                num   : 71,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam72",
                        type        : "number",
                        title       : "Parameter No. 72 - Venetian blind slats turning time",
                        description : "Set the time required for the slats to make a full turn (180 degrees).<br>NOTE: Make sure that working mode is set to venetian (Par. No. 71 =1)<br>&bull; 0 - turning time disabled<br>&bull; 1 - 65535 = 0.01 seconds – 655.35 seconds<br><b>NOTE:</b> If the set time is too long and a full turn was already performed, the device will start moving up or down for the remaining time. In this case, shorten the turning time.",
                        defaultValue: 150,
                        required    : false,
                        range       : "0..65535"
                ],
                num   : 72,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam73",
                        type        : "enum",
                        title       : "Parameter No. 73 - Slats position after moving",
                        description : "This parameter is used to enable/disable the slats to return to the previously set position, after being activated via the gateway, push-button operation or when the lower limit switch is reached.<br><b>NOTE:</b> Make sure that working mode is set to venetian (Par. No. 71=1)",
                        defaultValue: 1,
                        required    : false,
                        options     : [
                                0: "disable",
                                1: "enable",
                        ],
                ],
                num   : 73,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam76",
                        type        : "number",
                        title       : "Parameter No. 76 - Motor operation detection",
                        description : "Define the power consumption threshold at the end positions. Based on this value, the Device will know that the shutters reached the limit switches.<br>&bull; 0 - Disabled: reaching a limit switch will not be detected<br>&bull; 1 - Auto power calibration<br>&bull; 2 - 2-255 (2-255W) - report interval<br><b>NOTE:</b> For correct auto power calibration the shutter calibration must be performed!",
                        defaultValue: 1,
                        required    : false,
                        range       : "0..255"
                ],
                num   : 76,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam78",
                        type        : "enum",
                        title       : "Parameter No. 78 – Forced shutter calibration",
                        description : "By setting this parameter to value 1 the Device will start executing force calibration procedure. The parameter also reports the calibration status by sending the get parameter value command.<br>NOTE: Check chapter Functionality with calibration details.<br>NOTE: During the calibration procedure the blind moves up, down, up, and down to 50%.<br>NOTE: During the calibration procedure the yellow LED is blinking.",
                        defaultValue: 3,
                        required    : false,
                        options     : [
                                1: "start calibration",
                                2: "device is calibrated (read only)",
                                3: "device is not calibrated (read only)",
                                4: "calibration error (read only)",
                        ],
                ],
                num   : 78,
                size  : 1,
                hidden: true,
        ],
        [
                input : [
                        name        : "configParam79",
                        type        : "number",
                        title       : "Parameter No. 79 – Power consumption max delay time",
                        description : "Define the maximum time before the power consumption of the motor is read from the Device, after one of the relays is switched on. If there is no power consumption during the set time (motor is not connected, damaged or requires longer time to start, motor is at the end position), the relay will switch off. This time is defined by entering it manually.<br>&bull; 0 = time is set automatically<br>&bull; 3 - 50 = 0.3seconds – 5seconds (100ms resolution)",
                        defaultValue: 30,
                        required    : false,
                        range       : "0..50"
                ],
                num   : 79,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam80",
                        type        : "number",
                        title       : "Parameter No. 80 – Motor stop delay after limit switch detection",
                        description : "This parameter defines the delay time for the motor to turn off, after reaching the limit switch.<br>&bull; Default value 10 = (1s)<br>&bull; 0-255 (0-25.5s) - time",
                        defaultValue: 10,
                        required    : false,
                        range       : "0..255"
                ],
                num   : 80,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam81",
                        type        : "number",
                        title       : "Parameter No. 81 - Max. Motor moving time",
                        description : "When the shutter is not calibrated (or the motor is not equipped with a limit switch), this parameter defines the movement time of the motor.<br>&bull; Default value: 120 (120s)<br>&bull; value = 1 - 32000 (1s - 32000s)<br>&bull; 32001 = unlimited<br><b>NOTE:</b> Firmware 12.x counts this parameter in 10 ms steps instead of seconds. The driver converts the value automatically, but that firmware cannot go beyond 320 seconds.",
                        defaultValue: 120,
                        required    : false,
                        range       : "1..32001"
                ],
                num   : 81,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam105",
                        type        : "number",
                        title       : "Parameter No. 105 - LED Signalisation intensity",
                        description : "This parameter determines the intensity of the LED on the Device. Some Devices have RGB LEDs and some have Blue/Red LEDs, but all are dimmable.<br>&bull; 0-100 (0-100%, every 1%)",
                        defaultValue: 100,
                        required    : false,
                        range       : "0..100"
                ],
                num   : 105,
                size  : 1,
                hidden: false,
        ],
]

// Parameter that reboots the device, exposed as an action instead of a preference so that it only
// fires when asked for. Firmware 14.x and later only.
@Field static final Integer REBOOT_PARAM = 117

// Firmware 14.x renumbered part of the configuration parameter set. The specification above uses the
// firmware 14 numbering, and this map translates it to the numbering used by firmware 12.x devices.
// Parameters omitted here kept the same number across both firmware generations.
@Field static final Map<Integer, Integer> legacyParamNumbers = [
        5 : 1,  // push-button / bistable selection
        6 : 3,  // inputs orientation
        16: 5,  // output orientation
        36: 40, // power consumption reporting
        79: 85, // power consumption max delay time
        81: 91, // max. motor moving time
]

// Firmware 14.x also changed the unit of some parameters. The specification above uses the firmware
// 14 units, and values are converted for firmware 12.x devices. Keyed by the firmware 14 number,
// where "factor" is how many device steps make up one unit of the specification and "max" is the
// highest value firmware 12.x accepts, in its own steps.
@Field static final Map<Integer, Map> legacyValueScales = [
        81: [factor: 100, max: 32000], // seconds on 14.x, 10 ms steps on 12.x
]

//endregion Specification

//region Core Functions
void installed() {
    logWarn "installed driver version: ${VERSION}"

    runIn(10, configure)
}

void configure() {
    logWarn "performing configuration..."

    List<String> cmds = [
            versionGetCmd(),
            mfgSpecificGetCmd(),
            deviceSpecificGetCmd(),
    ]

    // Parameter numbering depends on the firmware version, so parameters are refreshed separately,
    // once the version report had a chance to arrive.
    runIn(5, refreshParams)

    sendCommands(cmds)
}

// Refreshes all configuration parameters.
void refreshParams() {
    List<String> cmds = []

    configParams.each { param ->
        cmds += configGetCmd(param)
    }

    runIn(cmds.size() * 2, refresh)

    sendCommands(cmds)
}

void refresh() {
    logWarn "performing device state refresh..."

    List<hubitat.zwave.Command> cmds = [
            switchMultilevelGetCmd(1),
            meterGetCmd(0),
            meterGetCmd(2),
            configGetCmd(getParam(78)),
    ]

    sendCommands(cmds)
}

void updated() {
    logWarn "performing preferences update..."

    checkLogLevel()

    logWarn "Z-Wave stack detected: ${describeStack()}, outbound supervision is ${useSupervision() ? 'on' : 'off'} (automatic: on for S2 devices on the legacy stack only)"

    sendCommands(getConfigureCmds())
}

//endregion Core Functions

//region Capabilities Functions

void open() {
    List<hubitat.zwave.Command> cmds = [
            switchMultilevelSetCmd((Integer) 99, 0, 1),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd(1))
    }

    sendCommands(cmds, 200)
}

void close() {
    List<hubitat.zwave.Command> cmds = [
            switchMultilevelSetCmd((Integer) 0, 0, 1),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd(1))
    }

    sendCommands(cmds, 200)
}

void setPosition(position) {
    if (position > 99) {
        position = 99
    } else if (position < 0) {
        position = 0
    }

    List<hubitat.zwave.Command> cmds = [
            switchMultilevelSetCmd((Integer) position, 0, 1),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd(1))
    }

    sendCommands(cmds, 200)
}

void startPositionChange(String direction) {
    List<hubitat.zwave.Command> cmds = []

    if (direction == "open") {
        cmds.add(switchMultilevelStartLvChCmd(false, 0, 1))
    } else if (direction == "close") {
        cmds.add(switchMultilevelStartLvChCmd(true, 0, 1))
    } else {
        logErr("Invalid direction: ${direction}")
        return
    }

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd(1))
    }

    sendCommands(cmds, 200)
}

void stopPositionChange() {
    sendCommands(switchMultilevelStopLvChCmd())
}

void calibrate() {
    sendCommands(configSetGetCmd(getParam(78), 1))
}

// Restarts the device through parameter No. 117, which firmware 14.x resets to 0 on its own once
// the device comes back. Firmware 12.x does not implement the parameter.
void reboot() {
    if (usesLegacyParamNumbers()) {
        logWarn "remote reboot is not supported on firmware ${device.getDataValue('firmwareVersion')}"
        return
    }

    logWarn "rebooting device..."
    sendCommands(secureCmd(zwave.configurationV4.configurationSet(parameterNumber: REBOOT_PARAM, size: 1, scaledConfigurationValue: 1)))
}

//endregion Capabilities Functions

//region Device Specific Handlers

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep = 0) {
    logTrace "${cmd}"

    // Z-Wave JS optimistically echoes the value we just commanded back as the current value, within
    // a few hundred milliseconds of a Set and before the shutter has moved. The echo is a partial
    // document carrying only currentValue, so it parses with a null targetValue and duration. The
    // device reports v4 and always sends both, so this shape is exclusively the echo; taking it at
    // face value publishes a false position and a wrong direction. The real report follows shortly.
    if (cmd.targetValue == null && cmd.duration == null) {
        logDebug "Ignoring optimistic value echo from Z-Wave JS: ${cmd}"
        return
    }

    // We handle this way an unknown position reported as 254 which can happen if blinds are not calibrated.
    Short position = cmd.value
    if (position > 99) {
        position = 99
    }

    sendEventWrapper(name: "position", value: position, unit: "%", descriptionText: "Shade position is ${position} %")

    updateWindowShade(cmd.value, cmd.targetValue)
}

void zwaveEvent(hubitat.zwave.commands.meterv5.MeterReport cmd, ep = 0) {
    handleMeterReport(cmd, ep)
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterReport cmd, ep = 0) {
    handleMeterReport(cmd, ep)
}

void handleMeterReport(cmd, ep = 0) {
    logTrace "${cmd}"
    switch (cmd.scale) {
        case 0x00:
            sendEventWrapper(name: "energy", value: cmd.scaledMeterValue, unit: "kWh", descriptionText: "Device consumed ${cmd.scaledMeterValue} kWh")
            break;
        case 0x02:
            sendEventWrapper(name: "power", value: cmd.scaledMeterValue, unit: "W", descriptionText: "Device consumes ${cmd.scaledMeterValue} W")
            break;
        default:
            logWarn("Skipped Z-Wave MeterReport: ${cmd.inspect()}")
    }
}

// We handle successful supervision report for set level command to correctly show temporary window shade status change to "opening" or "closing".
void handleSupervisionResult(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelSet cmd, ep = 0, result, position) {
    // Upon receiving set level command within supervision Shelly Wave Shutter correctly responds with "working" status as it is always going to take some time for the blinds to reach the desired position.
    if (result == 0x01) {
        updateWindowShade(position, cmd.value)
    }
}

// We handle successful supervision report for start level change command to correctly show temporary window shade status change to "opening" or "closing".
void handleSupervisionResult(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelStartLevelChange cmd, ep = 0, result, position) {
    // Upon receiving start level change command within supervision Shelly Wave Shutter responds with "success" status, instead of "working".
    if (result == 0xFF) {
        // We interpret start level change command "up" as target value 99 and "down" as target value "0".
        targetValue = 99
        if (cmd.upDown == true) {
            targetValue = 0
        }

        updateWindowShade(position, targetValue)
    }
}

// Defines custom behaviors for specific parameters.
void handleParameterReport(Map param, value) {
    switch (param.num) {
        case 78:
            updateCalibrationStatus(value)
            break
    }
}

// Updates window shade status based on multi-level report.
void updateWindowShade(value, targetValue) {
    // If the shutter is not yet calibrated it may return value 254.
    if (value > 99 || targetValue > 99) {
        sendEventWrapper(name: "windowShade", value: "unknown", descriptionText: "Shade is in unknown state")
        return
    }

    // If value and target value are equal 0 it means the calibrated shades are closed.
    if (value == 0 && targetValue == 0) {
        sendEventWrapper(name: "windowShade", value: "closed", descriptionText: "Shade is closed")
        return
    }

    // If value and target value are equal 99 it means the calibrated shades are open.
    if (value == 99 && targetValue == 99) {
        sendEventWrapper(name: "windowShade", value: "open", descriptionText: "Shade is open")
        return
    }

    // If value is higher than the target value then the shades are closing.
    if (value > targetValue) {
        sendEventWrapper(name: "windowShade", value: "closing", descriptionText: "Shade is closing")
        return
    }

    // If value is smaller than the target value then the shades are opening.
    if (value < targetValue) {
        sendEventWrapper(name: "windowShade", value: "opening", descriptionText: "Shade is opening")
        return
    }

    // Otherwise if value and target value are the same, while being higher than 0 and less than 99 it means the shades are partially open.
    sendEventWrapper(name: "windowShade", value: "partially open", descriptionText: "Shade is partially open")
}

// Updates state to reflect calibration status.
void updateCalibrationStatus(value) {
    switch (value) {
        case 1:
            state.calibrationStatus = "pending"
            break
        case 2:
            state.calibrationStatus = "calibrated"
            break
        case 3:
            state.calibrationStatus = "not calibrated"
            break
        case 4:
            state.calibrationStatus = "calibration error"
            break
    }
}

//endregion

//region Generic Z-Wave Driver

// The code below is heavily based on Universal Z-Wave Scanner driver from Jeff Page.
// Source: https://github.com/jtp10181/Hubitat/blob/main/Drivers/universal/zwave-universal-scanner.groovy

/**
 *  Copyright 2022-2024 Jeff Page
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is
 *  distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 *
 */

//region Z-Wave Event Handling

void parse(String description) {
    zwaveParse(description)
}

void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    zwaveMultiChannel(cmd)
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep = 0) {
    zwaveSupervision(cmd, ep)
}

void zwaveParse(String description) {
    hubitat.zwave.Command cmd = zwave.parse(description, commandClassVersions)

    if (cmd) {
        logTrace "parse: ${description} --PARSED-- ${cmd}"
        zwaveEvent(cmd)
    } else {
        logWarn "Unable to parse: ${description}"
    }
}

void zwaveEvent(hubitat.zwave.Command cmd, ep = 0) {
    logDebug "Unhandled zwaveEvent: $cmd (ep ${ep}) [${getObjectClassName(cmd)}]"
}

//Decodes Multichannel Encapsulated Commands
void zwaveMultiChannel(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    hubitat.zwave.Command encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)
    logTrace "${cmd} --ENCAP-- ${encapsulatedCmd}"

    if (encapsulatedCmd) {
        zwaveEvent(encapsulatedCmd, cmd.sourceEndPoint as Integer)
    } else {
        logWarn "Unable to extract encapsulated cmd from $cmd"
    }
}

//Decodes Supervision Encapsulated Commands (and replies to device)
void zwaveSupervision(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep = 0) {
    hubitat.zwave.Command encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)
    logTrace "${cmd} --ENCAP-- ${encapsulatedCmd}"

    if (encapsulatedCmd) {
        zwaveEvent(encapsulatedCmd, ep)
    } else {
        logWarn "Unable to extract encapsulated cmd from $cmd"
    }

    sendCommands(secureCmd(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0), ep))
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
    logTrace "${cmd}"

    switch (cmd.deviceIdType) {
        case 1: //Serial Number
            String serialNumber = ""
            if (cmd.deviceIdDataFormat == 1) {
                serialNumber = convertIntListToHexList(cmd.deviceIdData).join()
            } else {
                cmd.deviceIdData.each { serialNumber += (char) it }
            }
            logDebug "Device Serial Number: $serialNumber"
            device.updateDataValue("serialNumber", serialNumber)
            break
    }
}

void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
    logTrace "${cmd}"

    String fullVersion = String.format("%d.%02d", cmd.firmware0Version, cmd.firmware0SubVersion)
    String zwaveVersion = String.format("%d.%02d", cmd.zWaveProtocolVersion, cmd.zWaveProtocolSubVersion)
    device.updateDataValue("firmwareVersion", fullVersion)
    device.updateDataValue("protocolVersion", zwaveVersion)
    device.updateDataValue("hardwareVersion", "${cmd.hardwareVersion}")

    if (cmd.targetVersions) {
        Map tVersions = [:]
        cmd.targetVersions.each {
            tVersions[it.target] = String.format("%d.%02d", it.version, it.subVersion)
            device.updateDataValue("firmware${it.target}Version", tVersions[it.target])
        }
        logDebug "Received Version Report - Main Firmware: ${fullVersion} | Targets: ${tVersions}"
    } else {
        logDebug "Received Version Report - Firmware: ${fullVersion}"
    }
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    logTrace "${cmd}"

    device.updateDataValue("manufacturer", cmd.manufacturerId.toString())
    device.updateDataValue("deviceType", cmd.productTypeId.toString())
    device.updateDataValue("deviceId", cmd.productId.toString())

    logInfo "fingerprint mfr:\"${hubitat.helper.HexUtils.integerToHexString(cmd.manufacturerId, 2)}\", " +
            "prod:\"${hubitat.helper.HexUtils.integerToHexString(cmd.productTypeId, 2)}\", " +
            "deviceId:\"${hubitat.helper.HexUtils.integerToHexString(cmd.productId, 2)}\", " +
            "inClusters:\"${device.getDataValue("inClusters")}\"" +
            (device.getDataValue("secureInClusters") ? ", secureInClusters:\"${device.getDataValue("secureInClusters")}\"" : "")
}

void zwaveEvent(hubitat.zwave.commands.configurationv4.ConfigurationReport cmd) {
    logTrace "${cmd}"

    Integer paramNum = toSpecParamNumber(cmd.parameterNumber as Integer)
    Map param = getParam(paramNum)
    Long val = cmd.scaledConfigurationValue

    if (param) {
        //Convert scaled signed integer to unsigned
        if (param.format >= 1 || param.format == null) {
            Long sizeFactor = Math.pow(256, param.size).round()
            if (val < 0) {
                val += sizeFactor
            }
        }

        val = toSpecParamValue(paramNum, val)

        logDebug "${param.input.title} (#${paramNum}) = ${val.toString()}"
        if (param.input.type == "enum") {
            device.updateSetting("configParam${paramNum}", [value: "${val.toString()}", type: "enum"])
        } else {
            device.updateSetting("configParam${paramNum}", val as Long)
        }

        handleParameterReport(param, val)
    } else {
        logDebug "Parameter #${cmd.parameterNumber} (unknown) = ${val.toString()}"
    }
}

//endregion Z-Wave Event Handling

//region Z-Wave Command Helpers

void sendCommands(List<String> cmds, Long delay = 200) {
    cmds.each { logTrace "sendCommands: ${it}" }
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

void sendCommands(String cmd) {
    logTrace "sendCommands: ${cmd}"
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
}

String versionGetCmd() {
    return secureCmd(zwave.versionV3.versionGet())
}

String mfgSpecificGetCmd() {
    return secureCmd(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
}

String deviceSpecificGetCmd(type = 0) {
    return secureCmd(zwave.manufacturerSpecificV2.deviceSpecificGet(deviceIdType: type))
}

String switchBinarySetCmd(Integer value, Integer ep = 0) {
    return superviseCmd(zwave.switchBinaryV2.switchBinarySet(switchValue: value), ep)
}

String switchBinaryGetCmd(Integer ep = 0) {
    return secureCmd(zwave.switchBinaryV2.switchBinaryGet(), ep)
}

String switchMultilevelSetCmd(Integer value, Integer duration, Integer ep = 0) {
    return superviseCmd(zwave.switchMultilevelV4.switchMultilevelSet(dimmingDuration: duration, value: value), ep)
}

String switchMultilevelGetCmd(Integer ep = 0) {
    return secureCmd(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
}

String switchMultilevelStartLvChCmd(Boolean upDown, Integer duration, Integer ep = 0) {
    //upDown: false=up, true=down
    return superviseCmd(zwave.switchMultilevelV4.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel: 1, dimmingDuration: duration), ep)
}

String switchMultilevelStopLvChCmd(Integer ep = 0) {
    return superviseCmd(zwave.switchMultilevelV4.switchMultilevelStopLevelChange(), ep)
}

String meterGetCmd(scale, Integer ep = 0) {
    return secureCmd(zwave.meterV6.meterGet(scale: scale), ep)
}

String meterResetCmd(Integer ep = 0) {
    return secureCmd(zwave.meterV6.meterReset(), ep)
}

String notificationGetCmd(notificationType, eventType, Integer ep = 0) {
    return secureCmd(zwave.notificationV9.notificationGet(notificationType: notificationType, v1AlarmType: 0, event: eventType), ep)
}

String configSetCmd(Map param, Integer value) {
    value = toDeviceParamValue(param.num, value)

    //Convert from unsigned to signed for scaledConfigurationValue.
    if (param.format >= 1 || param.format == null) {
        Long sizeFactor = Math.pow(256, param.size).round()
        if (value >= sizeFactor / 2) {
            value -= sizeFactor
        }
    }

    return secureCmd(zwave.configurationV4.configurationSet(parameterNumber: toDeviceParamNumber(param.num), size: param.size, scaledConfigurationValue: value))
}

String configGetCmd(Map param) {
    return secureCmd(zwave.configurationV4.configurationGet(parameterNumber: toDeviceParamNumber(param.num)))
}

List configSetGetCmd(Map param, Integer value) {
    List<String> cmds = []
    cmds << configSetCmd(param, value)
    cmds << configGetCmd(param)
    return cmds
}
//endregion Z-Wave Command Helpers

//region Z-Wave Secure Encapsulation

//Secure encapsulation.
String secureCmd(String cmd) {
    return zwaveSecureEncap(cmd)
}

//Secure and multi-channel encapsulation.
String secureCmd(hubitat.zwave.Command cmd, ep = 0) {
    return zwaveSecureEncap(multiChannelCmd(cmd, ep))
}

// Multi-channel encapsulation if needed.
String multiChannelCmd(hubitat.zwave.Command cmd, ep) {
    if (ep > 0) {
        cmd = zwave.multiChannelV4.multiChannelCmdEncap(destinationEndPoint: ep).encapsulate(cmd)
    }
    return cmd.format()
}
//endregion Z-Wave Secure Encapsulation

//region Z-Wave Supervision
@Field static Map<String, Map<Short, Map>> supervisedPackets = new java.util.concurrent.ConcurrentHashMap()
@Field static Map<String, Short> sessionIDs = new java.util.concurrent.ConcurrentHashMap()
@Field static final Map supervisedStatus = [0x00: "NO SUPPORT", 0x01: "WORKING", 0x02: "FAILED", 0xFF: "SUCCESS"]
@Field static final Integer SUPERVISED_RETRIES = 2
@Field static final Integer SUPERVISED_DELAY_MS = 1000

String superviseCmd(hubitat.zwave.Command cmd, ep = 0) {
    logTrace "superviseCmd: ${cmd} (ep ${ep})"

    if (useSupervision()) {
        //Encapsulated command with SupervisionGet.
        Short sID = getSessionId()
        def cmdEncap = zwave.supervisionV1.supervisionGet(sessionID: sID, statusUpdates: true).encapsulate(cmd)

        //Encapsulate command with multi-channel if needed.
        cmdEncap = multiChannelCmd(cmdEncap, ep)

        logTrace "New Supervised Packet for session: ${sID}"
        if (supervisedPackets[device.id] == null) {
            supervisedPackets[device.id] = [:]
        }
        supervisedPackets[device.id][sID] = [cmd: cmdEncap, cmdRaw: cmd, endpoint: ep, position: device.currentValue("position")]

        //Calculate supervisionCheck delay based on how many cached packets
        Integer packetsCount = supervisedPackets[device.id]?.size() ?: 0
        Integer delayTotal = (SUPERVISED_DELAY_MS * packetsCount) + 1000
        runInMillis(delayTotal, supervisionCheck, [data: [sID: sID, num: 1], overwrite: false])

        //Send back secured command
        return secureCmd(cmdEncap)
    } else {
        //If supervision disabled just multichannel and secure
        return secureCmd(cmd, ep)
    }
}

Short getSessionId() {
    Short sID = sessionIDs[device.id] ?: (state.supervisionID as Short) ?: 0
    sID = (sID + 1) % 64  // Will always will return between 0-63 (6 bits)
    state.supervisionID = sID
    sessionIDs[device.id] = sID
    return sID
}

//Performs supervision check. The data format of map is: [Short sID, Integer num].
void supervisionCheck(Map data) {
    Short sID = (data.sID as Short)
    Integer num = (data.num as Integer)
    Integer packetsCount = supervisedPackets[device.id]?.size() ?: 0
    logTrace "Supervision check #${num} for session ${sID} with packet count: ${packetsCount}"

    if (supervisedPackets[device.id]?.containsKey(sID)) {
        List<String> cmds = []
        if (num <= SUPERVISED_RETRIES) { //Keep trying
            logWarn "Resending supervised session ${sID} with retry #${num}."
            cmds << secureCmd(supervisedPackets[device.id][sID].cmd)
            Integer delayTotal = SUPERVISED_DELAY_MS
            runInMillis(delayTotal, supervisionCheck, [data: [sID: sID, num: num + 1], overwrite: false])
        } else { //Clear after too many attempts
            logWarn "Supervision maximum retries were reached and device did not respond."
            supervisedPackets[device.id].remove(sID)
        }
        if (cmds) sendCommands(cmds)
    } else {
        logTrace "Supervision session ${sID} has already been cleared or is invalid."
    }
}

//Handles reports back from supervision encapsulated commands.
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionReport cmd, ep = 0) {
    logTrace "${cmd} (ep ${ep})"
    if (supervisedPackets[device.id] == null) {
        supervisedPackets[device.id] = [:]
    }
    Short sID = (cmd.sessionID as Short)
    Integer status = (cmd.status as Integer)

    session = supervisedPackets[device.id][sID]
    if (session != null) {
        handleSupervisionResult(session["cmdRaw"], session["endpoint"], status, session["position"])
    }

    switch (status) {
        case 0x01: // "Working"
        case 0xFF: // "Success"
            logDebug "Received supervision report with status ${supervisedStatus[status]} for session ID: ${sID})"
            supervisedPackets[device.id].remove(sID)
            break
        case 0x00: // "No Support"
        case 0x02: // "Failed"
            logWarn "Received supervision report with status ${supervisedStatus[status]} for session ID: ${sID})"
            supervisedPackets[device.id].remove(sID)
            break
    }
}

Boolean supportsSupervision() {
    if (getDataValue("S2")?.toInteger() == null) {
        return false
    }

    return true
}

// Whether this driver should wrap outbound commands in SupervisionGet itself. Only on the legacy
// stack: Z-Wave JS rewrites the encapsulation into its own supervised node.set_value, retries on
// its own, and answers with a synthesised SUCCESS on acceptance rather than the device's WORKING,
// so doing it here as well only adds a second retry path and dead session bookkeeping.
Boolean useSupervision() {
    return supportsSupervision() && !isZwaveJs()
}

// zwaveSecureEncap() only formats a command for whichever stack is active: a JSON document on
// Z-Wave JS, a hex frame on legacy. Nothing is sent and a Get allocates no supervision session, so
// this is a pure local check (1-2 ms measured) and needs no cached flag.
Boolean isZwaveJs() {
    try {
        return zwaveSecureEncap(zwave.versionV3.versionGet().format())?.trim()?.startsWith("{")
    } catch (e) {
        logWarn "isZwaveJs() - could not determine the Z-Wave stack (${e}), assuming legacy"
        return false
    }
}

String describeStack() {
    return isZwaveJs() ? "Z-Wave JS" : "legacy Z/IP"
}

void handleSupervisionResult(hubitat.zwave.Command cmd, ep = 0, result, position) {
    logDebug "Unhandled supervision result: $cmd (ep ${ep}) [${getObjectClassName(cmd)}]"
}

//endregion Z-Wave Supervision

// region Configuration Helpers

//Get a single param by name.
Map getParam(String search) {
    return configParams.find { it.input.name == search }
}

//Get a single param by number.
Map getParam(Number search) {
    return configParams.find { it.num == search }
}

//Tells whether the device runs a firmware generation predating the parameter renumbering done in 14.x.
//Until the version report arrives the current numbering is assumed.
Boolean usesLegacyParamNumbers() {
    String firmwareVersion = device.getDataValue("firmwareVersion")
    if (!firmwareVersion) {
        return false
    }

    return safeToInt(firmwareVersion.tokenize(".")[0], 14) < 14
}

//Translates a parameter number from the specification to the number the device understands.
Integer toDeviceParamNumber(Integer num) {
    if (!usesLegacyParamNumbers()) {
        return num
    }

    return legacyParamNumbers[num] ?: num
}

//Translates a parameter number reported by the device back to the number used in the specification.
Integer toSpecParamNumber(Integer num) {
    if (!usesLegacyParamNumbers()) {
        return num
    }

    return legacyParamNumbers.find { it.value == num }?.key ?: num
}

//Translates a parameter value from the units of the specification to the units the device expects.
Integer toDeviceParamValue(Integer num, Integer value) {
    Map scale = usesLegacyParamNumbers() ? legacyValueScales[num] : null
    if (!scale) {
        return value
    }

    Integer deviceValue = value * scale.factor
    if (deviceValue > scale.max) {
        deviceValue = scale.max
        logWarn "parameter #${num} set to ${value}, which is more than firmware ${device.getDataValue('firmwareVersion')} can express, capping at ${scale.max / scale.factor}"
    }

    return deviceValue
}

//Translates a parameter value reported by the device to the units used in the specification.
Long toSpecParamValue(Integer num, Long value) {
    Map scale = usesLegacyParamNumbers() ? legacyValueScales[num] : null
    if (!scale) {
        return value
    }

    return safeToInt(value / scale.factor) as Long
}

//Get param value.
BigDecimal getParamValue(Map param) {
    BigDecimal paramVal = safeToDec(settings."configParam${param.num}", param.input.defaultValue)

    return paramVal
}

//Convert param value if needed.
BigDecimal getParamValue(String paramName) {
    return getParamValue(getParam(paramName))
}

List<String> getConfigureCmds() {
    List<String> cmds = []

    configParams.each { param ->
        if (!param.hidden) {
            Integer paramVal = getParamValue(param)
            cmds += configSetGetCmd(param, paramVal)
        }
    }

    return cmds ?: []
}

// endregion Configuration Helpers

//region Utility Functions

List convertIntListToHexList(intList, pad = 2) {
    def hexList = []
    intList?.each {
        hexList.add(Integer.toHexString(it).padLeft(pad, "0").toUpperCase())
    }
    return hexList
}

List convertHexListToIntList(String[] hexList) {
    def intList = []
    hexList?.each {
        try {
            it = it.trim()
            intList.add(Integer.parseInt(it, 16))
        }
        catch (e) {
        }
    }
    return intList
}


Integer safeToInt(val, defaultVal = 0) {
    if ("${val}"?.isInteger()) {
        return "${val}".toInteger()
    } else if ("${val}"?.isNumber()) {
        return "${val}".toDouble()?.round()
    } else {
        return defaultVal
    }
}

BigDecimal safeToDec(val, defaultVal = 0, roundTo = -1) {
    BigDecimal decVal = "${val}"?.isNumber() ? "${val}".toBigDecimal() : defaultVal
    if (roundTo == 0) {
        decVal = Math.round(decVal)
    } else if (roundTo > 0) {
        decVal = decVal.setScale(roundTo, BigDecimal.ROUND_HALF_UP).stripTrailingZeros()
    }
    if (decVal.scale() < 0) {
        decVal = decVal.setScale(0)
    }
    return decVal
}

private void sendEventWrapper(Map prop) {
    String cv = device.currentValue(prop.name)
    Boolean changed = (prop.isStateChange == true) || ((cv?.toString() != prop.value?.toString()) ? true : false)
    if (changed) {
        sendEvent(prop)
        logInfo "${device.displayName} ${prop.descriptionText}"
    }
}

//endregion Utility Functions

//region Logging

//Logging level options.
@Field static final Map LOG_LEVELS = [0: "Error", 1: "Warn", 2: "Info", 3: "Debug", 4: "Trace"]
@Field static final Map LOG_TIMES = [0: "Indefinitely", 30: "30 Minutes", 60: "1 Hour", 120: "2 Hours", 180: "3 Hours", 360: "6 Hours", 720: "12 Hours", 1440: "24 Hours"]

//Additional preferences for logging.
preferences {
    //Logging Options
    input name: "logLevel", type: "enum", title: "Logging Level",
            description: "Logs selected level and above.", defaultValue: 2, options: LOG_LEVELS
    input name: "logLevelTime", type: "enum", title: "Logging Level Time",
            description: "Time to enable Debug/Trace logging.", defaultValue: 30, options: LOG_TIMES
}

// This function is to be called from within updated() and configure() to set up logging according to settings.
void checkLogLevel(Map levelInfo = [level: null, time: null]) {
    unschedule(logsOff)

    //Set defaults.
    if (settings.logLevel == null) {
        device.updateSetting("logLevel", [value: "2", type: "enum"])
        levelInfo.level = 2
    }
    if (settings.logLevelTime == null) {
        device.updateSetting("logLevelTime", [value: "30", type: "enum"])
        levelInfo.time = 30
    }

    //Schedule turn off and log as needed.
    if (levelInfo.level == null) levelInfo = getLogLevelInfo()
    String logMsg = "Logging Level is: ${LOG_LEVELS[levelInfo.level]} (${levelInfo.level})"
    if (levelInfo.level >= 3 && levelInfo.time > 0) {
        logMsg += " for ${LOG_TIMES[levelInfo.time]}"
        runIn(60 * levelInfo.time, logsOff)
    }
    logInfo(logMsg)

    //Store last level below Debug
    if (levelInfo.level <= 2) state.lastLogLevel = levelInfo.level
}

// Returns effective log level.
Map getLogLevelInfo() {
    Integer level
    try {
        level = settings.logLevel != null ? settings.logLevel as Integer : 1
    } catch (Exception e) {
        level = 1
    }

    Integer time
    try {
        time = settings.logLevelTime != null ? settings.logLevelTime as Integer : 30
    } catch (Exception e) {
        time = 30
    }

    return [level: level, time: time]
}

// Disables debug or trace logging after a set period of time.
void logsOff() {
    logWarn "Debug and trace logging disabled..."
    if (logLevelInfo.level >= 3) {
        Integer lastLvl = state.lastLogLevel != null ? state.lastLogLevel as Integer : 2
        device.updateSetting("logLevel", [value: lastLvl.toString(), type: "enum"])
        logWarn "Logging level is: ${LOG_LEVELS[lastLvl]} (${lastLvl})"
    }
}

// Logging functions.

void logErr(String msg) {
    log.error "${device.displayName}: ${msg}"
}

void logWarn(String msg) {
    if (logLevelInfo.level >= 1) log.warn "${device.displayName}: ${msg}"
}

void logInfo(String msg) {
    if (logLevelInfo.level >= 2) log.info "${device.displayName}: ${msg}"
}

void logDebug(String msg) {
    if (logLevelInfo.level >= 3) log.debug "${device.displayName}: ${msg}"
}

void logTrace(String msg) {
    if (logLevelInfo.level >= 4) log.trace "${device.displayName}: ${msg}"
}

// endregion Logging

//endregion Generic Z-Wave Driver