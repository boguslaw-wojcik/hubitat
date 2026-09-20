/**
 *  BITUO TECHNIK SPM02 3-Phase Zigbee Energy Meter Driver for Hubitat
 *  Date: 20.09.2026
 *  Author: Bogusław Wójcik
 *
 *  CHANGELOG:
 *  - v0.1.0 - 20.09.2026: Initial release: total and per-phase power, voltage and current, imported and exported energy, frequency and power factor, with reporting intervals and change thresholds configurable in the preferences and a device interview command.
 *
 *  DESCRIPTION:
 *  This is a custom driver for the BITUO TECHNIK SPM02X001 smart energy monitor for 3P+N systems, a Zigbee 3.0
 *  clamp (CT) meter also sold as SPM02X and as the Zemismart SPM02-3Z3. The device implements the standard
 *  Metering (0x0702) and Electrical Measurement (0x0B04) clusters, no Tuya data points.
 *
 *  What it exposes:
 *  - standard attributes for the whole installation: power (total active power, W), energy (total imported energy, kWh),
 *    voltage (average of the three phases, V), amperage (sum of the three phases, A) and frequency (Hz),
 *  - per-phase attributes: powerL1..L3, voltageL1..L3, amperageL1..L3, and optionally powerFactorL1..L3,
 *  - optionally the total reactive and apparent power and the total exported energy.
 *
 *  Reporting: the driver binds both clusters to the hub and configures attribute reporting on the device from the
 *  preferences (minimum and maximum interval, and a change threshold per quantity). The hub is not polling; the device
 *  reports on its own whenever a value moves by more than the threshold, and at least once per maximum interval.
 *  Every Configure Reporting response is checked and rejected attributes are logged as warnings.
 *
 *  Scaling: the device advertises multipliers and divisors for every quantity, and the driver reads them instead of
 *  assuming. The one known firmware quirk (zigbee2mqtt issue #19705) is that the power divisor is advertised as 1000 while
 *  the power attributes are already in watts. The "Power scaling" preference defaults to auto-detection, which compares a
 *  phase's active power with its voltage × current the first time all three are known and remembers the outcome.
 *
 *  The "interview" command asks the device what it actually supports: it discovers attributes and commands on every
 *  cluster (standard and manufacturer specific), reads the Basic cluster, and reads back the reporting configuration.
 *  The results go to the log at info level, so have the log open while running it.
 *
 *  NOTES:
 *  - Tested with SPM02X001 firmware 003.00.04 (application 0x0B). The SPM02X and SPM02-3Z3 fingerprints share the
 *    firmware according to zigbee2mqtt but have not been tested.
 *  - Phases are named L1, L2, L3 and map to Zigbee phases A, B, C.
 *  - Firmware 003.00.04 has no per-phase energy: the tier summation attributes zigbee2mqtt maps to phases are not
 *    implemented (UNSUPPORTED_ATTRIBUTE), nor is InstantaneousDemand. The Metering and Electrical Measurement clusters
 *    accept no commands at all and there are no manufacturer-specific attributes, so the energy counter cannot be reset
 *    from the driver.
 *
 *  Copyright 2026 Bogusław Wójcik
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
import java.math.RoundingMode

@Field static final String VERSION = "0.1.0"

// Cluster ids.
@Field static final int CL_BASIC = 0x0000
@Field static final int CL_IDENTIFY = 0x0003
@Field static final int CL_METERING = 0x0702
@Field static final int CL_ELECTRICAL = 0x0B04
@Field static final int CL_DIAGNOSTICS = 0x0B05

// ZCL data types.
@Field static final int T_UINT8 = 0x20
@Field static final int T_UINT16 = 0x21
@Field static final int T_UINT24 = 0x22
@Field static final int T_UINT32 = 0x23
@Field static final int T_UINT48 = 0x25
@Field static final int T_INT8 = 0x28
@Field static final int T_INT16 = 0x29
@Field static final int T_INT32 = 0x2B

// Manufacturer code seen in the OTA header of this firmware.
@Field static final int MFR_CODE = 0x133D

// Electrical Measurement attributes that become events: attribute id -> [event name, unit, scaling kind].
@Field static final Map<Integer, Map> ELECTRICAL_ATTRS = [
        0x0300: [name: "frequency", unit: "Hz", kind: "frequency", type: 0x21],
        0x0304: [name: "power", unit: "W", kind: "power", type: 0x2B],
        0x0305: [name: "reactivePower", unit: "VAr", kind: "power", type: 0x2B],
        0x0306: [name: "apparentPower", unit: "VA", kind: "power", type: 0x23],
        0x0505: [name: "voltageL1", unit: "V", kind: "voltage", type: 0x21],
        0x0508: [name: "amperageL1", unit: "A", kind: "current", type: 0x21],
        0x050B: [name: "powerL1", unit: "W", kind: "power", type: 0x29],
        0x0510: [name: "powerFactorL1", unit: "%", kind: "none", type: 0x28],
        0x0905: [name: "voltageL2", unit: "V", kind: "voltage", type: 0x21],
        0x0908: [name: "amperageL2", unit: "A", kind: "current", type: 0x21],
        0x090B: [name: "powerL2", unit: "W", kind: "power", type: 0x29],
        0x0910: [name: "powerFactorL2", unit: "%", kind: "none", type: 0x28],
        0x0A05: [name: "voltageL3", unit: "V", kind: "voltage", type: 0x21],
        0x0A08: [name: "amperageL3", unit: "A", kind: "current", type: 0x21],
        0x0A0B: [name: "powerL3", unit: "W", kind: "power", type: 0x29],
        0x0A10: [name: "powerFactorL3", unit: "%", kind: "none", type: 0x28],
]

// Metering attributes that become events.
@Field static final Map<Integer, Map> METERING_ATTRS = [
        0x0000: [name: "energy", unit: "kWh", kind: "energy", type: 0x25],
        0x0001: [name: "energyExported", unit: "kWh", kind: "energy", type: 0x25],
]

// Multiplier/divisor attributes: cluster -> attribute id -> key in state.scaling.
@Field static final Map<Integer, Map<Integer, String>> SCALING_ATTRS = [
        0x0B04: [0x0400: "frequencyMultiplier", 0x0401: "frequencyDivisor",
                 0x0600: "voltageMultiplier", 0x0601: "voltageDivisor",
                 0x0602: "currentMultiplier", 0x0603: "currentDivisor",
                 0x0604: "powerMultiplier", 0x0605: "powerDivisor"],
        0x0702: [0x0301: "energyMultiplier", 0x0302: "energyDivisor"],
]

// What this firmware is known to advertise; used until the device has answered.
@Field static final Map DEFAULT_SCALING = [
        frequencyMultiplier: 1, frequencyDivisor: 100,
        voltageMultiplier: 1, voltageDivisor: 100,
        currentMultiplier: 1, currentDivisor: 100,
        powerMultiplier: 1, powerDivisor: 1000,
        energyMultiplier: 1, energyDivisor: 100,
]

// Decimal places of the emitted values, per scaling kind.
@Field static final Map<String, Integer> DECIMALS = [frequency: 2, power: 0, voltage: 1, current: 2, energy: 2, none: 0]

// Attribute names for readable interview logs.
@Field static final Map<Integer, Map<Integer, String>> ATTR_NAMES = [
        0x0000: [0x0000: "ZCLVersion", 0x0001: "ApplicationVersion", 0x0002: "StackVersion", 0x0003: "HWVersion",
                 0x0004: "ManufacturerName", 0x0005: "ModelIdentifier", 0x0006: "DateCode", 0x0007: "PowerSource",
                 0x000A: "ProductCode", 0x0010: "LocationDescription", 0x4000: "SWBuildID", 0xFFFD: "ClusterRevision"],
        0x0003: [0x0000: "IdentifyTime", 0xFFFD: "ClusterRevision"],
        0x0702: [0x0000: "CurrentSummationDelivered", 0x0001: "CurrentSummationReceived", 0x0002: "CurrentMaxDemandDelivered",
                 0x0100: "CurrentTier1SummationDelivered", 0x0101: "CurrentTier1SummationReceived",
                 0x0102: "CurrentTier2SummationDelivered", 0x0103: "CurrentTier2SummationReceived",
                 0x0104: "CurrentTier3SummationDelivered", 0x0105: "CurrentTier3SummationReceived",
                 0x0200: "Status", 0x0300: "UnitOfMeasure", 0x0301: "Multiplier", 0x0302: "Divisor",
                 0x0303: "SummationFormatting", 0x0304: "DemandFormatting", 0x0306: "MeteringDeviceType",
                 0x0400: "InstantaneousDemand", 0x0601: "DemandLimit", 0xFFFD: "ClusterRevision"],
        0x0B04: [0x0000: "MeasurementType", 0x0300: "ACFrequency", 0x0304: "TotalActivePower", 0x0305: "TotalReactivePower",
                 0x0306: "TotalApparentPower", 0x0400: "ACFrequencyMultiplier", 0x0401: "ACFrequencyDivisor",
                 0x0402: "PowerMultiplier", 0x0403: "PowerDivisor", 0x0404: "HarmonicCurrentMultiplier",
                 0x0405: "PhaseHarmonicCurrentMultiplier", 0x0505: "RMSVoltage", 0x0508: "RMSCurrent",
                 0x050B: "ActivePower", 0x050E: "ReactivePower", 0x050F: "ApparentPower", 0x0510: "PowerFactor",
                 0x0600: "ACVoltageMultiplier", 0x0601: "ACVoltageDivisor", 0x0602: "ACCurrentMultiplier",
                 0x0603: "ACCurrentDivisor", 0x0604: "ACPowerMultiplier", 0x0605: "ACPowerDivisor",
                 0x0905: "RMSVoltagePhB", 0x0908: "RMSCurrentPhB", 0x090B: "ActivePowerPhB", 0x090E: "ReactivePowerPhB",
                 0x090F: "ApparentPowerPhB", 0x0910: "PowerFactorPhB",
                 0x0A05: "RMSVoltagePhC", 0x0A08: "RMSCurrentPhC", 0x0A0B: "ActivePowerPhC", 0x0A0E: "ReactivePowerPhC",
                 0x0A0F: "ApparentPowerPhC", 0x0A10: "PowerFactorPhC",
                 0x0800: "ACAlarmsMask", 0x0801: "ACVoltageOverload", 0x0802: "ACCurrentOverload",
                 0x0803: "ACActivePowerOverload", 0xFFFD: "ClusterRevision"],
        0x0B05: [0x011C: "LastMessageLQI", 0x011D: "LastMessageRSSI", 0xFFFD: "ClusterRevision"],
]

metadata {
    definition(
            name: "BITUO TECHNIK SPM02 3-Phase Meter",
            namespace: "boguslaw-wojcik",
            author: "Bogusław Wójcik",
            singleThreaded: true,
            importUrl: "https://github.com/boguslaw-wojcik/hubitat/blob/main/drivers/bituo/bituo_spm02_3phase_meter.groovy"
    ) {
        capability "Sensor"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "CurrentMeter"
        capability "Configuration"
        capability "Refresh"

        // Per-phase measurements. L1, L2, L3 are Zigbee phases A, B, C.
        attribute "powerL1", "number"
        attribute "powerL2", "number"
        attribute "powerL3", "number"
        attribute "voltageL1", "number"
        attribute "voltageL2", "number"
        attribute "voltageL3", "number"
        attribute "amperageL1", "number"
        attribute "amperageL2", "number"
        attribute "amperageL3", "number"

        // Extended measurements, reported only when enabled in the preferences.
        attribute "energyExported", "number"
        attribute "powerFactorL1", "number"
        attribute "powerFactorL2", "number"
        attribute "powerFactorL3", "number"
        attribute "reactivePower", "number"
        attribute "apparentPower", "number"

        // Asks the device what it supports: attributes, commands, reporting configuration. Results go to the log.
        command "interview"
        // Identify: the only thing the device can be told to do besides reporting. Blinks its indicator for 10 seconds.
        command "identify"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0702,0B04,0B05", outClusters: "0003,0019", manufacturer: "BITUO TECHNIK", model: "SPM02X001", deviceJoinName: "BITUO TECHNIK SPM02 3-Phase Meter"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0702,0B04,0B05", outClusters: "0003,0019", manufacturer: "BITUO TECHNIK", model: "SPM02X", deviceJoinName: "BITUO TECHNIK SPM02 3-Phase Meter"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0702,0B04,0B05", outClusters: "0003,0019", manufacturer: "BITUO TECHNIK", model: "SPM02-3Z3", deviceJoinName: "Zemismart SPM02-3Z3 3-Phase Meter"
    }

    preferences {
        input name: "reportMinInterval", type: "number", title: "Minimum reporting interval (s)",
                description: "The device will not report a quantity more often than this, even if it keeps changing. 1 - 3600, default 30.",
                defaultValue: 30, range: "1..3600"
        input name: "reportMaxInterval", type: "number", title: "Maximum reporting interval (s)",
                description: "Every quantity is reported at least this often, even without a change. 10 - 43200, default 1800.",
                defaultValue: 1800, range: "10..43200"
        input name: "powerChange", type: "number", title: "Power change to report (W)",
                description: "Applies to total and per-phase active power, and to reactive and apparent power. Default 50.",
                defaultValue: 50, range: "1..30000"
        input name: "amperageChange", type: "decimal", title: "Current change to report (A)",
                description: "Per phase. Default 0.5.", defaultValue: 0.5, range: "0.01..100"
        input name: "voltageChange", type: "decimal", title: "Voltage change to report (V)",
                description: "Per phase. Default 5.", defaultValue: 5, range: "0.1..50"
        input name: "energyChange", type: "decimal", title: "Energy change to report (kWh)",
                description: "Applies to total and exported energy. Default 0.25.", defaultValue: 0.25, range: "0.01..100"
        input name: "reportExtended", type: "bool", title: "Report extended measurements",
                description: "Frequency, per-phase power factor and energy, exported energy, total reactive and apparent power.",
                defaultValue: false
        input name: "powerScaling", type: "enum", title: "Power scaling",
                description: "The firmware advertises a power divisor of 1000 but sends watts. Auto compares a phase's power with its voltage × current once and remembers the result.",
                options: ["auto": "Auto-detect (default)", "watts": "Raw value is watts", "divisor": "Apply the advertised multiplier/divisor"],
                defaultValue: "auto"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------------------------------

void installed() {
    logger("info", "installed() driver v${VERSION}")
    runIn(3600, "logsOff")
}

void updated() {
    logger("info", "updated() driver v${VERSION}")
    unschedule("logsOff")
    if (settings.logEnable != false) runIn(3600, "logsOff")
    cleanupState()
    // Preferences drive the reporting configuration, so push it again.
    sendZigbeeCommands(reportingCommands())
}

void logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void cleanupState() {
    // Interview results used to be kept in state; they are log-only now.
    ["discovered", "commands", "reporting", "basic"].each { state.remove(it) }
}

// ---------------------------------------------------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------------------------------------------------

void configure() {
    logger("info", "configure(): binding Metering and Electrical Measurement, reading scaling, configuring reporting")
    cleanupState()
    if (state.scaling == null) state.scaling = [:] + DEFAULT_SCALING
    List<String> cmds = []
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0702 {${device.zigbeeId}} {}"
    cmds += "delay 300"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0B04 {${device.zigbeeId}} {}"
    cmds += "delay 300"
    cmds += scalingReadCommands()
    cmds += reportingCommands()
    cmds += measurementReadCommands()
    sendZigbeeCommands(cmds)
}

void refresh() {
    logger("info", "refresh(): reading all measurements")
    sendZigbeeCommands(measurementReadCommands())
}

void identify() {
    logger("info", "identify(): asking the device to identify itself for 10 s")
    sendZigbeeCommands([zigbee.command(CL_IDENTIFY, 0x00, "0A00")].flatten() as List<String>)
}

void interview() {
    log.warn "${device.displayName} interview started: results are logged at info level over the next ~20 seconds"
    cleanupState()
    List<String> cmds = []
    // Basic cluster, stored as device data.
    cmds += zigbee.readAttribute(CL_BASIC, [0x0000, 0x0001, 0x0002, 0x0003, 0x0004, 0x0005], [:], 300)
    cmds += zigbee.readAttribute(CL_BASIC, [0x0006, 0x0007, 0x000A, 0x4000, 0xFFFD], [:], 300)
    // Diagnostics: link quality of the last message from us.
    cmds += zigbee.readAttribute(CL_DIAGNOSTICS, [0x011C, 0x011D], [:], 300)
    // Every server cluster: discover attributes (standard and manufacturer specific) and commands received.
    [CL_BASIC, CL_IDENTIFY, CL_METERING, CL_ELECTRICAL, CL_DIAGNOSTICS].each { int cluster ->
        cmds += discoverAttributesCommand(cluster, 0x0000, false)
        cmds += "delay 400"
        // The extended form also tells whether each attribute is readable, writable or reportable.
        cmds += discoverAttributesExtendedCommand(cluster, 0x0000)
        cmds += "delay 400"
        cmds += discoverAttributesCommand(cluster, 0x0000, true)
        cmds += "delay 400"
        cmds += discoverCommandsCommand(cluster, false)
        cmds += "delay 400"
        cmds += discoverCommandsCommand(cluster, true)
        cmds += "delay 400"
    }
    // Reporting configuration as the device holds it.
    cmds += readReportingConfigurationCommands()
    // Anything the Metering cluster has to say about itself.
    cmds += zigbee.readAttribute(CL_METERING, [0x0200, 0x0300, 0x0303, 0x0306, 0x0400], [:], 300)
    cmds += zigbee.readAttribute(CL_ELECTRICAL, [0x0000, 0xFFFD], [:], 300)
    // Alarm mask and overload thresholds; the extended measurements to see that they carry values.
    cmds += zigbee.readAttribute(CL_ELECTRICAL, [0x0800, 0x0801, 0x0802, 0x0803], [:], 300)
    cmds += zigbee.readAttribute(CL_ELECTRICAL, [0x0300, 0x0305, 0x0306, 0x0510, 0x050E, 0x050F], [:], 300)
    cmds += zigbee.readAttribute(CL_METERING, [0x0001], [:], 300)
    cmds += zigbee.readAttribute(CL_BASIC, [0x0010], [:], 300)
    sendZigbeeCommands(cmds)
}

// ---------------------------------------------------------------------------------------------------------------------
// Command builders
// ---------------------------------------------------------------------------------------------------------------------

List<String> scalingReadCommands() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(CL_ELECTRICAL, [0x0400, 0x0401, 0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605], [:], 300)
    cmds += zigbee.readAttribute(CL_METERING, [0x0300, 0x0301, 0x0302], [:], 300)
    return cmds
}

List<String> measurementReadCommands() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(CL_ELECTRICAL, [0x0304, 0x0505, 0x0508, 0x050B, 0x0905, 0x0908, 0x090B], [:], 300)
    cmds += zigbee.readAttribute(CL_ELECTRICAL, [0x0A05, 0x0A08, 0x0A0B, 0x0300], [:], 300)
    cmds += zigbee.readAttribute(CL_METERING, [0x0000], [:], 300)
    if (settings.reportExtended == true) {
        cmds += zigbee.readAttribute(CL_ELECTRICAL, [0x0305, 0x0306, 0x0510, 0x0910, 0x0A10], [:], 300)
        cmds += zigbee.readAttribute(CL_METERING, [0x0001], [:], 300)
    }
    return cmds
}

/**
 * Configure Reporting for everything the driver emits. One multi-record command per group so the device's response
 * names the attributes it rejected (a lone SUCCESS means the whole group was accepted).
 */
