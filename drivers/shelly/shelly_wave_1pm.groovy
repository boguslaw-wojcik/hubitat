/**
 *  Shelly Wave 1PM & 1PM Mini Z-Wave 800 Driver for Hubitat
 *  Date: 20.09.2026
 *  Author: Bogusław Wójcik
 *
 *  CHANGELOG:
 *  - v0.1.0 - 20.09.2026: Initial release: switch control, power and energy metering with lowest and highest power tracking, remote reboot and all parameters of Wave 1PM and Wave 1PM Mini, working on both the legacy Z-Wave stack and Z-Wave JS.
 *
 *  DESCRIPTION:
 *  This is a custom driver for Shelly Wave 1PM (QNSW-001P16EU) and Shelly Wave 1PM Mini (QMSW-0A1P8EU), single-channel
 *  in-wall relays with power metering. It also carries the fingerprints of Shelly Wave 1 and Shelly Wave 1 Mini, which
 *  share the same firmware without the power metering part.
 *
 *  The driver started as a rewrite of Rene Boer's "Shelly Wave 1PM" driver v1.2 and keeps all of its functionality:
 *  - switch control,
 *  - power and energy metering with the lowest and highest power seen since the last meter reset (PM models only),
 *  - remote reboot of the device for troubleshooting,
 *  - all parameters defined in the manual are configurable, and the configuration is properly saved on the device.
 *
 *  It differs in the implementation:
 *  - it works on both the legacy Z-Wave stack and Z-Wave JS: on Z-Wave JS the platform supervises commands itself, so the driver
 *    does not wrap them again, the optimistic value echo of the stack is filtered out, and meter reports are parsed as Meter CC v5
 *    because the v6 report objects cannot be constructed by the platform,
 *  - supervision on the legacy stack retries a bounded number of times and cannot loop,
 *  - the set of configuration parameters follows the firmware version: firmware 13 and later adds the detach mode (No. 7) and the
 *    remote reboot (No. 117) and drops the alarm responses (No. 91 - 94).
 *
 *  NOTES:
 *  - The driver has been tested on Shelly Wave 1PM with firmware version 11.10 and Shelly Wave 1PM Mini with firmware version 11.05,
 *    both securely paired (S2 Authenticated) with Hubitat. The firmware 13 and 14 parameter set comes from the vendor's release
 *    notes and the Z-Wave JS device database and has not been tested on a device.
 *  - Shelly Wave 1 and Shelly Wave 1 Mini are recognised by the fingerprint but have not been tested.
 *  - Association groups 2 and 3 (Basic Set and Switch Multilevel to other devices) are not configurable from the driver.
 *
 *  Copyright 2026 Bogusław Wójcik
 *
 *  Based on "Shelly Wave 1PM" driver v1.2 (03.12.2024) by Rene Boer, https://github.com/reneboer/Hubitat.
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
            name: "Shelly Wave 1PM & Mini",
            namespace: "boguslaw-wojcik",
            author: "Bogusław Wójcik",
            singleThreaded: true,
            importUrl: "https://github.com/boguslaw-wojcik/hubitat/blob/main/drivers/shelly/shelly_wave_1pm.groovy"
    ) {
        capability "Actuator"
        capability "Switch"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Configuration"
        capability "Refresh"

        // Lowest and highest power seen since the last meter reset.
        attribute "powerHigh", "number"
        attribute "powerLow", "number"

        // Resets the accumulated energy on the device and clears the lowest and highest power seen.
        command "resetPower"
        // Restarts the device. Use for troubleshooting only.
        command "reboot"

        fingerprint mfr: "0460", prod: "0002", deviceId: "0083", controllerType: "ZWV", deviceJoinName: "Shelly Wave 1"
        fingerprint mfr: "0460", prod: "0002", deviceId: "0084", inClusters: "0x5E,0x98,0x9F,0x55,0x6C", secureInClusters: "0x86,0x73,0x87,0x7A,0x5A,0x8E,0x59,0x85,0x70,0x25,0x71,0x32,0x72", controllerType: "ZWV", deviceJoinName: "Shelly Wave 1PM"
        fingerprint mfr: "0460", prod: "0002", deviceId: "0084", inClusters: "0x5E,0x98,0x9F,0x55,0x86,0x6C,0x73,0x87,0x7A,0x5A,0x8E,0x59,0x85,0x70,0x25,0x71,0x32,0x72", controllerType: "ZWV", deviceJoinName: "Shelly Wave 1PM"
        fingerprint mfr: "0460", prod: "0002", deviceId: "008E", inClusters: "0x5E,0x98,0x9F,0x55,0x6C", secureInClusters: "0x86,0x73,0x60,0x87,0x7A,0x5A,0x8E,0x59,0x85,0x71,0x72,0x70,0x25", controllerType: "ZWV", deviceJoinName: "Shelly Wave 1 Mini"
        fingerprint mfr: "0460", prod: "0002", deviceId: "008E", inClusters: "0x5E,0x98,0x9F,0x55,0x86,0x6C,0x73,0x60,0x87,0x7A,0x5A,0x8E,0x59,0x85,0x71,0x72,0x70,0x25", controllerType: "ZWV", deviceJoinName: "Shelly Wave 1 Mini"
        fingerprint mfr: "0460", prod: "0002", deviceId: "008F", inClusters: "0x5E,0x98,0x9F,0x55,0x6C", secureInClusters: "0x71,0x32,0x72,0x70,0x25,0x86,0x73,0x87,0x7A,0x5A,0x8E,0x59,0x85", controllerType: "ZWV", deviceJoinName: "Shelly Wave 1PM Mini"
    }

    preferences {
        configParams.each { param ->
            if (!param.hidden && supportsParam(param)) {
                input param.input
            }
        }
    }
}

//region Specification

// Command class versions specific for the device, as listed in the knowledge base (Z-Wave Command Class section).
@Field static final Map commandClassVersions = [
        0x85: 2, // COMMAND_CLASS_ASSOCIATION_V2
        0x59: 3, // COMMAND_CLASS_ASSOCIATION_GRP_INFO_V3
        0x20: 2, // COMMAND_CLASS_BASIC_V2
        0x25: 2, // COMMAND_CLASS_SWITCH_BINARY_V2
        0x70: 4, // COMMAND_CLASS_CONFIGURATION_V4
        0x5A: 1, // COMMAND_CLASS_DEVICE_RESET_LOCALLY_V1
        0x7A: 5, // COMMAND_CLASS_FIRMWARE_UPDATE_MD_V5
        0x87: 3, // COMMAND_CLASS_INDICATOR_V3
        0x72: 2, // COMMAND_CLASS_MANUFACTURER_SPECIFIC_V2
        0x32: 5, // COMMAND_CLASS_METER_V5 (the device implements v6, but v6 report objects fail to construct under Z-Wave JS: MeterReport.setMeterType(Integer))
        0x8E: 3, // COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION_V3
        0x71: 8, // COMMAND_CLASS_NOTIFICATION_V8
        0x73: 1, // COMMAND_CLASS_POWERLEVEL_V1
        0x98: 1, // COMMAND_CLASS_SECURITY_V1
        0x9F: 1, // COMMAND_CLASS_SECURITY_2_V1
        0x6C: 1, // COMMAND_CLASS_SUPERVISION_V1
        0x55: 2, // COMMAND_CLASS_TRANSPORT_SERVICE_V2
        0x86: 3, // COMMAND_CLASS_VERSION_V3
        0x5E: 2, // COMMAND_CLASS_ZWAVEPLUS_INFO_V2
]

// Specification of configuration parameters (knowledge base, Z-Wave Parameters section). Parameters that only some firmware
// versions implement carry "minFirmware" or "maxFirmware" (exclusive): firmware 13 of Wave 1PM (2025-10) and firmware 14 of
// Wave 1PM Mini (2026-02) added the detach mode (No. 7) and the remote reboot (No. 117) and removed the alarm responses
// (No. 91 - 94), according to the vendor's release notes.
@Field static final List<Map> configParams = [
        [
                input : [
                        name        : "configParam1",
                        type        : "enum",
                        title       : "Parameter No. 1 – SW (SW1) Switch type",
                        description : "This parameter defines how the Device should treat the switch (which type) connected to the SW (SW1) terminal.",
                        defaultValue: 2,
                        required    : false,
                        options     : [
                                0: "momentary switch",
                                1: "toggle switch (contact closed - ON / contact opened - OFF)",
                                2: "toggle switch (device changes status when switch changes status)",
                        ],
                ],
                num   : 1,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam7",
                        type        : "enum",
                        title       : "Parameter No. 7 – SW (SW1) detach mode",
                        description : "In this mode the input SW (SW1) is separated/not changing the state of the output.<br><b>NOTE:</b> Available from firmware 13 (Wave 1PM) and firmware 14 (Wave 1PM Mini).",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "normal mode",
                                1: "detached mode",
                        ],
                ],
                num        : 7,
                size       : 1,
                hidden     : false,
                minFirmware: 13.0,
        ],
        [
                input : [
                        name        : "configParam17",
                        type        : "enum",
                        title       : "Parameter No. 17 – Restore state of O (O1) after power failure",
                        description : "This parameter determines if on/off status is saved and restored for load connected to O (O1) after power failure.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "Device saves last on/off status and restores it after a power failure",
                                1: "Device does not save on/off status and does not restore it after a power failure, it remains off",
                        ],
                ],
                num   : 17,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam19",
                        type        : "number",
                        title       : "Parameter No. 19 – O (O1) Auto OFF with timer",
                        description : "If the load O (O1) is ON, you can schedule it to turn OFF automatically after the period of time defined in this parameter. The timer resets to zero each time the Device receives an ON command, either remotely (from the gateway or associated device) or locally from the switch.<br>&bull; 0 - Auto OFF Disabled<br>&bull; 1 - 32535 = 1 - 32535 seconds (or milliseconds – see Parameter no. 25. Auto OFF timer enabled for a given amount of seconds (or milliseconds) resolution 100ms",
                        defaultValue: 0,
                        required    : false,
                        range       : "0..32535"
                ],
                num   : 19,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam20",
                        type        : "number",
                        title       : "Parameter No. 20 – O (O1) Auto ON with timer",
                        description : "If the load O (O1) is OFF, you can schedule it to turn ON automatically after the period of time defined in this parameter. The timer resets to zero each time the Device receives an OFF command, either remotely (from the gateway or associated device) or locally from the switch.<br>&bull; 0 - Auto ON Disabled<br>&bull; 1 - 32535 = 1 - 32535 seconds (or milliseconds – see Parameter no. 25. Auto ON timer enabled for a given amount of seconds (or milliseconds) resolution 100ms",
                        defaultValue: 0,
                        required    : false,
                        range       : "0..32535"
                ],
                num   : 20,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam23",
                        type        : "enum",
                        title       : "Parameter No. 23 – O (O1) contact type - NO/NC",
                        description : "The set value determines the relay contact type for output O (O1). The relay contact type can be normally open (NO) or normally closed (NC).",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "NO",
                                1: "NC",
                        ],
                ],
                num   : 23,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam25",
                        type        : "enum",
                        title       : "Parameter No. 25 – Set timer units to s or ms for O (O1)",
                        description : "Set Timer Units to Seconds or Milliseconds Choose if you want to set the timer in seconds or milliseconds in Parameters No. 19, 20.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "timer set in seconds",
                                1: "timer set in milliseconds",
                        ],
                ],
                num   : 25,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam36",
                        type        : "number",
                        title       : "Parameter No. 36 – O (O1) Power report on change - percentage",
                        description : "This parameter determines the minimum change in consumed power that will result in sending a new report to the gateway.<br>&bull; 0 - reports are disabled<br>&bull; 1-100 (1-100%) - change in power<br><b>NOTE:</b> Independent of the Power consumption change in percentage, report will not be send more often than defined by Parameter No. 39.",
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
                        title       : "Parameter No. 39 – Minimal time between reports (O) O1",
                        description : "This parameter determines the minimum time that must elapse before a new power report on O (O1) is sent to the gateway.<br>&bull; 0 - reports are disabled<br>&bull; 1-120 (1-120s) - report interval<br><b>NOTE:</b> This Parameter is in relation with Parameter No. 36<br><b>NOTE:</b> Setting the value less then 30s can result in zwave network congestion state (device slow response and network stability).",
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
                        name        : "configParam91",
                        type        : "enum",
                        title       : "Parameter No. 91 – Water Alarm",
                        description : "This parameter determines which alarm frames the Device should respond to and how. The parameters consist of 4 bytes, the three most significant bytes are set according to the official Z-Wave protocol specification.<br><b>NOTE:</b> Removed in firmware 13 (Wave 1PM) and firmware 14 (Wave 1PM Mini).",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "no action",
                                1: "open relay",
                                2: "close relay",
                        ],
                ],
                num        : 91,
                size       : 4,
                hidden     : false,
                maxFirmware: 13.0,
        ],
        [
                input : [
                        name        : "configParam92",
                        type        : "enum",
                        title       : "Parameter No. 92 – Smoke Alarm",
                        description : "This parameter determines which alarm frames the Device should respond to and how. The parameters consist of 4 bytes, the three most significant bytes are set according to the official Z-Wave protocol specification.<br><b>NOTE:</b> Removed in firmware 13 (Wave 1PM) and firmware 14 (Wave 1PM Mini).",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "no action",
                                1: "open relay",
                                2: "close relay",
                        ],
                ],
                num        : 92,
                size       : 4,
                hidden     : false,
                maxFirmware: 13.0,
        ],
        [
                input : [
                        name        : "configParam93",
                        type        : "enum",
                        title       : "Parameter No. 93 – CO Alarm",
                        description : "This parameter determines which alarm frames the Device should respond to and how. The parameters consist of 4 bytes, the three most significant bytes are set according to the official Z-Wave protocol specification.<br><b>NOTE:</b> Removed in firmware 13 (Wave 1PM) and firmware 14 (Wave 1PM Mini).",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "no action",
                                1: "open relay",
                                2: "close relay",
                        ],
                ],
                num        : 93,
                size       : 4,
                hidden     : false,
                maxFirmware: 13.0,
        ],
        [
                input : [
                        name        : "configParam94",
                        type        : "enum",
                        title       : "Parameter No. 94 – Heat Alarm",
                        description : "This parameter determines which alarm frames the Device should respond to and how. The parameters consist of 4 bytes, the three most significant bytes are set according to the official Z-Wave protocol specification.<br><b>NOTE:</b> Removed in firmware 13 (Wave 1PM) and firmware 14 (Wave 1PM Mini).",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "no action",
                                1: "open relay",
                                2: "close relay",
                        ],
                ],
                num        : 94,
                size       : 4,
                hidden     : false,
                maxFirmware: 13.0,
        ],
]

// Parameter No. 117 - Remote Device reboot: "This parameter enable restarting or rebooting the Device without physical
// intervention. Use this parameter only for troubleshooting scope. After device reboot the parameter value will be set to
// default." Exposed as an action instead of a preference so that it only fires when asked for. Firmware 13 and later only.
@Field static final Integer REBOOT_PARAM = 117
@Field static final BigDecimal REBOOT_MIN_FIRMWARE = 13.0

// Product ids of the models with power metering; the others share the firmware but answer no meter get.
@Field static final List<String> POWER_METER_PRODUCT_IDS = ["132", "143"] // 0x0084 Wave 1PM, 0x008F Wave 1PM Mini

// Wave 1PM switches up to 16 A and Wave 1PM Mini up to 8 A, so any power reading above this is not a real measurement and
// is left out of the lowest/highest power tracking.
@Field static final Integer POWER_MAX_W = 4000

// Notification types and events the device sends (knowledge base, Z-Wave Notifications Command Class section). The device
// switches the output off on all of them; the driver only logs them.
@Field static final Map<Integer, Map<Integer, String>> NOTIFICATION_EVENTS = [
        0x04: [0x02: "Overheat detected"], // Heat Alarm
        0x08: [0x02: "AC mains disconnected", 0x06: "Over-current detected", 0x07: "Over-voltage detected"], // Power management
]

//endregion Specification

//region Core Functions

void installed() {
    logWarn "installed driver version: ${VERSION}"

    cleanupLegacyDriverState()

    runIn(10, configure)
}

void configure() {
    logWarn "performing configuration..."

    cleanupLegacyDriverState()

    List<String> cmds = [
            versionGetCmd(),
            mfgSpecificGetCmd(),
            deviceSpecificGetCmd(),
    ]

    // The set of parameters depends on the firmware version, so parameters are refreshed separately,
    // once the version report had a chance to arrive.
    runIn(5, refreshParams)

    sendCommands(cmds)
}

// Refreshes all configuration parameters the firmware implements.
void refreshParams() {
    List<String> cmds = []

    configParams.each { param ->
        if (supportsParam(param)) {
            cmds << configGetCmd(param)
        }
    }

    runIn(cmds.size() * 2, refresh)

    sendCommands(cmds)
}

void refresh() {
    logWarn "performing device state refresh..."

    List<String> cmds = [
            switchBinaryGetCmd(),
    ]

    if (hasPowerMeter()) {
        cmds << meterGetCmd(0)
        cmds << meterGetCmd(2)
    }

    sendCommands(cmds)
}

void updated() {
    logWarn "performing preferences update..."

    checkLogLevel()
    cleanupLegacyDriverState()

    logWarn "Z-Wave stack detected: ${describeStack()}, outbound supervision is ${useSupervision() ? 'on' : 'off'} (automatic: on for S2 devices on the legacy stack only)"

    sendCommands(getConfigureCmds())
}

// Removes what the previous driver of these devices (Rene Boer's "Shelly Wave 1PM" v1.2) kept in state and
// settings, so devices switched over to this driver tidy themselves without a visit to the device page.
void cleanupLegacyDriverState() {
    ["lastSupervision", "isDigital"].each { state.remove(it) }
    ["logEnable", "txtEnable"].each { device.removeSetting(it) }
}

//endregion Core Functions

//region Capabilities Functions

void on() {
    List<String> cmds = [
            basicSetCmd(0xFF),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchBinaryGetCmd())
    }

    sendCommands(cmds, 200)
}

void off() {
    List<String> cmds = [
            basicSetCmd(0x00),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchBinaryGetCmd())
    }

    sendCommands(cmds, 200)
}

// Resets the accumulated energy on the device and starts the lowest/highest power tracking over.
void resetPower() {
    if (!hasPowerMeter()) {
        logWarn "this model has no power meter, nothing to reset"
        return
    }

    logWarn "resetting accumulated energy..."

    device.deleteCurrentState("powerHigh")
    device.deleteCurrentState("powerLow")

    List<String> cmds = [
            meterResetCmd(),
            meterGetCmd(0),
            meterGetCmd(2),
    ]

    sendCommands(cmds, 1000)
}

// Restarts the device through parameter No. 117, which the device resets to 0 on its own once it comes back.
void reboot() {
    BigDecimal firmware = firmwareVersion()
    if (firmware == null || firmware < REBOOT_MIN_FIRMWARE) {
        logWarn "remote reboot is not supported on firmware ${device.getDataValue('firmwareVersion') ?: 'unknown'} (needs ${REBOOT_MIN_FIRMWARE} or later)"
        return
    }

    logWarn "rebooting device..."
    sendCommands(secureCmd(zwave.configurationV4.configurationSet(parameterNumber: REBOOT_PARAM, size: 1, scaledConfigurationValue: 1)))
}

//endregion Capabilities Functions

//region Device Specific Handlers

// The device reports its state through Switch Binary (association group 1) after every change, whether it was
// commanded from the hub or from the switch on the SW terminal.
void zwaveEvent(hubitat.zwave.commands.switchbinaryv2.SwitchBinaryReport cmd, ep = 0) {
    logTrace "${cmd}"

    // Z-Wave JS optimistically echoes the value we just commanded back as the current value, within
    // a few hundred milliseconds of a Set. The echo is a partial document carrying only currentValue,
    // so it parses with a null targetValue and duration. The device reports v2 and always sends both,
    // so this shape is exclusively the echo. The device's own report follows shortly after.
    if (cmd.targetValue == null && cmd.duration == null) {
        logDebug "Ignoring optimistic value echo from Z-Wave JS: ${cmd}"
        return
    }

    updateSwitch(cmd.value)
}

// A Basic report is either the optimistic echo Z-Wave JS produces for our own Basic Set (partial, ignored) or the
// answer to the Basic Get that Z-Wave JS sends a few seconds after the Set to verify it. Both carry the same state
// the Switch Binary report already delivered, so the full one is applied only to be safe.
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, ep = 0) {
    logTrace "${cmd}"

    if (cmd.targetValue == null && cmd.duration == null) {
        logDebug "Ignoring optimistic value echo from Z-Wave JS: ${cmd}"
        return
    }

    updateSwitch(cmd.value)
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
            updatePowerRange(cmd.scaledMeterValue)
            break;
        default:
            logWarn("Skipped Z-Wave MeterReport: ${cmd.inspect()}")
    }
}

// The device sends a notification when it switches the output off to protect itself (overheat, over-current,
// over-voltage, AC mains disconnected). There is no attribute for these, so they are only logged.
void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, ep = 0) {
    logTrace "${cmd}"

    String eventName = NOTIFICATION_EVENTS[cmd.notificationType as Integer]?.get(cmd.event as Integer)
    if (eventName) {
        logWarn "Device reported: ${eventName}"
    } else {
        logDebug "Skipped Z-Wave NotificationReport of type ${cmd.notificationType} with event ${cmd.event}: ${cmd.inspect()}"
    }
}

void zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv5.FirmwareMdReport cmd, ep = 0) {
    logDebug "Starting firmware update process: ${cmd}"
}

void zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv5.FirmwareUpdateMdRequestReport cmd, ep = 0) {
    if ((cmd.status as Integer) == 0xFF) {
        logDebug "Valid firmware for device, firmware update continuing..."
    } else {
        logWarn "Invalid firmware for device, error code ${cmd.status}"
    }
}

void zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv5.FirmwareUpdateMdStatusReport cmd, ep = 0) {
    if ((cmd.status as Integer) == 0xFF) {
        logDebug "Firmware update successfully completed"
    } else {
        logWarn "Error updating firmware for device, error code ${cmd.status}"
    }
}

// Not logged: the device sends one of these per firmware fragment, which would flood the log.
void zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv5.FirmwareUpdateMdGet cmd, ep = 0) {
}

// Defines custom behaviors for specific parameters.
void handleParameterReport(Map param, value) {
}

// Publishes the switch state from a reported value: 0 is off, anything else is on.
void updateSwitch(value) {
    String switchValue = (value as Integer) ? "on" : "off"
    sendEventWrapper(name: "switch", value: switchValue, descriptionText: "Switch is ${switchValue}")
}

// Tracks the lowest and highest power readings since the last reset. Readings of 0 W (load off) are left out, so the
// lowest value is the lowest power seen while the load was on.
void updatePowerRange(power) {
    BigDecimal value = safeToDec(power, 0)

    if (value <= 0 || value > POWER_MAX_W) {
        logDebug "Power reading ${value} W is outside of the tracked range, not tracking it"
        return
    }

    def high = device.currentValue("powerHigh")
    if (high == null || value > (high as BigDecimal)) {
        sendEventWrapper(name: "powerHigh", value: value, unit: "W", descriptionText: "Highest power seen is ${value} W")
    }

    def low = device.currentValue("powerLow")
    if (low == null || value < (low as BigDecimal)) {
        sendEventWrapper(name: "powerLow", value: value, unit: "W", descriptionText: "Lowest power seen is ${value} W")
    }
}

// Whether this model measures power: the product id is stored by the manufacturer specific report and by the hub at inclusion.
Boolean hasPowerMeter() {
    return device.getDataValue("deviceId") in POWER_METER_PRODUCT_IDS
}

// Firmware version as reported by the device (major.minor), or null until the version report has arrived.
BigDecimal firmwareVersion() {
    String firmware = device?.getDataValue("firmwareVersion")
    if (!firmware?.isNumber()) {
        return null
    }

    return firmware.toBigDecimal()
}

// Whether the firmware running on the device implements the parameter. Before the first version report the
// firmware is unknown and every parameter is assumed to exist, so nothing is skipped by mistake.
Boolean supportsParam(Map param) {
    BigDecimal firmware = firmwareVersion()
    if (firmware == null) {
        return true
    }
    if (param.minFirmware != null && firmware < (param.minFirmware as BigDecimal)) {
        return false
    }
    if (param.maxFirmware != null && firmware >= (param.maxFirmware as BigDecimal)) {
        return false
    }
    return true
}

//endregion Device Specific Handlers

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
    hubitat.zwave.Command cmd

    // On Z-Wave JS zwave.parse() can throw on reports it does not know how to rebuild, instead of returning
    // null; without the catch the exception would end the whole parse() call and the report would be lost silently.
    try {
        cmd = zwave.parse(description, commandClassVersions)
    } catch (e) {
        logErr "Unable to parse: ${description} (${e})"
        return
    }

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

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {
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

    Integer paramNum = cmd.parameterNumber as Integer
    Map param = getParam(paramNum)
    Long val = cmd.scaledConfigurationValue

    if (param) {
        //Convert scaled signed integer to unsigned. The size comes from the specification, because on Z-Wave JS
        //the report's size is synthesised from the value's magnitude and not the parameter's real width.
        if (param.format >= 1 || param.format == null) {
            Long sizeFactor = Math.pow(256, param.size).round()
            if (val < 0) {
                val += sizeFactor
            }
        }

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

// Basic Set is mapped to Switch Binary by the device: 0x00 turns the load off, 0xFF turns it on.
String basicSetCmd(Integer value, Integer ep = 0) {
    return superviseCmd(zwave.basicV1.basicSet(value: value), ep)
}

String switchBinaryGetCmd(Integer ep = 0) {
    return secureCmd(zwave.switchBinaryV2.switchBinaryGet(), ep)
}

String meterGetCmd(scale, Integer ep = 0) {
    return secureCmd(zwave.meterV5.meterGet(scale: scale), ep)
}

String meterResetCmd(Integer ep = 0) {
    return secureCmd(zwave.meterV5.meterReset(), ep)
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
        supervisedPackets[device.id][sID] = [cmd: cmdEncap, cmdRaw: cmd, endpoint: ep]

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

    Map session = supervisedPackets[device.id][sID]
    if (session != null) {
        handleSupervisionResult(session["cmdRaw"], session["endpoint"], status)
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

void handleSupervisionResult(hubitat.zwave.Command cmd, ep = 0, result) {
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
        if (!param.hidden && supportsParam(param)) {
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
