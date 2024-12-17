/**
 *  Nice IBT4ZWAVE Z-Wave 700 Driver for Hubitat
 *  Date: 12.12.2024
 *	Author: Bogusław Wójcik
 *
 *	CHANGELOG:
 *  - V0.1.1 - 17.12.2024: Improved fingerprint and added state update based on supervision report to improve speed and reliability.
 *  - V0.1.0 - 12.12.2024: Initial working version.
 *
 *  DESCRIPTION:
 *  This is a custom driver for Nice IBT4ZWAVE module pluggable into Nice gate and garage door controllers. To control
 *  the gate the module exposes a multi-level switch command class and can be used with an inbuilt Generic Z-Wave Plus
 *  Dimmer driver - which can prove basic functionality but does not allow the user to see the current state of the
 *  gate properly nor export the device properly as a gate to HomeKit. On the other hand, this driver exposes the
 *  device as a garage door and internally maps its multi-level switch values to one of the following states: open,
 *  closed, opening, closing, stopped, unknown.
 *
 *  NOTES:
 *  - The driver exposes a "stopped" state which can occur if gate or garage doors hit an obstacle or are stopped by a pilot.
 *  - This state is not listed as supported by Hubitat Garage Door Control capability and the user can disable this behavior in preferences.
 *  - The driver has been tested on Nice IBT4ZWAVE from EU distribution module with firmware version 7.0 and securely paired with Hubitat.
 *  - The driver incorporates also a contact sensor capability only to allow the user to export the device to HomeKit as a garage door.
 *    The requirement to have a contact capability might be an error of the HomeKit integration and may be removed in the future.
 *
 *  Copyright 2024 Bogusław Wójcik
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

@Field String VERSION = "0.1.1"

metadata {
  definition (name: "Nice IBT4ZWAVE", namespace: "boguslaw-wojcik", author: "Bogusław Wójcik", importUrl: "https://github.com/boguslaw-wojcik/hubitat/blob/main/drivers/nice/nice_ibt4zwave_gate_actuator.groovy") {
    capability "Actuator"
    capability "DoorControl"
    capability "GarageDoorControl"
    capability "ContactSensor"
    capability "Refresh"
    capability "Configuration"

    fingerprint mfr:"1089", prod:"9216", deviceId:"4096", inClusters:"0x00,0x00", controllerType: "ZWV", deviceJoinName: "Nice IBT4ZWAVE"
  }

  preferences {
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    input name: "reportStopped", type: "bool", title: "Report stopped state instead of unknown", defaultValue: true
  }
}

@Field static Map CMD_CLASS_VERS = [
  0x5E: 2, // COMMAND_CLASS_ZWAVEPLUS_INFO_V2
  0x26: 4, // COMMAND_CLASS_MULTILEVEL_SWITCH_V4
  0x85: 2, // COMMAND_CLASS_ASSOCIATION_V2
  0x8E: 3, // COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION_V3
  0x59: 3, // COMMAND_CLASS_ASSOCIATION_GRP_INFO_V3
  0x5A: 1, // COMMAND_CLASS_DEVICE_RESET_LOCALLY_V1
  0x7A: 5, // COMMAND_CLASS_FIRMWARE_UPDATE_MD_V5
  0x87: 3, // COMMAND_CLASS_INDICATOR_V3,
  0x72: 2, // COMMAND_CLASS_MANUFACTURER_SPECIFIC_V2
  0x73: 1, // COMMAND_CLASS_POWER_LEVEL_V1
  0x98: 1, // COMMAND_CLASS_SECURITY_V1
  0x9F: 1, // COMMAND_CLASS_SECURITY_2_V1
  0x6C: 1, // COMMAND_CLASS_SUPERVISION_V1
  0x55: 2, // COMMAND_CLASS_TRANSPORT_SERVICE_V2
  0x86: 3, // COMMAND_CLASS_VERSION_V3
  0x40: 8, // COMMAND_CLASS_NOTIFICATION_V8
  0x22: 1, // COMMAND_CLASS_APPLICATION_STATUS_V1
  0x75: 2, // COMMAND_CLASS_PROTECTION_V2
  0x70: 4, // COMMAND_CLASS_CONFIGURATION_V4
  0x20: 2  // COMMAND_CLASS_BASIC_V2
]

// This is a helper function to get the state of the barrier based on current value and target value as reported by multi level switch command class.
String getBarrierState(Short value, Short targetValue) {
   switch (value) {
      case 0:
        return "closed"
      case 99:
        return "open"
      case 254:
          switch (targetValue) {
            case 0:
              return "closing"
            case 99:
              return "opening"
            case 254:
              return reportStopped ? "stopped" : "unknown"
          }
      default:
        return "unknown"
   }
}

// This is a helper function to get the contact state of the barrier based on current value and target value as reported by multi level switch command class.
String getContactState(Short value, Short targetValue) {
   switch (value) {
      case 0:
        return "closed"
      case 99:
        return "open"
      case 254:
          switch (targetValue) {
            case 0:
              return "open"
            case 99:
              return "open"
            case 254:
              return "open"
          }
      default:
        return "unknown"
   }
}

// This is a helper function to report the state of the barrier and contact sensor.
void reportState(String barrierState, String contactState) {
  sendEventWrapper(name:"door", value: barrierState, descriptionText:"Barrier is ${barrierState}")
  sendEventWrapper(name:"contact", value: contactState, descriptionText:"Contact is ${contactState}")
}

void installed() {
  log.info "installed(${VERSION})"
  sendEvent(name: "door", value: "unknown")
  sendEvent(name: "contact", value: "unknown")
  runIn(10, refresh)  // Get current device state after being installed.
}

void updated() {
  log.debug "updated()"
  log.warn "reporting stopped state is: ${reportStopped == true}"
  log.warn "debug logging is: ${logEnable == true}"
  log.warn "description logging is: ${txtEnable == true}"
  unschedule()
  if (logEnable) runIn(3600, logsOff)
}

void refresh() {
  logger "info", "refresh()"
  List<hubitat.zwave.Command> cmds=[
    secureCmd(zwave.switchMultilevelV4.switchMultilevelGet())
  ]
  sendCommands(cmds, 500)
}

void configure() {
  logger("debug", "configure()")
  List<hubitat.zwave.Command> cmds=[
    secureCmd(zwave.versionV3.versionGet())
  ]
  if (!device.getDataValue("MSR")) {
    cmds.add(secureCmd(zwave.manufacturerSpecificV2.manufacturerSpecificGet()))
  }
  runIn(cmds.size()*2, refresh)
  sendCommands(cmds, 500)
}

void open() {
  logger("debug", "open()")
  List<hubitat.zwave.Command> cmds=[
    supervisionEncap(zwave.switchMultilevelV4.switchMultilevelSet(value: 0x63, dimmingDuration: 0x00))
  ]
  sendCommands(cmds, 200)
}

void close() {
  logger("debug", "close()")
  List<hubitat.zwave.Command> cmds=[
    supervisionEncap(zwave.switchMultilevelV4.switchMultilevelSet(value: 0x00, dimmingDuration: 0x00))
  ]
  sendCommands(cmds, 200)
}

void parse(String description) {
  logger("debug", "parse() - description: ${description.inspect()}")
  hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
  if (cmd) {
    logger("debug", "parse() - parsed to cmd: ${cmd?.inspect()} with result: ${result?.inspect()}")
    zwaveEvent(cmd)
  } else {
    logger("error", "parse() - non-parsed - description: ${description?.inspect()}")
  }
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd){
  logger("trace", "zwaveEvent(SwitchMultilevelReport) - cmd: ${cmd.inspect()}")

  reportState(getBarrierState(cmd.value, cmd.targetValue), getContactState(cmd.value, cmd.targetValue))
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
  logger("trace", "zwaveEvent(ManufacturerSpecificReport) - cmd: ${cmd.inspect()}")
  if (cmd.manufacturerName) { device.updateDataValue("manufacturer", cmd.manufacturerName) }
  if (cmd.productTypeId) { device.updateDataValue("productTypeId", cmd.productTypeId.toString()) }
  if (cmd.productId) { device.updateDataValue("deviceId", cmd.productId.toString()) }
  device.updateDataValue("MSR", String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId))
}

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {
  logger("trace", "zwaveEvent(VersionReport) - cmd: ${cmd.inspect()}")
  device.updateDataValue("firmwareVersion", "${cmd.firmware0Version}.${cmd.firmware0SubVersion}")
  device.updateDataValue("protocolVersion", "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}")
  device.updateDataValue("hardwareVersion", "${cmd.hardwareVersion}")
  if (cmd.firmwareTargets > 0) {
    cmd.targetVersions.each { target ->
      device.updateDataValue("firmware${target.target}Version", "${target.version}.${target.subVersion}")
    }
  }
}

void zwaveEvent(hubitat.zwave.Command cmd) {
  logger("warn", "zwaveEvent(Command) - Unspecified - cmd: ${cmd.inspect()}")
}

void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd){
  logger("trace", "zwaveEvent(BasicReport) - cmd: ${cmd.inspect()}. No action.")
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelSet cmd){
  logger("trace", "zwaveEvent(SwitchMultilevelSet) - cmd: ${cmd.inspect()}. No action.")
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelStopLevelChange cmd) {
  logger("trace", "zwaveEvent(SwitchMultilevelStopLevelChange) - cmd: ${cmd.inspect()}. No action")
}

private void sendEventWrapper(Map prop) {
  String cv = device.currentValue(prop.name)
  Boolean changed = (prop.isStateChange == true) || ((cv?.toString() != prop.value?.toString()) ? true : false)
  if (changed) sendEvent(prop)
  if (prop?.descriptionText) {
    if (txtEnable && changed) {
      log.info "${device.displayName} ${prop.descriptionText}"
    } else {
      logger("debug", "${prop.descriptionText}")
    }
  }
}

void handleSupervisedCommand(hubitat.zwave.Command cmd, supervisionStatus) {
    logger("warn", "handleSupervisedCommand(Command) - Unspecified - cmd: ${cmd.inspect()}, status: ${supervisionStatus}")
}

void handleSupervisedCommand(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelSet cmd, supervisionStatus) {
    logger("trace", "handleSupervisedCommand(SwitchMultilevelSet) - cmd: ${cmd.inspect()}, status: ${supervisionStatus}")

    switch (supervisionStatus) {
      case 0x01: // "Working"
        // If the device responded with a working status upon receiving a command to open or close the gate,
        // we can assume the command was accepted and is undergoing, so we can report the state as opening or closing.
        // There is no need to request a report from the device.
        reportState(getBarrierState((Short) 0xFE, cmd.value), getContactState((Short) 0xFE, cmd.value))
        break
      case 0xFF: // "Success"
        // If the device responded with a success status upon receiving a command to open or close the gate,
        // we can assume that the gate was open or closed already.
        // There is no need to request a report from the device.
        reportState(getBarrierState(cmd.value, cmd.value), getContactState(cmd.value, cmd.value))
        break
    }
}

// The code below with minimal changes is largely based on ZooZ custom drivers developed by Jeff Page.
// Source: https://github.com/jtp10181/Hubitat/tree/main/Drivers/zooz

/**
 *  Copyright 2024 Jeff Page
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

/* Commands handling */

