/**
 *  Heatit ZM Dimmer Z-Wave 800 Driver for Hubitat
 *  Date: 20.09.2026
 *  Author: Bogusław Wójcik
 *
 *  CHANGELOG:
 *  (no released version yet)
 *
 *  DESCRIPTION:
 *  This is a custom driver for Heatit ZM Dimmer 250W (article no. 14 444 49), an in-wall dimmer with power metering and two
 *  external switch inputs that can act as scene controllers.
 *
 *  The driver started as a rewrite of Rene Boer's "Heatit ZM Dimmer" driver v1.6 and keeps all of its functionality:
 *  - switch, level and level change control,
 *  - power and energy metering with the lowest and highest power seen since the last meter reset,
 *  - S1 and S2 switches as buttons 1 and 2 (pushed, held, released, double tapped) when set up as scene controllers,
 *  - overload protection notification,
 *  - all parameters defined in the manual are configurable, and the configuration is properly saved on the device.
 *
 *  It differs in the implementation:
 *  - it works on both the legacy Z-Wave stack and Z-Wave JS: on Z-Wave JS the platform supervises commands itself, so the driver
 *    does not wrap them again, and the optimistic value echo of the stack is filtered out,
 *  - supervision on the legacy stack retries a bounded number of times and cannot loop,
 *  - the "held" button event is reported once per hold.
 *
 *  NOTES:
 *  - The driver has been tested on Heatit ZM Dimmer with firmware version 1.0 and securely paired (S2 Authenticated) with Hubitat.
 *  - Association groups 2 and 3 (Basic Set and Switch Multilevel to other devices) are not configurable from the driver.
 *
 *  Copyright 2026 Bogusław Wójcik
 *
 *  Based on "Heatit ZM Dimmer" driver v1.6 (16.01.2024) by Rene Boer, https://github.com/reneboer/Hubitat,
 *  Copyright 2024 Rene Boer, licensed under the Apache License, Version 2.0.
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
            name: "Heatit ZM Dimmer 250W",
            namespace: "boguslaw-wojcik",
            author: "Bogusław Wójcik",
            singleThreaded: true,
            importUrl: "https://github.com/boguslaw-wojcik/hubitat/blob/main/drivers/heatit/heatit_zm_dimmer.groovy"
    ) {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"
        capability "Configuration"
        capability "Refresh"

        // Lowest and highest power seen since the last meter reset.
        attribute "powerHigh", "number"
        attribute "powerLow", "number"
        // 1 while the device reports a power overload (load above 250 W), 0 otherwise.
        attribute "overloadProtection", "number"

        // Resets the accumulated energy on the device and clears the lowest and highest power seen.
        command "resetPower"

        fingerprint mfr: "019B", prod: "0021", deviceId: "2101", inClusters: "0x5E,0x55,0x98,0x9F,0x6C", secureInClusters: "0x86,0x26,0x32,0x5B,0x70,0x71,0x8E,0x87,0x85,0x59,0x72,0x5A,0x73,0x7A", controllerType: "ZWV", deviceJoinName: "Heatit ZM Dimmer"
        fingerprint mfr: "019B", prod: "0021", deviceId: "2101", inClusters: "0x5E,0x26,0x32,0x70,0x5B,0x8E,0x87,0x85,0x59,0x55,0x71,0x86,0x72,0x5A,0x73,0x98,0x9F,0x6C,0x7A", controllerType: "ZWV", deviceJoinName: "Heatit ZM Dimmer"
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

// Command class versions specific for the device, as listed in the installers manual (2023-B, section 23).
@Field static final Map commandClassVersions = [
        0x85: 2, // COMMAND_CLASS_ASSOCIATION_V2
        0x59: 3, // COMMAND_CLASS_ASSOCIATION_GRP_INFO_V3
        0x5A: 1, // COMMAND_CLASS_DEVICE_RESET_LOCALLY_V1
        0x7A: 5, // COMMAND_CLASS_FIRMWARE_UPDATE_MD_V5
        0x87: 3, // COMMAND_CLASS_INDICATOR_V3
        0x72: 2, // COMMAND_CLASS_MANUFACTURER_SPECIFIC_V2
        0x8E: 3, // COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION_V3
        0x73: 1, // COMMAND_CLASS_POWERLEVEL_V1
        0x98: 1, // COMMAND_CLASS_SECURITY_V1
        0x9F: 1, // COMMAND_CLASS_SECURITY_2_V1
        0x6C: 1, // COMMAND_CLASS_SUPERVISION_V1
        0x55: 2, // COMMAND_CLASS_TRANSPORT_SERVICE_V2
        0x86: 3, // COMMAND_CLASS_VERSION_V3
        0x5E: 2, // COMMAND_CLASS_ZWAVEPLUS_INFO_V2
        0x22: 1, // COMMAND_CLASS_APPLICATION_STATUS_V1
        0x20: 2, // COMMAND_CLASS_BASIC_V2
        0x5B: 3, // COMMAND_CLASS_CENTRAL_SCENE_V3
        0x70: 4, // COMMAND_CLASS_CONFIGURATION_V4
        0x32: 5, // COMMAND_CLASS_METER_V5 (v6 report objects fail to construct under Z-Wave JS: MeterReport.setMeterType(Integer))
        0x71: 8, // COMMAND_CLASS_NOTIFICATION_V8
        0x26: 4, // COMMAND_CLASS_SWITCH_MULTILEVEL_V4
]

// Specification of configuration parameters (installers manual 2023-B, section 17).
@Field static final List<Map> configParams = [
        [
                input : [
                        name        : "configParam1",
                        type        : "number",
                        title       : "Parameter No. 1 – Power restore level",
                        description : "The state the dimmer should return to once power is restored after a power failure.<br>&bull; 0 - Off<br>&bull; 1 - 99 - 1% - 99%<br>&bull; 100 - Returns to level before power outage (Default)",
                        defaultValue: 100,
                        required    : false,
                        range       : "0..100"
                ],
                num   : 1,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam2",
                        type        : "number",
                        title       : "Parameter No. 2 – Switch ON level",
                        description : "Defines the dimming level when restored from the OFF state.<br>&bull; 0 - Restores last dim level (Default)<br>&bull; 1 - 99 - 1% - 99%",
                        defaultValue: 0,
                        required    : false,
                        range       : "0..99"
                ],
                num   : 2,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam3",
                        type        : "number",
                        title       : "Parameter No. 3 – Automatic turn OFF",
                        description : "Time for the dimmer to turn off automatically after turning it on.<br>&bull; 0 - Auto OFF disabled (Default)<br>&bull; 1 - 86400 - Duration 1 - 86400 seconds",
                        defaultValue: 0,
                        required    : false,
                        range       : "0..86400"
                ],
                num   : 3,
                size  : 4,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam4",
                        type        : "number",
                        title       : "Parameter No. 4 – Automatic turn ON",
                        description : "Time for the dimmer to turn on automatically after turning it off.<br>&bull; 0 - Auto ON disabled (Default)<br>&bull; 1 - 86400 - Duration 1 - 86400 seconds",
                        defaultValue: 0,
                        required    : false,
                        range       : "0..86400"
                ],
                num   : 4,
                size  : 4,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam5",
                        type        : "number",
                        title       : "Parameter No. 5 – Turn off delay time",
                        description : "The time it takes before the dimmer turns off after turning it off.<br>&bull; 0 - Disabled (Default)<br>&bull; 1 - 60 - Duration 1 - 60 seconds",
                        defaultValue: 0,
                        required    : false,
                        range       : "0..60"
                ],
                num   : 5,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam6",
                        type        : "enum",
                        title       : "Parameter No. 6 – S1 functionality",
                        description : "Decide the S1 switch functionality.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "Single press and hold for dimming. Double press for 100%. Triple press for inclusion. (Default)",
                                1: "Scene controller.",
                                2: "Scene controller and dimming.",
                                3: "Disabled.",
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
                        title       : "Parameter No. 7 – S2 functionality",
                        description : "Decide the S2 switch functionality.",
                        defaultValue: 1,
                        required    : false,
                        options     : [
                                0: "Single press and hold for dimming. Double press for scene controller. Triple press for inclusion.",
                                1: "Scene controller functionality. (Default)",
                                2: "Scene controller and dimming.",
                                3: "Disabled.",
                        ],
                ],
                num   : 7,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam8",
                        type        : "number",
                        title       : "Parameter No. 8 – Dimming duration",
                        description : "Define how long it takes to dim when using the external switch. The driver uses the same duration for the start level change command.<br>&bull; 0 - Instantly<br>&bull; 1 - 100 - 0.1 - 10 seconds (Default 50 = 5 seconds)",
                        defaultValue: 50,
                        required    : false,
                        range       : "0..100"
                ],
                num   : 8,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam9",
                        type        : "enum",
                        title       : "Parameter No. 9 – Choose the dimmer curve",
                        description : "Choose if the dimmer uses linear or logarithmic dimming.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "Linear dimming (Default)",
                                1: "Logarithmic dimming",
                        ],
                ],
                num   : 9,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam10",
                        type        : "enum",
                        title       : "Parameter No. 10 – Load dimming mode",
                        description : "Choose the dimming type.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "Trailing edge (Default)",
                                1: "Leading edge",
                        ],
                ],
                num   : 10,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam11",
                        type        : "number",
                        title       : "Parameter No. 11 – Maximum dim level",
                        description : "Highest dim level of the dimmer.<br>&bull; 2 - 99 - 2% - 99% (Default 90)<br><b>NOTE:</b> Autocalibration initiated when flicker is detected updates this parameter on the device; press Configure to read the calibrated value back.",
                        defaultValue: 90,
                        required    : false,
                        range       : "2..99"
                ],
                num   : 11,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam12",
                        type        : "number",
                        title       : "Parameter No. 12 – Minimum dim level",
                        description : "Lowest dim level of the dimmer.<br>&bull; 1 - 98 - 1% - 98% (Default 15)<br><b>NOTE:</b> Calibrating with the \"Min.\" button on the device updates this parameter on the device; press Configure to read the calibrated value back.",
                        defaultValue: 15,
                        required    : false,
                        range       : "1..98"
                ],
                num   : 12,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam13",
                        type        : "number",
                        title       : "Parameter No. 13 – Meter report threshold",
                        description : "Threshold for device to send meter report in W.<br>&bull; 0 - Disabled<br>&bull; 1 - 250 - 1 - 250 W (Default 10 W)",
                        defaultValue: 10,
                        required    : false,
                        range       : "0..250"
                ],
                num   : 13,
                size  : 1,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam14",
                        type        : "number",
                        title       : "Parameter No. 14 – Meter report interval",
                        description : "Time interval between consecutive meter reports in seconds.<br>&bull; 30 - 65535 - 30 - 65535 seconds (Default 810 seconds = 13.5 minutes)",
                        defaultValue: 810,
                        required    : false,
                        range       : "30..65535"
                ],
                num   : 14,
                size  : 2,
                hidden: false,
        ],
        [
                input : [
                        name        : "configParam15",
                        type        : "enum",
                        title       : "Parameter No. 15 – ON/OFF functionality",
                        description : "Decide if the connected load should only turn ON/OFF and not dim. When enabled the load can no longer be dimmed, but only turned ON and OFF; the manufacturer recommends 3-wire mode for this.",
                        defaultValue: 0,
                        required    : false,
                        options     : [
                                0: "ON/OFF mode disabled (Default)",
                                1: "ON/OFF mode enabled",
                        ],
                ],
                num   : 15,
                size  : 1,
                hidden: false,
        ],
]

// The device has two external switch inputs, S1 and S2, reported as buttons 1 and 2.
@Field static final Integer NUMBER_OF_BUTTONS = 2

// Parameter No. 8 counts in tenths of a second; the driver derives the start level change duration from it.
@Field static final Integer DIMMING_DURATION_PARAM = 8

// Notification type "Power management" and its "Overload detected" event (installers manual, section 22.6).
@Field static final Integer NOTIFICATION_POWER_MANAGEMENT = 0x08
@Field static final Integer NOTIFICATION_EVENT_IDLE = 0x00
@Field static final Integer NOTIFICATION_EVENT_OVERLOAD = 0x08

// The dimmer cuts the load at 300 W, so any power reading above that is not a real measurement and is
// left out of the lowest/highest power tracking.
@Field static final Integer POWER_CUT_OFF_W = 300

//endregion Specification

//region Core Functions

void installed() {
    logWarn "installed driver version: ${VERSION}"

    cleanupLegacyDriverState()
    sendEventWrapper(name: "numberOfButtons", value: NUMBER_OF_BUTTONS, descriptionText: "Device has ${NUMBER_OF_BUTTONS} buttons")

    runIn(10, configure)
}

void configure() {
    logWarn "performing configuration..."

    cleanupLegacyDriverState()
    sendEventWrapper(name: "numberOfButtons", value: NUMBER_OF_BUTTONS, descriptionText: "Device has ${NUMBER_OF_BUTTONS} buttons")

    List<String> cmds = [
            versionGetCmd(),
            mfgSpecificGetCmd(),
            deviceSpecificGetCmd(),
    ]

    configParams.each { param ->
        cmds << configGetCmd(param)
    }

    runIn(cmds.size() * 2, refresh)

    sendCommands(cmds)
}

void refresh() {
    logWarn "performing device state refresh..."

    List<String> cmds = [
            switchMultilevelGetCmd(),
            meterGetCmd(0),
            meterGetCmd(2),
            notificationGetCmd(NOTIFICATION_POWER_MANAGEMENT, NOTIFICATION_EVENT_IDLE),
    ]

    sendCommands(cmds)
}

void updated() {
    logWarn "performing preferences update..."

    checkLogLevel()
    cleanupLegacyDriverState()

    logWarn "Z-Wave stack detected: ${describeStack()}, outbound supervision is ${useSupervision() ? 'on' : 'off'} (automatic: on for S2 devices on the legacy stack only)"

    sendCommands(getConfigureCmds())
}

// Removes what the previous driver of these devices (Rene Boer's "Heatit ZM Dimmer" v1.6) kept in state and
// settings, so devices switched over to this driver tidy themselves without a visit to the device page.
void cleanupLegacyDriverState() {
    ["lastSupervision", "1", "2"].each { state.remove(it) }
    ["logEnable", "txtEnable"].each { device.removeSetting(it) }

    // The previous driver stored Parameter No. 15 as a bool. updateSetting() alone does not retype a stored
    // setting, so a configuration report for it left the preference at "false"; drop it and store the enum.
    String onOffMode = settings.configParam15?.toString()
    if (onOffMode in ["true", "false"]) {
        device.removeSetting("configParam15")
        device.updateSetting("configParam15", [value: onOffMode == "true" ? "1" : "0", type: "enum"])
    }
}

//endregion Core Functions

//region Capabilities Functions

void on() {
    List<String> cmds = [
            basicSetCmd(0xFF),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd())
    }

    sendCommands(cmds, 200)
}

void off() {
    List<String> cmds = [
            basicSetCmd(0x00),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd())
    }

    sendCommands(cmds, 200)
}

// Sets the level in percent, optionally over a transition time in seconds. Without a duration the change is instant.
void setLevel(level, duration = 0) {
    Integer value = safeToInt(level, 0)
    if (value > 99) {
        value = 99
    } else if (value < 0) {
        value = 0
    }

    List<String> cmds = [
            switchMultilevelSetCmd(value, toDimmingDuration(duration)),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd())
    }

    sendCommands(cmds, 200)
}

// Starts dimming up or down at the speed set by Parameter No. 8, the same one the external switch uses.
void startLevelChange(direction) {
    Boolean down
    if (direction == "up") {
        down = false
    } else if (direction == "down") {
        down = true
    } else {
        logErr("Invalid direction: ${direction}")
        return
    }

    Integer duration = safeToInt(getParamValue(getParam(DIMMING_DURATION_PARAM)) / 10, 5)

    sendCommands(switchMultilevelStartLvChCmd(down, duration))
}

void stopLevelChange() {
    List<String> cmds = [
            switchMultilevelStopLvChCmd(),
    ]

    // If the device does not support S2 security, request the state to be updated.
    if (!supportsSupervision()) {
        cmds.add(switchMultilevelGetCmd())
    }

    sendCommands(cmds, 200)
}

// Resets the accumulated energy on the device and starts the lowest/highest power tracking over.
void resetPower() {
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

void push(button) {
    sendButtonEvent("pushed", button, "digital")
}

void hold(button) {
    sendButtonEvent("held", button, "digital")
}

void release(button) {
    sendButtonEvent("released", button, "digital")
}

void doubleTap(button) {
    sendButtonEvent("doubleTapped", button, "digital")
}

//endregion Capabilities Functions

//region Device Specific Handlers

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep = 0) {
    logTrace "${cmd}"

    // Z-Wave JS optimistically echoes the value we just commanded back as the current value, within
    // a few hundred milliseconds of a Set. The echo is a partial document carrying only currentValue,
    // so it parses with a null targetValue and duration. The device reports v4 and always sends both,
    // so this shape is exclusively the echo. The device's own report follows shortly after.
    if (cmd.targetValue == null && cmd.duration == null) {
        logDebug "Ignoring optimistic value echo from Z-Wave JS: ${cmd}"
        return
    }

    updateSwitchLevel(cmd.value)
}

// The device reports its state through Switch Multilevel (association group 1); a Basic report can only be
// the optimistic echo Z-Wave JS produces for our own Basic Set, so it carries nothing new.
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, ep = 0) {
    logDebug "Ignoring Basic report, the device reports its state through Switch Multilevel: ${cmd}"
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

// Key attributes as listed in the installers manual, section 22.5: 0x00 pressed once, 0x01 released (sent only
// after a hold), 0x02 held (sent while the button is held for more than 500 ms), 0x03 pressed twice,
// 0x04 pressed three times.
void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd, ep = 0) {
    logTrace "${cmd}"

    Integer button = cmd.sceneNumber as Integer
    Integer key = cmd.keyAttributes as Integer

    switch (key) {
        case 0x00:
            sendButtonEvent("pushed", button, "physical")
            break
        case 0x01:
            heldButtonsFor().remove(button)
            sendButtonEvent("released", button, "physical")
            break
        case 0x02:
            // The device keeps repeating the held notification while the button stays pressed; report it once.
            if (heldButtonsFor().add(button)) {
                sendButtonEvent("held", button, "physical")
            }
            break
        case 0x03:
            sendButtonEvent("doubleTapped", button, "physical")
            break
        case 0x04:
            logDebug "Button ${button} was pressed three times, which has no matching Hubitat button event"
            break
        default:
            logWarn "Skipped Z-Wave CentralSceneNotification with unknown key attribute: ${cmd.inspect()}"
    }
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, ep = 0) {
    logTrace "${cmd}"

    if ((cmd.notificationType as Integer) != NOTIFICATION_POWER_MANAGEMENT) {
        logDebug "Skipped Z-Wave NotificationReport of type ${cmd.notificationType}: ${cmd.inspect()}"
        return
    }

    switch (cmd.event as Integer) {
        case NOTIFICATION_EVENT_IDLE:
            sendEventWrapper(name: "overloadProtection", value: 0, descriptionText: "Power overload is not detected")
            break
        case NOTIFICATION_EVENT_OVERLOAD:
            sendEventWrapper(name: "overloadProtection", value: 1, descriptionText: "Power overload detected, the load will be turned off above ${POWER_CUT_OFF_W} W")
            break
        default:
            logDebug "Skipped Z-Wave NotificationReport with power management event ${cmd.event}: ${cmd.inspect()}"
    }
}

// Defines custom behaviors for specific parameters.
void handleParameterReport(Map param, value) {
}

// Publishes switch and level from a reported dimmer value: 0 is off, 1 - 99 is the level, 0xFF stands for
// fully on, and 0xFE means the device does not know its level.
void updateSwitchLevel(value) {
    Integer level = value as Integer

    if (level == 0xFE) {
        logDebug "Device reported an unknown level"
        return
    }

    if (level > 99) {
        level = 100
    }

    String switchValue = level > 0 ? "on" : "off"
    sendEventWrapper(name: "switch", value: switchValue, descriptionText: "Switch is ${switchValue}")
    sendEventWrapper(name: "level", value: level, unit: "%", descriptionText: "Level is ${level} %")
}

// Tracks the lowest and highest power readings since the last reset.
void updatePowerRange(power) {
    BigDecimal value = safeToDec(power, 0)

    if (value < 0 || value > POWER_CUT_OFF_W) {
        logDebug "Power reading ${value} W is outside of the range the device can deliver, not tracking it"
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

void sendButtonEvent(String action, button, String type) {
    Integer number = safeToInt(button, 0)

    if (number < 1 || number > NUMBER_OF_BUTTONS) {
        logWarn "Button number must be between 1 and ${NUMBER_OF_BUTTONS}, got ${button}"
        return
    }

    sendEventWrapper(name: action, value: number, descriptionText: "Button ${number} was ${action} [${type}]", isStateChange: true, type: type)
}

// Buttons currently held down, per device. Only used to report "held" once per hold; the device repeats
// the notification every few hundred milliseconds while the button stays pressed. Losing this on a hub
// reboot or a driver save costs at most one extra "held" event, so it does not need to live in state.
@Field static Map<String, Set<Integer>> heldButtonsByDevice = new java.util.concurrent.ConcurrentHashMap()

Set<Integer> heldButtonsFor() {
    if (heldButtonsByDevice[device.id] == null) {
        heldButtonsByDevice[device.id] = new HashSet<Integer>()
    }
    return heldButtonsByDevice[device.id]
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

// Basic Set is mapped to Switch Multilevel by the device: 0x00 turns the load off, 0xFF turns it on to the
// level defined by Parameter No. 2, 0x01 - 0x63 is 1% - 99%.
String basicSetCmd(Integer value, Integer ep = 0) {
    return superviseCmd(zwave.basicV1.basicSet(value: value), ep)
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
    return secureCmd(zwave.meterV5.meterGet(scale: scale), ep)
}

String meterResetCmd(Integer ep = 0) {
    return secureCmd(zwave.meterV5.meterReset(), ep)
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

// Converts a transition time in seconds to the Z-Wave duration byte: 0 is instant, 1 - 127 is seconds,
// 128 - 254 is 1 - 127 minutes.
Integer toDimmingDuration(duration) {
    Integer seconds = safeToInt(duration, 0)

    if (seconds <= 0) {
        return 0
    }
    if (seconds <= 127) {
        return seconds
    }

    Integer minutes = safeToInt(seconds / 60, 1)
    if (minutes > 127) {
        minutes = 127
    }
    return 127 + minutes
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