List<String> reportingCommands() {
    int minI = prefInt("reportMinInterval", 30)
    int maxI = prefInt("reportMaxInterval", 1800)
    if (maxI < minI) maxI = minI
    boolean extended = settings.reportExtended == true

    long powerRaw = rawChange("power", prefDecimal("powerChange", 50))
    long currentRaw = rawChange("current", prefDecimal("amperageChange", 0.5))
    long voltageRaw = rawChange("voltage", prefDecimal("voltageChange", 5))
    long energyRaw = rawChange("energy", prefDecimal("energyChange", 0.25))
    long frequencyRaw = rawChange("frequency", 0.1)

    List<String> cmds = []
    // Group 1: total power and the three voltages.
    cmds += configureReportingCommand(CL_ELECTRICAL, [
            record(0x0304, T_INT32, minI, maxI, powerRaw),
            record(0x0505, T_UINT16, minI, maxI, voltageRaw),
            record(0x0905, T_UINT16, minI, maxI, voltageRaw),
            record(0x0A05, T_UINT16, minI, maxI, voltageRaw),
    ])
    cmds += "delay 500"
    // Group 2: per-phase current and power.
    cmds += configureReportingCommand(CL_ELECTRICAL, [
            record(0x0508, T_UINT16, minI, maxI, currentRaw),
            record(0x0908, T_UINT16, minI, maxI, currentRaw),
            record(0x0A08, T_UINT16, minI, maxI, currentRaw),
            record(0x050B, T_INT16, minI, maxI, clampInt16(powerRaw)),
            record(0x090B, T_INT16, minI, maxI, clampInt16(powerRaw)),
            record(0x0A0B, T_INT16, minI, maxI, clampInt16(powerRaw)),
    ])
    cmds += "delay 500"
    // Group 3: extended electrical measurements. Disabled by asking for no reports (max interval 0xFFFF).
    int eMin = extended ? minI : 0xFFFF
    int eMax = extended ? maxI : 0xFFFF
    cmds += configureReportingCommand(CL_ELECTRICAL, [
            record(0x0300, T_UINT16, eMin, eMax, frequencyRaw),
            record(0x0305, T_INT32, eMin, eMax, powerRaw),
            record(0x0306, T_UINT32, eMin, eMax, powerRaw),
            record(0x0510, T_INT8, eMin, eMax, 1),
            record(0x0910, T_INT8, eMin, eMax, 1),
            record(0x0A10, T_INT8, eMin, eMax, 1),
    ])
    cmds += "delay 500"
    // Group 4: energy. Total imported always; exported only when extended.
    cmds += configureReportingCommand(CL_METERING, [
            record(0x0000, T_UINT48, minI, maxI, energyRaw),
            record(0x0001, T_UINT48, eMin, eMax, energyRaw),
    ])
    cmds += "delay 500"
    return cmds
}