void sendCommands(List<String> cmds, Long delay=200) {
  logger("debug", "sendCommands Commands($cmds), delay ($delay)")
  //Calculate supervisionCheck delay based on how many commands are being sent.
  Integer packetsCount = supervisedPackets?."${device.id}"?.size()
  if (packetsCount > 0) {
    Integer delayTotal = (cmds.size() * delay) + 2000
    logger ("debug", "Setting supervisionCheck to ${delayTotal}ms | ${packetsCount} | ${cmds.size()} | ${delay}")
    runInMillis(delayTotal, supervisionCheck, [data:1])
   }
   //Send the commands
  sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

void sendCommands(String cmd) {
  sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
}

String secureCmd(String cmd) {
  logger("debug", "secureCmd String(${cmd})")
  return zwaveSecureEncap(cmd)
}

String secureCmd(hubitat.zwave.Command cmd) {
  logger("debug", "secureCmd Command(${cmd})")
  return zwaveSecureEncap(cmd.format())
}

/* Supervision handling */

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
  logger("trace", "zwaveEvent(SecurityMessageEncapsulation) - cmd: ${cmd.inspect()}")
  hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
  if (encapsulatedCommand) {
    logger("trace", "zwaveEvent(SecurityMessageEncapsulation) - encapsulatedCommand: ${encapsulatedCommand}")
    zwaveEvent(encapsulatedCommand)
  } else {
    logger("warn", "zwaveEvent(SecurityMessageEncapsulation) - Unable to extract Secure command from: ${cmd.inspect()}")
  }
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
  logger("trace", "zwaveEvent(SupervisionGet) - cmd: ${cmd.inspect()}")
  hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
  if (encapsulatedCommand) {
    logger("trace", "zwaveEvent(SupervisionGet) - encapsulatedCommand: ${encapsulatedCommand}")
    zwaveEvent(encapsulatedCommand)
  } else {
    logger("error", "SupervisionGet - Non-parsed - description: ${description?.inspect()}")
  }
  sendCommands(secureCmd(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0)))
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionReport cmd) {
  logger("trace", "zwaveEvent(SupervisionReport) - cmd: ${cmd.inspect()}")
  if (!supervisedPackets."${device.id}") { supervisedPackets."${device.id}" = [:] }
  switch (cmd.status as Integer) {
    case 0x00: // "No Support"
    case 0x02: // "Failed"
      logger("warn", "Supervision NOT Successful - SessionID: ${cmd.sessionID}, Status: ${cmd.status}")
      break
    case 0x01: // "Working"
    case 0xFF: // "Success"
      if (supervisedPackets["${device.id}"][cmd.sessionID] != null) {
        supervisedCmd = supervisedPackets["${device.id}"][cmd.sessionID].encapsulatedCommand(CMD_CLASS_VERS)

        handleSupervisedCommand(supervisedCmd, cmd.status)

        supervisedPackets["${device.id}"].remove(cmd.sessionID)
      }
      break
  }
}

