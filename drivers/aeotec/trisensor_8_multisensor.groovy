/**
 *  Aeotec TriSensor 8 Z-Wave 800 Driver for Hubitat
 *  Date: 19.06.2025
 * 	Author: Bogusław Wójcik
 *
 * 	CHANGELOG:
 *  - v0.1.0 - 19.06.2025: Initial working version.
 *
 *  DESCRIPTION:
 *  This is a custom driver for Aeotec TriSensor 8 that is intended to be used instead of the driver available on Aeotec support pages.
 *  This custom driver improves following aspects:
 *  - S2 included sensor is fully supported,
 *  - most parameters defined in the manual are configurable, including new parameters added in 2.8.4 firmware update.
 *
 *  NOTES:
 *  - The driver has been tested on Aeotec TriSensor 8 from EU distribution with firmware version 2.8.4 (shows as 2.0.8) and securely paired with Hubitat.
 *  - To take full advantage of parameters configuring LED behavior make sure you update the firmware of the sensor.
 *    Firmware can be downloaded at: https://aeotec.freshdesk.com/support/solutions/articles/6000276825-update-trisensor-8-to-v2-8-4
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
import groovy.time.TimeCategory

@Field static final String VERSION = "0.1.0"

metadata {
    definition(
            name: "Aeotec TriSensor 8",
            namespace: "boguslaw-wojcik",
            author: "Bogusław Wójcik",
            singleThreaded: true,
            importUrl: "https://github.com/boguslaw-wojcik/hubitat/blob/main/drivers/shelly/trisensor8_multisensor.groovy"
    ) {
        capability "Actuator"
        capability "Battery"
        capability "Power Source"
        capability "Sensor"
        capability "Motion Sensor"
        capability "Illuminance Measurement"
        capability "Temperature Measurement"
        capability "Refresh"
        capability "Configuration"

        // EU
        fingerprint mfr: "0371", prod: "0002", deviceId: "002D", inClusters: "0x5E,0x22,0x55,0x98,0x9F,0x6C", secureInClusters: "0x85,0x59,0x8E,0x80,0x31,0x71,0x70,0x86,0x84,0x72,0x5A,0x87,0x73,0x7A", controllerType: "ZWV", deviceJoinName: "Aeotec TriSensor 8"

        // US
        fingerprint mfr: "0371", prod: "0102", deviceId: "002D", inClusters: "0x5E,0x22,0x55,0x98,0x9F,0x6C", secureInClusters: "0x85,0x59,0x8E,0x80,0x31,0x71,0x70,0x86,0x84,0x72,0x5A,0x87,0x73,0x7A", controllerType: "ZWV", deviceJoinName: "Aeotec TriSensor 8"

        // AU
        fingerprint mfr: "0371", prod: "0202", deviceId: "002D", inClusters: "0x5E,0x22,0x55,0x98,0x9F,0x6C", secureInClusters: "0x85,0x59,0x8E,0x80,0x31,0x71,0x70,0x86,0x84,0x72,0x5A,0x87,0x73,0x7A", controllerType: "ZWV", deviceJoinName: "Aeotec TriSensor 8"

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
        0x5E: 2, // COMMAND_CLASS_ZWAVEPLUS_INFO_V2
        0x98: 1, // COMMAND_CLASS_SECURITY_V1
        0x9F: 1, // COMMAND_CLASS_SECURITY_2_V1
        0x55: 2, // COMMAND_CLASS_TRANSPORT_SERVICE_V2
        0x86: 3, // COMMAND_CLASS_VERSION_V3
        0x73: 1, // COMMAND_CLASS_POWERLEVEL_V1
        0x85: 2, // COMMAND_CLASS_ASSOCIATION_V2
        0x8E: 3, // COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION_V3
        0x59: 3, // COMMAND_CLASS_ASSOCIATION_GRP_INFO_V3
        0x72: 2, // COMMAND_CLASS_MANUFACTURER_SPECIFIC_V2
        0x5A: 1, // COMMAND_CLASS_DEVICE_RESET_LOCALLY_V1
        0x80: 1, // COMMAND_CLASS_BATTERY_V1
        0x84: 2, // COMMAND_CLASS_WAKE_UP_V2
        0x71: 8, // COMMAND_CLASS_NOTIFICATION_V8
        0x30: 2, // COMMAND_CLASS_SENSOR_BINARY_V2
        0x87: 3, // COMMAND_CLASS_INDICATOR_V3
        0x31: 11, // COMMAND_CLASS_SENSOR_MULTILEVEL_V11
        0x70: 4, // COMMAND_CLASS_CONFIGURATION_V4
        0x6C: 1, // COMMAND_CLASS_SUPERVISION_V1
        0x22: 2, // COMMAND_CLASS_APPLICATION_STATUS_V2
        0x7A: 5, // COMMAND_CLASS_FIRMWARE_UPDATE_MD_V5
]

// Specification of configuration parameters.
@Field static final List<Map> configParams = [
        [
                input : [
                        name        : "configParam3",
                        type        : "number",
                        title       : "(Param 3) Motion Untrigger Time",
                        description : "Timeout configuration set in second for TriSensor to send no trigger status.",
                        defaultValue: 60,
                        required    : false,
                        range       : "30..3600",
                ],
                num   : 3,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam4",
                        type        : "enum",
                        title       : "(Param 4) Motion Sensitivity",
                        description : "Set the sensitivity of TriSensor.",
                        defaultValue: 2,
                        required    : false,
                        options     : [
                                0: "disable",
                                1: "minimum sensitivity",
                                2: "medium sensitivity",
                                3: "maximum sensitivity",
                        ],
                ],
                num   : 4,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam5",
                        type        : "enum",
                        title       : "(Param 5) Motion Report Type",
                        description : "Sends Notification or Sensor Binary Report.",
                        defaultValue: 2,
                        required    : false,
                        options     : [
                                0: "Send Notification Report",
                                1: "Send Sensor Binary Report",
                                2: "Send Notification and Sensor Binary Report",
                        ],
                ],
                num   : 5,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam14",
                        type        : "number",
                        title       : "(Param 14) Low Battery Threshold",
                        description : "Configure low battery report threshold, sends low battery report via notification and battery report when battery level drops under setting. Unit %.",
                        defaultValue: 20,
                        required    : false,
                        range       : "10..50"
                ],
                num   : 14,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam15",
                        type        : "enum",
                        title       : "(Param 15) Threshold Check Enable/Disable",
                        description : "If enabled the sensor will send reports if a change from last reported value exceed a configured threshold upon a periodic threshold check.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "disable threshold reports",
                                1: "enable threshold reports",
                        ],
                ],
                num   : 15,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam16",
                        type        : "number",
                        title       : "(Param 16) Temperature Threshold",
                        description : "Threshold value for temperature. Provided value is multiplied by 0.1, set to 0 to disable.",
                        defaultValue: 30,
                        required    : false,
                        range       : "0..255"
                ],
                num   : 16,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam17",
                        type        : "number",
                        title       : "(Param 17) Lux Threshold",
                        description : "Threshold value for Lux. Set to 0 to disable.",
                        defaultValue: 250,
                        required    : false,
                        range       : "0..10000"
                ],
                num   : 17,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam18",
                        type        : "number",
                        title       : "(Param 18) Threshold Check Time",
                        description : "Set threshold check time in seconds.",
                        defaultValue: 900,
                        required    : false,
                        range       : "60-65535"
                ],
                num   : 18,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam24",
                        type        : "enum",
                        title       : "(Param 24) Temperature Scale",
                        description : "Set the scale for temperature when reports.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "Celsius",
                                1: "Fahrenheit",
                        ],
                ],
                num   : 24,
                size  : 1,
                hidden: true,
        ],
        [
                input : [
                        name        : "configParam25",
                        type        : "number",
                        title       : "(Param 25) Sensor Report Interval",
                        description : "Determines the interval in seconds in which temperature and lux sensor values are reported regardless of change.",
                        defaultValue: 3600,
                        required    : false,
                        range       : "30..65535"
                ],
                num   : 25,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam26",
                        type        : "enum",
                        title       : "(Param 26) LED Activity",
                        description : "Allow user to enable/disable LED activity of specific reports sent by sensor. Button press indicator is not affected by this.",
                        defaultValue: 1,
                        required    : false,
                        options     : [
                                0: "disabled",
                                1: "enable",
                        ],
                ],
                num   : 26,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam27",
                        type        : "enum",
                        title       : "(Param 27) Motion Sensor Report Indicator",
                        description : "If LED is enabled by Param 26, allow user to change the report color of motion sensor.",
                        defaultValue: 3,
                        required    : false,
                        options     : [
                                0: "disabled",
                                1: "Red",
                                2: "Blue",
                                3: "Green",
                                4: "Pink",
                                5: "Cyan",
                                6: "Purple",
                                7: "Orange",
                                8: "Yellow",
                                9: "White",
                        ],
                ],
                num   : 27,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam28",
                        type        : "number",
                        title       : "(Param 28) Temperature Offset Value",
                        description : "Can add or minus this setting value to calibrate temperature when checked. Scale is defined by Param 24, e.g. value 15 means 1.5C or 1.5F.",
                        defaultValue: 0,
                        required    : false,
                        range       : "-200..200"
                ],
                num   : 28,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam29",
                        type        : "number",
                        title       : "(Param 29) Lux Offset Value",
                        description : "Can add or minus this setting value to calibrate Lux when checked.",
                        defaultValue: 0,
                        required    : false,
                        range       : "-10000..10000"
                ],
                num   : 29,
                size  : 2,
                hidden: false,
        ],
]

//endregion Specification

//region Core Functions
void installed() {
    logWarn "installed driver version: ${VERSION}"

    sendEvent(name: 'motion', value: 'inactive')
    sendEvent(name: 'illuminance', value: 100)
    sendEvent(name: 'temperature', value: 50)
    sendEvent(name: 'battery', value: 100)

    configure()
    refresh()
}

void configure() {
    logWarn "preparing initialization, actions will be performed during next wakeup..."

    state.initializeOnNextWakeup = true
}

void refresh() {
    logWarn "preparing device state refresh, actions will be performed during next wakeup..."

    state.refreshOnNextWakeup = true
}

void updated() {
    logWarn "preparing preferences update, configuration of the device will be updated during next wakeup..."

    checkLogLevel()

    state.configureOnNextWakeup = true
}

//endregion Core Functions

//region Device Specific Handlers

def zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, ep = 0) {
    logTrace "${cmd}"

    switch (cmd.notificationType) {
        case 0x07:
            switch (cmd.event) {
                case 0:
                    sendMotionEvent(0)
                    break
                case 8:
                    sendMotionEvent(1)
                    break
                default:
                    logWarn "zwaveEvent(NotificationReport) - Unhandled event - cmd: ${cmd.inspect()}"
                    break;
            }
            break
        default:
            logWarn "zwaveEvent(NotificationReport) - Unhandled notificationType - cmd: ${cmd.inspect()}"
            break;
    }
}

void zwaveEvent(hubitat.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd, ep = 0) {
    logTrace "${cmd}"
    // Sensor sends value 0xFF on motion, 0x00 on no motion.
    sendMotionEvent(cmd.sensorValue)
}

private sendMotionEvent(value) {
    Map event = [name: "motion"]
    if (value) {
        event.value = "active"
        event.descriptionText = "Motion is active"
    } else {
        event.value = "inactive"
        event.descriptionText = "Motion is inactive"
    }

    sendEventWrapper(event)
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv11.SensorMultilevelReport cmd, ep = 0) {
    logTrace "${cmd}"

    Map event = [:]

    switch (cmd.sensorType) {
        case 1:
            event.name = "temperature"
            event.value = convertTemperatureIfNeeded(cmd.scaledSensorValue, cmd.scale == 1 ? "f" : "c", cmd.precision)
            event.unit = "°" + getTemperatureScale()
            event.descriptionText = "Temperature is ${event.value} ${event.unit}"
            sendEventWrapper(event)
            break
        case 3:
            event.name = "illuminance"
            event.value = cmd.scaledSensorValue
            event.unit = "lux"
            event.descriptionText = "Illuminance is ${event.value} ${event.unit}"
            sendEventWrapper(event)
            break
        default:
            logWarn "zwaveEvent(SensorMultilevelReport) - Unknown sensorType - cmd: ${cmd.inspect()}"
            break;
    }
}

//endregion

//region Sleepy Device Specific Handlers

//Additional preferences for sleepy devices.
preferences {
    input name: "wakeUpInterval", type: "enum", title: "Device Wake Up Interval",
            description: "Interval at which the battery-powered device will check-in to receive commands and configuration.", defaultValue: 43200, options: [
            300   : "5m",
            900   : "15m",
            1800  : "30m",
            3600  : "1h",
            7200  : "2h",
            10800 : "3h",
            21600 : "6h",
            43200 : "12h",
            86400 : "24h",
            172800: "48h",
    ]
    input name: "batteryReportInterval", type: "enum", title: "Battery Report Interval",
            description: "Interval at which the battery level will be requested if there was no report sent by the device itself.", defaultValue: 172800, options: [
            21600 : "6h",
            43200 : "12h",
            86400 : "1d",
            172800: "2d",
            604800: "1w",
    ]
}

Boolean requireBatteryReport() {
    Boolean refresh = true

    device.getCurrentStates().each { state ->
        if (state.name == "battery") {
            diff = TimeCategory.minus(new Date(), state.date)
            reportInterval = safeToDec(settings.batteryReportInterval, 2*24*60*60)
            if (reportInterval > (diff.toMilliseconds()/1000)) {
                refresh = false
            }
        }
    }

    return refresh
}

void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd, ep = 0) {
    logTrace "${cmd}"

    if (cmd.batteryLevel == 0xFF) {
        sendEventWrapper(name: "battery", value: 1, unit: "%", descriptionText: "Device has low battery level.")
    } else {
        sendEventWrapper(name: "battery", value: cmd.batteryLevel, unit: "%", descriptionText: "Device battery is at ${cmd.batteryLevel}%.")
    }
}

void zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalReport cmd, ep = 0) {
    logTrace "${cmd}"
    BigDecimal wakeHrs = safeToDec(cmd.seconds / 3600, 0, 2)
    logDebug "WakeUp Interval is $cmd.seconds seconds ($wakeHrs hours)"
    device.updateDataValue("zwWakeupInterval", "${cmd.seconds}")
    device.updateSetting("wakeUpInterval", [value: "${cmd.seconds}", type: "enum"])
}

void zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd, ep = 0) {
    logDebug "WakeUp Notification Received"
    logTrace "${cmd}"


    List<hubitat.zwave.Command> cmds = []

    // If device was not initialized we need to retrieve basic data from it.
    if (state.initializeOnNextWakeup) {
        logWarn "performing scheduled initialization..."

        cmds += versionGetCmd()
        cmds += mfgSpecificGetCmd()
        cmds += deviceSpecificGetCmd()
        cmds += wakeUpIntervalGetCmd()

        configParams.each { param ->
            cmds += configGetCmd(param)
        }

        state.initializeOnNextWakeup = false
    }

    // If it was requested to refresh device readings we need to ask for the reports.
    if (state.refreshOnNextWakeup) {
        logWarn "performing scheduled refresh..."

        cmds += sensorBinaryGetCmd(12)
        cmds += batteryGetCmd()
        cmds += sensorMultilevelGetCmd(1, getTemperatureScale())
        cmds += sensorMultilevelGetCmd(3, 0)

        state.refreshOnNextWakeup = false
    }

    // If it was requested to update configuration all configuration options will be sent.
    if (state.configureOnNextWakeup == true) {
        logWarn "performing scheduled configuration..."

        cmds += getConfigureCmds()
        cmds += wakeUpIntervalSetCmd(settings.wakeUpInterval as Integer)
        cmds += wakeUpIntervalGetCmd()

        state.configureOnNextWakeup = false
    }

    if (requireBatteryReport() == true) {
        logWarn "refreshing battery report..."

        cmds += batteryGetCmd()
    }

    cmds << "delay 1400" << wakeUpNoMoreInfoCmd()

    sendCommands(cmds, 400)
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

        if (param.input.type == "enum") {
            device.updateSetting("configParam${cmd.parameterNumber}", [value: "${val.toString()}", type: "enum"])
        } else {
            device.updateSetting("configParam${cmd.parameterNumber}", val as Long)
        }
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

String wakeUpIntervalGetCmd() {
    return secureCmd(zwave.wakeUpV2.wakeUpIntervalGet())
}

String wakeUpIntervalSetCmd(val) {
    return secureCmd(zwave.wakeUpV2.wakeUpIntervalSet(seconds: val, nodeid: zwaveHubNodeId))
}

String wakeUpNoMoreInfoCmd() {
    return secureCmd(zwave.wakeUpV2.wakeUpNoMoreInformation())
}

String batteryGetCmd() {
    return secureCmd(zwave.batteryV1.batteryGet())
}

String sensorBinaryGetCmd(sensorType) {
  return secureCmd(zwave.sensorBinaryV2.sensorBinaryGet(sensorType: sensorType))
}

String sensorMultilevelGetCmd(sensorType, scale) {
    return secureCmd(zwave.sensorMultilevelV11.sensorMultilevelGet(scale: scale, sensorType: sensorType))
}

String notificationGetCmd(notificationType, eventType, Integer ep = 0) {
    return secureCmd(zwave.notificationV8.notificationGet(notificationType: notificationType, v1AlarmType: 0, event: eventType), ep)
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