List<String> readReportingConfigurationCommands() {
    List<String> cmds = []
    cmds += readReportingCommand(CL_ELECTRICAL, [0x0304, 0x0505, 0x0905, 0x0A05, 0x0508, 0x0908, 0x0A08, 0x050B, 0x090B, 0x0A0B])
    cmds += "delay 500"
    cmds += readReportingCommand(CL_ELECTRICAL, [0x0300, 0x0305, 0x0306, 0x0510, 0x0910, 0x0A10])
    cmds += "delay 500"
    cmds += readReportingCommand(CL_METERING, [0x0000, 0x0001])
    cmds += "delay 500"
    return cmds
}

/** One Configure Reporting record: direction 0, attribute id, data type, min, max, reportable change (analog types only). */
String record(int attrId, int type, int minI, int maxI, long change) {
    String r = "00" + hexLE(attrId, 2) + hexByte(type) + hexLE(minI, 2) + hexLE(maxI, 2)
    if (isAnalog(type)) r += hexLE(change, typeSize(type))
    return r
}

String configureReportingCommand(int cluster, List<String> records) {
    return zclGlobal(cluster, 0x06, records.join(""), false)
}

String readReportingCommand(int cluster, List<Integer> attrIds) {
    return zclGlobal(cluster, 0x08, attrIds.collect { "00" + hexLE(it, 2) }.join(""), false)
}