@Field static Map<String, Map<Short, String>> supervisedPackets = new java.util.concurrent.ConcurrentHashMap()
@Field static Map<String, Short> sessionIDs = new java.util.concurrent.ConcurrentHashMap()

String supervisionEncap(hubitat.zwave.Command cmd) {
  logger("trace", "supervisionEncap(): ${cmd}")
  if (getDataValue("S2")?.toInteger() != null) {
    // Encapsulate with SupervisionGet command.
    Short sessId = getSessionId()
    def cmdEncap = zwave.supervisionV1.supervisionGet(sessionID: sessId).encapsulate(cmd)
    logger("debug", "New supervised packet for session: ${sessId}")
    if (supervisedPackets["${device.id}"] == null) { supervisedPackets["${device.id}"] = [:] }
    supervisedPackets["${device.id}"][sessId] = cmdEncap
    // Calculate supervisionCheck delay based on how many packets are cached.
    Integer packetsCount = supervisedPackets?."${device.id}"?.size()
    Integer delayTotal = (packetsCount * 500) + 2000
    runInMillis(delayTotal, supervisionCheck, [data:1])
    // Send secured command.
    return secureCmd(cmdEncap)
  } else {
    // If supervision is disabled just secure the command.
    return secureCmd(cmd)
  }
}

