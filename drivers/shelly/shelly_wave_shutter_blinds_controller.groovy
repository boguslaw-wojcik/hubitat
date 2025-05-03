/**
 *  Shelly Wave Shutter Z-Wave 800 Driver for Hubitat
 *  Date: 03.05.2025
 *	Author: Bogusław Wójcik
 *
 *	CHANGELOG:
 *  - V0.1.0 - 03.05.2025: Initial working version.
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
 *  - The driver has been tested on Shelly Wave Shutter from EU distribution module with firmware version 12.23 and securely paired with Hubitat.
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

@Field static final String VERSION = "0.1.0"

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
        0x32: 6, // COMMAND_CLASS_METER_V6
        0x7A: 7, // COMMAND_CLASS_FIRMWARE_UPDATE_MD_V7
        0x98: 1, // COMMAND_CLASS_SECURITY_V1
        0x9F: 1, // COMMAND_CLASS_SECURITY_2_V1
]

// Specification of configuration parameters.
@Field static final List<Map> configParams = [
        [
                input : [
                        name        : "configParam1",
                        type        : "enum",
                        title       : "Parameter No. 1 - Push-button (momentary) / bistable (toggle switch) selection",
                        description : "With this parameter, you can select between the switch type: push-button (momentary) or on/off toggle switch connected to SW1 and SW2 inputs.<br><b>NOTE:</b> When set = 2, 1x click on SW1 up - 1x click on SW1 stop - 1x click down",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "momentary switch",
                                1: "toggle switch (contact closed - ON / contact opened - OFF)",
                                2: "single, momentary switch (the switch should be connected to SW1 terminal)"
                        ],
                ],
                num   : 1,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam3",
                        type        : "enum",
                        title       : "Parameter No. 3 – Inputs orientation",
                        description : "This parameter allows to reverse the operation of switches connected to SW1 and SW2 inputs without changing the wiring.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "default (SW1 - O1, I2 - O2)",
                                1: "reversed (SW1 - O2, I2 - O1)",
                        ],
                ],
                num   : 3,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam5",
                        type        : "enum",
                        title       : "Parameter No. 5 – Output orientation",
                        description : "This parameter allows to reverse the operation of O1 and O2 without changing the wiring (in case of invalid motor connection) to ensure proper operation.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "default (O1 - UP, O2 - DOWN)",
                                1: "reversed (O1 - DOWN, O2 - UP)",
                        ],
                ],
                num   : 5,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam40",
                        type        : "number",
                        title       : "Parameter No. 40 - Power Consumption Reporting",
                        description : "Choose by how much the power (W) consumption needs to increase or decrease to be reported. Values correspond to percentages, so if 50 is set (by default), the Device will report any power consumption changes of 50 % or more, compared to the last reading.<br>&bull; 0 - Power consumption reporting disabled<br>&bull; 1 % - 100 % Power consumption reporting enabled. New value is reported only when the power consumption in real time changes by more than the percentage value set in this parameter, compared to the previous power consumption reading, starting at 1 % (the lowest value possible).<br><b>NOTE:</b> Power consumption needs to increase or decrease by at least 1 Watt to be reported, REGARDLESS of the percentage set in this parameter.",
                        defaultValue: 50,
                        required    : false,
                        range       : "0..100"
                ],
                num   : 40,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam71",
                        type        : "enum",
                        title       : "Parameter No. 71 - Operating modes",
                        description : "Choose between the two operating modes. In shutter mode, you can select up/down/stop. In venetian mode, an additional widget/endpoint is displayed in the UI interface, which you can use to control the tilt position of the slats.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "Shutter mode",
                                1: "Venetian mode with (up/down and slats rotation)",
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
                        description : "Set the time required for the slats to make a full turn (180 degrees).<br>NOTE: Make sure that working mode is set to venetian (Par. No. 71 =1)<br>&bull; 0 - turning time disabled<br>&bull; 1 - 32000 = 0.01 seconds – 320 seconds<br><b>NOTE:</b> If the set time is too long and a full turn was already performed, the device will start moving up or down for the remaining time. In this case, shorten the turning time.",
                        defaultValue: 150,
                        required    : false,
                        range       : "0..32000"
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
                        name        : "configParam80",
                        type        : "number",
                        title       : "Parameter No. 80 – Motor stop delay after limit switch detection",
                        description : "This parameter defines the delay time for the motor to turn off, after reaching the limit switch.<br>&bull; Default value 10 = (1s)<br>&bull; 0-127 (0-12.7s) - time",
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
                        name        : "configParam85",
                        type        : "number",
                        title       : "Parameter No. 85 – Power consumption max delay time",
                        description : "Define the maximum time before the power consumption of the motor is read from the Device, after one of the relays is switched on. If there is no power consumption during the set time (motor is not connected, damaged or requires longer time to start, motor is at the end position), the relay will switch off. This time is defined by entering it manually.<br>&bull; 0 = time is set automatically<br>&bull; 3 - 50 = 0.3seconds – 5seconds (100ms resolution)",
                        defaultValue: 30,
                        required    : false,
                        range       : "0..50"
                ],
                num   : 85,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam91",
                        type        : "number",
                        title       : "Parameter No. 91 - Max. Motor moving time",
                        description : "When the shutter is not calibrated (or the motor is not equipped with a limit switch), this parameter defines the movement time of the motor.<br>&bull; Default value: 12000 (120s)<br>&bull; value = 1 - 32000 (10ms - 320s)",
                        defaultValue: 12000,
                        required    : false,
                        range       : "1..32000"
                ],
                num   : 91,
                size  : 2,
                hidden: false,
        ],
]

//endregion Specification

//region Core Functions
void installed() {
    logWarn "installed driver version: ${VERSION}"

    runIn(10, configure)
}

void configure() {
    logWarn "performing configuration..."

    List<hubitat.zwave.Command> cmds = [
            versionGetCmd(),
            mfgSpecificGetCmd(),
            deviceSpecificGetCmd(),
    ]

    // Refresh all parameters.
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

//endregion Capabilities Functions

//region Device Specific Handlers

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep = 0) {
    logTrace "${cmd}"

    // We handle this way an unknown position reported as 254 which can happen if blinds are not calibrated.
    Short position = cmd.value
    if (position > 99) {
        position = 99
    }

    sendEventWrapper(name: "position", value: position, unit: "%", descriptionText: "Shade position is ${position} %")

    updateWindowShade(cmd.value, cmd.targetValue)
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterReport cmd, ep = 0) {
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

    Map param = getParam(cmd.parameterNumber)
    Long val = cmd.scaledConfigurationValue

    if (param) {
        //Convert scaled signed integer to unsigned
        if (param.format >= 1 || param.format == null) {
            Long sizeFactor = Math.pow(256, param.size).round()
            if (val < 0) {
                val += sizeFactor
            }
        }

        logDebug "${param.input.title} (#${param.num}) = ${val.toString()}"
        device.updateSetting("configParam${cmd.parameterNumber}", val as Long)
        handleParameterReport(param, val)
    } else {
        logDebug "Parameter #${cmd.parameterNumber} = ${val.toString()}"
    }
}

//endregion Z-Wave Event Handling

//region Z-Wave Command Helpers

void sendCommands(List<String> cmds, Long delay = 200) {
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

void sendCommands(String cmd) {
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
    //Convert from unsigned to signed for scaledConfigurationValue.
    if (param.format >= 1 || param.format == null) {
        Long sizeFactor = Math.pow(256, param.size).round()
        if (value >= sizeFactor / 2) {
            value -= sizeFactor
        }
    }

    return secureCmd(zwave.configurationV4.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: value))
}

String configGetCmd(Map param) {
    return secureCmd(zwave.configurationV4.configurationGet(parameterNumber: param.num))
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

    if (supportsSupervision()) {
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

//Get param value.
BigDecimal getParamValue(Map param) {
    BigDecimal paramVal = safeToDec(settings."configParam${param.num}", param.defaultVal)

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
    Integer level = settings.logLevel != null ? settings.logLevel as Integer : 1
    Integer time = settings.logLevelTime != null ? settings.logLevelTime as Integer : 30
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