String discoverAttributesCommand(int cluster, int startAttr, boolean mfrSpecific) {
    return zclGlobal(cluster, 0x0C, hexLE(startAttr, 2) + "40", mfrSpecific)
}

String discoverAttributesExtendedCommand(int cluster, int startAttr) {
    return zclGlobal(cluster, 0x15, hexLE(startAttr, 2) + "40", false)
}

String discoverCommandsCommand(int cluster, boolean mfrSpecific) {
    return zclGlobal(cluster, 0x11, "00FF", mfrSpecific)
}

/** A ZCL global (profile-wide) command frame: client to server, default response disabled. */
String zclGlobal(int cluster, int command, String payload, boolean mfrSpecific) {
    String frameControl = mfrSpecific ? "14" : "10"
    String mfr = mfrSpecific ? hexLE(MFR_CODE, 2) : ""
    String seq = hexByte((int) (now() % 256))
    return "he raw 0x${device.deviceNetworkId} 0x01 0x${device.endpointId} 0x${hex16(cluster)} {${frameControl}${mfr}${seq}${hexByte(command)}${payload}}"
}

void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    logger("debug", "sending ${cmds.size()} commands: ${cmds}")
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

// ---------------------------------------------------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------------------------------------------------

void parse(String description) {
    logger("debug", "parse: ${description}")
    Map m
    try {
        m = zigbee.parseDescriptionAsMap(description)
    } catch (Exception e) {
        logger("warn", "could not parse '${description}': ${e}")
        return
    }
    if (m == null) return

    // A device that has never been configured by this driver gets one automatic configure so the reports start flowing.
    if (state.scaling == null) {
        state.scaling = [:] + DEFAULT_SCALING
        log.warn "${device.displayName} first message with this driver, scheduling configure()"
        runIn(2, "configure")
    }

    if (description.startsWith("read attr")) {
        List<Map> records = []
        if (m.attrInt != null) records << [attr: m.attrInt as int, type: hexInt(m.encoding), hex: m.value, text: true]
        m.additionalAttrs?.each {
            // An unsupported attribute in a multi-attribute read response arrives here without encoding or value.
            if (it.encoding == null || it.value == null) records << [attr: it.attrInt as int, status: 0x86]
            else records << [attr: it.attrInt as int, type: hexInt(it.encoding), hex: it.value, text: true]
        }
        handleAttributes(m.clusterInt as int, records, false)
    } else if (description.startsWith("catchall")) {
        handleCatchall(m)
    } else {
        logger("debug", "unhandled description: ${description}")
    }
}