Short getSessionId() {
  Short sessId = sessionIDs["${device.id}"] ?: state.lastSupervision ?: 0
  sessId = (sessId + 1) % 64  // Will always will return between 0-63.
  state.lastSupervision = sessId
  sessionIDs["${device.id}"] = sessId
  return sessId
}

void supervisionCheck(Integer num) {
  Integer packetsCount = supervisedPackets?."${device.id}"?.size()
  logger("debug", "Supervision Check #${num} - Packet Count: ${packetsCount}")
  if (packetsCount > 0 ) {
    List<String> cmds = []
    supervisedPackets["${device.id}"].each { sid, cmd ->
      logger("warn",  "Re-Sending Supervised Session: ${sid} (Retry #${num})")
      cmds << secureCmd(cmd)
    }
    sendCommands(cmds)
    if (num >= 3) { //Clear after this many attempts
      logger("warn",  "Supervision MAX RETIES (${num}) Reached")
      supervisedPackets["${device.id}"].clear()
    } else { //Otherwise keep trying
      Integer delayTotal = (packetsCount * 500) + 2000
      runInMillis(delayTotal, supervisionCheck, [data:num+1])
    }
  }
}

/* Logging */

private logger(String level, String msg) {
  if (level == "error" || level == "warn") {
    log."${level}" "${device.displayName} ${msg}"
  } else{
    if (logEnable) log."${level}" "${device.displayName} ${msg}"
  }
}

void logsOff(){
  log.warn "debug logging is disabled..."
  device.updateSetting("logEnable",[value:"false",type:"bool"])
}