void handleCatchall(Map m) {
    int cluster = m.clusterInt as int
    String command = m.command
    List<String> data = (m.data ?: []) as List<String>
    boolean mfr = m.isManufacturerSpecific?.toString() in ["true", "01"]
    if (m.profileId == "0000") {
        logger("debug", "ZDO message cluster 0x${m.clusterId} data ${data}")
        return
    }
    if (m.isClusterSpecific?.toString() in ["true", "01"]) {
        logger("debug", "cluster-specific command 0x${command} on cluster 0x${m.clusterId}: ${data}")
        return
    }
    switch (command) {
        case "01": // Read Attributes Response (Hubitat parses most of these itself; this handles the ones with failures)
            handleAttributes(cluster, parseAttributeRecords(data, true), mfr)
            break
        case "0A": // Report Attributes
            handleAttributes(cluster, parseAttributeRecords(data, false), mfr)
            break
        case "04": // Write Attributes Response
            logger("info", "write attributes response on cluster 0x${m.clusterId}: ${data}")
            break
        case "07": // Configure Reporting Response
            handleConfigureReportingResponse(cluster, data)
            break
        case "09": // Read Reporting Configuration Response
            handleReadReportingResponse(cluster, data)
            break
        case "0B": // Default Response
            handleDefaultResponse(cluster, data, mfr)
            break
        case "0D": // Discover Attributes Response
            handleDiscoverAttributesResponse(cluster, data, mfr, false)
            break
        case "16": // Discover Attributes Extended Response
            handleDiscoverAttributesResponse(cluster, data, mfr, true)
            break
        case "12": // Discover Commands Received Response
            handleDiscoverCommandsResponse(cluster, data, mfr)
            break
        default:
            logger("debug", "unhandled global command 0x${command} on cluster 0x${m.clusterId}: ${data}")
    }
}

/**
 * Turns attribute records into events. Records: [attr, type, hex, status?, text?]. A record with text:true carries the
 * value the way Hubitat parsed it (already big-endian, strings decoded); others carry raw big-endian hex from the payload.
 */
void handleAttributes(int cluster, List<Map> records, boolean mfr) {
    Map<String, BigDecimal> phaseValues = [:]
    boolean phaseChanged = false
    records.each { Map r ->
        String name = attrName(cluster, r.attr as int)
        if (r.status != null && r.status != 0) {
            logger("info", "attribute ${name} on cluster 0x${hex16(cluster)} read failed with status 0x${hexByte(r.status as int)} (${zclStatus(r.status as int)})")
            return
        }
        if (r.hex == null) return
        if (mfr) {
            logger("info", "manufacturer-specific attribute 0x${hex16(r.attr as int)} on cluster 0x${hex16(cluster)}: type 0x${hexByte(r.type as int)} value ${r.hex}")
            return
        }
        Integer type = r.type as Integer
        switch (cluster) {
            case CL_ELECTRICAL:
                if (SCALING_ATTRS[cluster].containsKey(r.attr)) {
                    storeScaling(SCALING_ATTRS[cluster][r.attr as int], decode(r.hex, type))
                } else if (ELECTRICAL_ATTRS.containsKey(r.attr)) {
                    Map spec = ELECTRICAL_ATTRS[r.attr as int]
                    long raw = decode(r.hex, type)
                    if (spec.kind == "power") detectPowerScaling(spec.name as String, raw)
                    BigDecimal value = scale(spec.kind as String, raw)
                    emit(spec.name as String, value, spec.unit as String)
                    if ((spec.name as String) ==~ /(voltage|amperage)L[123]/) {
                        phaseValues[spec.name as String] = value
                        phaseChanged = true
                    }
                } else if (r.attr == 0x0000) {
                    logger("info", "MeasurementType is 0x${r.hex}")
                } else {
                    logger("info", "${name} = ${decode(r.hex, type)} (raw ${r.hex}, type 0x${hexByte(type)})")
                }
                break
            case CL_METERING:
                if (SCALING_ATTRS[cluster].containsKey(r.attr)) {
                    storeScaling(SCALING_ATTRS[cluster][r.attr as int], decode(r.hex, type))
                } else if (METERING_ATTRS.containsKey(r.attr)) {
                    Map spec = METERING_ATTRS[r.attr as int]
                    emit(spec.name as String, scale(spec.kind as String, decode(r.hex, type)), spec.unit as String)
                } else if (r.attr == 0x0300) {
                    long unit = decode(r.hex, type)
                    if (unit != 0) log.warn "${device.displayName} UnitOfMeasure is ${unit}, expected 0 (kWh); energy values are in the device's unit"
                    else logger("info", "UnitOfMeasure is kWh")
                } else {
                    logger("info", "${name} = ${r.hex} (type 0x${hexByte(type)})")
                }
                break
            case CL_BASIC:
                handleBasicAttribute(r.attr as int, type, r.hex as String, r.text == true)
                break
            case CL_DIAGNOSTICS:
                logger("info", "${name} = ${decode(r.hex, type)}")
                break
            default:
                logger("debug", "cluster 0x${hex16(cluster)} ${name} = ${r.hex}")
        }
    }
    if (phaseChanged) emitTotals(phaseValues)
}

void handleBasicAttribute(int attr, int type, String hex, boolean text) {
    String value = (type in [0x41, 0x42, 0x43, 0x44]) ? (text ? hex : hexToAscii(hex)) : decode(hex, type).toString()
    String name = attrName(CL_BASIC, attr)
    logger("info", "Basic ${name} = ${value}")
    switch (attr) {
        case 0x0001: updateDataValue("application", hexByte(decode(hex, type) as int)); break
        case 0x0003: updateDataValue("hwVersion", value); break
        case 0x0004: updateDataValue("manufacturer", value); break
        case 0x0005: updateDataValue("model", value); break
        case 0x0006: updateDataValue("dateCode", value); break
        case 0x4000: updateDataValue("softwareBuild", value); break
    }
}

/** Average voltage and summed current over the phases, for the standard capability attributes. */
void emitTotals(Map<String, BigDecimal> fresh) {
    List<BigDecimal> volts = []
    List<BigDecimal> amps = []
    ["L1", "L2", "L3"].each { String p ->
        BigDecimal v = fresh["voltage${p}"] ?: (device.currentValue("voltage${p}", true) as BigDecimal)
        BigDecimal a = fresh["amperage${p}"] ?: (device.currentValue("amperage${p}", true) as BigDecimal)
        if (v != null) volts << v
        if (a != null) amps << a
    }
    if (volts) emit("voltage", (volts.sum() as BigDecimal).divide(volts.size() as BigDecimal, 1, RoundingMode.HALF_UP), "V")
    if (amps) emit("amperage", (amps.sum() as BigDecimal).setScale(2, RoundingMode.HALF_UP), "A")
}

void handleConfigureReportingResponse(int cluster, List<String> data) {
    if (data.size() == 1 && data[0] == "00") {
        logger("info", "reporting configuration accepted for all requested attributes of cluster 0x${hex16(cluster)}")
        return
    }
    if (data.size() == 1) {
        log.warn "${device.displayName} reporting configuration on cluster 0x${hex16(cluster)} rejected as a whole: status 0x${data[0]} (${zclStatus(hexInt(data[0]))})"
        return
    }
    for (int i = 0; i + 3 < data.size(); i += 4) {
        int status = hexInt(data[i])
        int attr = hexInt(data[i + 3] + data[i + 2])
        String msg = "reporting of ${attrName(cluster, attr)} on cluster 0x${hex16(cluster)}: status 0x${data[i]} (${zclStatus(status)})"
        if (status == 0) logger("info", msg) else log.warn "${device.displayName} ${msg}"
    }
}

void handleReadReportingResponse(int cluster, List<String> data) {
    int i = 0
    while (i + 3 < data.size()) {
        int status = hexInt(data[i])
        int direction = hexInt(data[i + 1])
        int attr = hexInt(data[i + 3] + data[i + 2])
        i += 4
        String name = attrName(cluster, attr)
        if (status != 0) {
            log.info "${device.displayName} reporting configuration of ${name}: status 0x${hexByte(status)} ${zclStatus(status)}"
            continue
        }
        if (direction != 0) {
            // Direction 1 records carry only a timeout; not expected from this device.
            if (i + 1 < data.size()) i += 2
            continue
        }
        if (i + 4 >= data.size()) break
        int type = hexInt(data[i])
        int minI = hexInt(data[i + 2] + data[i + 1])
        int maxI = hexInt(data[i + 4] + data[i + 3])
        i += 5
        String change = ""
        if (isAnalog(type)) {
            int size = typeSize(type)
            if (i + size > data.size()) break
            change = " change ${decode(data.subList(i, i + size).reverse().join(''), type)}"
            i += size
        }
        String desc = (maxI == 0xFFFF) ? "off" : "min ${minI}s max ${maxI}s${change}"
        log.info "${device.displayName} reporting configuration of ${name}: ${desc}"
    }
}

void handleDefaultResponse(int cluster, List<String> data, boolean mfr) {
    if (data.size() < 2) return
    int cmd = hexInt(data[0])
    int status = hexInt(data[1])
    String msg = "default response on cluster 0x${hex16(cluster)}${mfr ? ' (manufacturer specific)' : ''} to command 0x${data[0]}: 0x${data[1]} ${zclStatus(status)}"
    if (status == 0) logger("debug", msg) else log.info "${device.displayName} ${msg}"
}

void handleDiscoverAttributesResponse(int cluster, List<String> data, boolean mfr, boolean extended) {
    if (data.isEmpty()) return
    boolean complete = data[0] != "00"
    int stride = extended ? 4 : 3
    List<String> found = []
    int last = -1
    for (int i = 1; i + stride - 1 < data.size(); i += stride) {
        int attr = hexInt(data[i + 1] + data[i])
        int type = hexInt(data[i + 2])
        String access = extended ? accessFlags(hexInt(data[i + 3])) : ""
        found << "${hex16(attr)}:${hexByte(type)}${access}".toString()
        last = attr
    }
    List<String> names = found.collect { String f -> attrName(cluster, hexInt(f.substring(0, 4))) + " (" + f + ")" }
    log.info "${device.displayName} cluster 0x${hex16(cluster)}${mfr ? ' manufacturer-specific' : ''} attributes${extended ? ' with access' : ''}${complete ? '' : ' (more follow)'}: ${names.join(', ') ?: 'none'}"
    if (!complete && last >= 0 && last < 0xFFFF) {
        sendZigbeeCommands([discoverAttributesCommand(cluster, last + 1, mfr)])
    }
}

void handleDiscoverCommandsResponse(int cluster, List<String> data, boolean mfr) {
    if (data.isEmpty()) return
    List<String> ids = data.size() > 1 ? data.subList(1, data.size()) : []
    log.info "${device.displayName} cluster 0x${hex16(cluster)}${mfr ? ' manufacturer-specific' : ''} commands received: ${ids ? ids.collect { '0x' + it }.join(', ') : 'none'}"
}

// ---------------------------------------------------------------------------------------------------------------------
// Scaling
// ---------------------------------------------------------------------------------------------------------------------

void storeScaling(String key, long value) {
    Map scaling = ((state.scaling ?: [:]) + [:]) as Map
    if (value <= 0) {
        logger("warn", "ignoring ${key} = ${value}")
        return
    }
    if (scaling[key] != value) {
        logger("info", "${key} = ${value}")
        scaling[key] = value
        state.scaling = scaling
    }
}

BigDecimal factor(String kind) {
    Map scaling = (state.scaling ?: DEFAULT_SCALING) as Map
    BigDecimal mult = (scaling["${kind}Multiplier"] ?: DEFAULT_SCALING["${kind}Multiplier"] ?: 1) as BigDecimal
    BigDecimal div = (scaling["${kind}Divisor"] ?: DEFAULT_SCALING["${kind}Divisor"] ?: 1) as BigDecimal
    return mult.divide(div, 10, RoundingMode.HALF_UP)
}

/** Whether power attributes are taken as watts (true) or scaled by the advertised multiplier/divisor (false). */
boolean powerIsWatts() {
    String mode = settings.powerScaling ?: "auto"
    if (mode == "watts") return true
    if (mode == "divisor") return false
    return state.powerRawUnit != "divisor"
}

BigDecimal scale(String kind, long raw) {
    BigDecimal f = (kind == "none") ? 1 : (kind == "power" && powerIsWatts()) ? 1 : factor(kind)
    return (new BigDecimal(raw) * f).setScale(DECIMALS[kind] ?: 0, RoundingMode.HALF_UP)
}

/** The reportable change in device units for a change expressed in engineering units. */
long rawChange(String kind, BigDecimal change) {
    BigDecimal f = (kind == "power" && powerIsWatts()) ? 1 : factor(kind)
    long raw = change.divide(f, 0, RoundingMode.HALF_UP).longValue()
    return Math.max(raw, 1L)
}

long clampInt16(long v) { Math.min(v, 32767L) }

/**
 * Decides once whether a phase's active power is raw watts or needs the advertised divisor, by comparing it with that
 * phase's voltage × current. A power factor above 1.2 is impossible, a power factor below 0.05 is implausible for a
 * loaded phase, so either way one of the two candidates is ruled out.
 */
void detectPowerScaling(String name, long raw) {
    if ((settings.powerScaling ?: "auto") != "auto" || state.powerRawUnit != null) return
    def matcher = name =~ /^powerL([123])$/
    if (!matcher.matches() || raw == 0) return
    String phase = "L" + matcher.group(1)
    BigDecimal v = device.currentValue("voltage${phase}", true) as BigDecimal
    BigDecimal a = device.currentValue("amperage${phase}", true) as BigDecimal
    if (v == null || a == null) return
    BigDecimal va = v * a
    if (va < 50) return
    BigDecimal asWatts = new BigDecimal(Math.abs(raw))
    BigDecimal ratio = asWatts.divide(va, 4, RoundingMode.HALF_UP)
    BigDecimal advertised = factor("power")
    if (ratio > 1.2) {
        state.powerRawUnit = "divisor"
        log.info "${device.displayName} power attributes need the advertised scaling (${advertised}): raw ${raw} vs ${phase} V×I = ${va.setScale(0, RoundingMode.HALF_UP)} VA"
    } else if (ratio > 0.05) {
        state.powerRawUnit = "watts"
        log.info "${device.displayName} power attributes are raw watts, ignoring the advertised divisor: raw ${raw} W vs ${phase} V×I = ${va.setScale(0, RoundingMode.HALF_UP)} VA"
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Payload decoding helpers
// ---------------------------------------------------------------------------------------------------------------------

/** Parses the records of a Read Attributes Response (withStatus) or a Report Attributes command. Values come out as big-endian hex. */
List<Map> parseAttributeRecords(List<String> data, boolean withStatus) {
    List<Map> out = []
    int i = 0
    while (i + 2 <= data.size()) {
        int attr = hexInt(data[i + 1] + data[i])
        i += 2
        if (withStatus) {
            if (i >= data.size()) break
            int status = hexInt(data[i])
            i++
            if (status != 0) {
                out << [attr: attr, status: status]
                continue
            }
        }
        if (i >= data.size()) break
        int type = hexInt(data[i])
        i++
        int size = typeSize(type)
        String hex
        if (size > 0) {
            if (i + size > data.size()) break
            hex = data.subList(i, i + size).reverse().join("")
            i += size
        } else if (type in [0x41, 0x42]) {
            int len = hexInt(data[i])
            i++
            if (len == 0xFF) len = 0
            if (i + len > data.size()) break
            hex = data.subList(i, i + len).join("")
            i += len
        } else if (type in [0x43, 0x44]) {
            if (i + 2 > data.size()) break
            int len = hexInt(data[i + 1] + data[i])
            i += 2
            if (len == 0xFFFF) len = 0
            if (i + len > data.size()) break
            hex = data.subList(i, i + len).join("")
            i += len
        } else {
            logger("debug", "cannot size ZCL type 0x${hexByte(type)}, stopping at attribute 0x${hex16(attr)}")
            break
        }
        out << [attr: attr, status: 0, type: type, hex: hex]
    }
    return out
}

/** Big-endian hex to a number, sign-extended for the signed ZCL types. */
long decode(String hex, int type) {
    if (!hex) return 0L
    long v = Long.parseUnsignedLong(hex, 16)
    int bits = hex.length() * 4
    if (isSigned(type) && bits < 64 && (v & (1L << (bits - 1))) != 0) v -= (1L << bits)
    return v
}

int typeSize(int t) {
    switch (t) {
        case 0x08: case 0x10: case 0x18: case 0x20: case 0x28: case 0x30: return 1
        case 0x09: case 0x19: case 0x21: case 0x29: case 0x31: case 0x38: return 2
        case 0x0A: case 0x1A: case 0x22: case 0x2A: return 3
        case 0x0B: case 0x1B: case 0x23: case 0x2B: case 0x39: case 0xE0: case 0xE1: case 0xE2: return 4
        case 0x0C: case 0x1C: case 0x24: case 0x2C: return 5
        case 0x0D: case 0x1D: case 0x25: case 0x2D: return 6
        case 0x0E: case 0x1E: case 0x26: case 0x2E: return 7
        case 0x0F: case 0x1F: case 0x27: case 0x2F: case 0x3A: case 0xF0: return 8
        default: return -1
    }
}

boolean isAnalog(int t) { (t >= 0x20 && t <= 0x2F) || (t >= 0x38 && t <= 0x3A) || (t >= 0xE0 && t <= 0xE2) }

boolean isSigned(int t) { t >= 0x28 && t <= 0x2F }

String accessFlags(int flags) {
    String s = ""
    if (flags & 0x01) s += "R"
    if (flags & 0x02) s += "W"
    if (flags & 0x04) s += "P"
    return s ? ":" + s : ""
}

String attrName(int cluster, int attr) {
    String n = ATTR_NAMES[cluster]?.get(attr)
    return n ? "${n} (0x${hex16(attr)})" : "0x${hex16(attr)}"
}

String zclStatus(int status) {
    switch (status) {
        case 0x00: return "SUCCESS"
        case 0x01: return "FAILURE"
        case 0x7E: return "NOT_AUTHORIZED"
        case 0x80: return "MALFORMED_COMMAND"
        case 0x81: return "UNSUP_CLUSTER_COMMAND"
        case 0x82: return "UNSUP_GENERAL_COMMAND"
        case 0x83: return "UNSUP_MANUF_CLUSTER_COMMAND"
        case 0x84: return "UNSUP_MANUF_GENERAL_COMMAND"
        case 0x85: return "INVALID_FIELD"
        case 0x86: return "UNSUPPORTED_ATTRIBUTE"
        case 0x87: return "INVALID_VALUE"
        case 0x88: return "READ_ONLY"
        case 0x89: return "INSUFFICIENT_SPACE"
        case 0x8A: return "DUPLICATE_EXISTS"
        case 0x8B: return "NOT_FOUND"
        case 0x8C: return "UNREPORTABLE_ATTRIBUTE"
        case 0x8D: return "INVALID_DATA_TYPE"
        case 0x8E: return "INVALID_SELECTOR"
        case 0xC3: return "UNSUPPORTED_CLUSTER"
        default: return "status 0x${hexByte(status)}"
    }
}

int hexInt(String hex) { Integer.parseInt(hex, 16) }

String hexByte(int v) { Integer.toHexString(v & 0xFF).padLeft(2, "0").toUpperCase() }

String hex16(int v) { Integer.toHexString(v & 0xFFFF).padLeft(4, "0").toUpperCase() }

/** Little-endian hex of the low `bytes` bytes of v. */
String hexLE(long v, int bytes) {
    String h = Long.toHexString(v).padLeft(bytes * 2, "0").toUpperCase()
    h = h.substring(h.length() - bytes * 2)
    List<String> pairs = []
    for (int i = 0; i < h.length(); i += 2) pairs << h.substring(i, i + 2)
    return pairs.reverse().join("")
}

String hexToAscii(String hex) {
    if (!hex) return ""
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i + 1 < hex.length(); i += 2) sb.append((char) Integer.parseInt(hex.substring(i, i + 2), 16))
    return sb.toString()
}

// ---------------------------------------------------------------------------------------------------------------------
// Events, preferences, logging
// ---------------------------------------------------------------------------------------------------------------------

/** Sends an event only when the value actually changed, so rules are not woken by identical reports. */
void emit(String name, BigDecimal value, String unit) {
    def current = device.currentValue(name, true)
    if (current != null) {
        try {
            if ((current as BigDecimal).compareTo(value) == 0) return
        } catch (Exception ignored) {
        }
    }
    String text = "${device.displayName} ${name} is ${value} ${unit}"
    sendEvent(name: name, value: value, unit: unit, descriptionText: text)
    if (settings.txtEnable != false) log.info text
}

int prefInt(String name, int dflt) {
    def v = settings[name]
    return (v == null) ? dflt : (v as BigDecimal).intValue()
}

BigDecimal prefDecimal(String name, BigDecimal dflt) {
    def v = settings[name]
    return (v == null) ? dflt : (v as BigDecimal)
}

void logger(String level, String msg) {
    switch (level) {
        case "debug":
            if (settings.logEnable != false) log.debug "${device.displayName} ${msg}"
            break
        case "info":
            if (settings.txtEnable != false) log.info "${device.displayName} ${msg}"
            break
        case "warn":
            log.warn "${device.displayName} ${msg}"
            break
        default:
            log.error "${device.displayName} ${msg}"
    }
}
