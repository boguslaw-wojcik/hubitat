/**
 *  Nice IBT4ZWAVE Z-Wave 700 Driver for Hubitat
 *  Date: 19.09.2026
 *	Author: Bogusław Wójcik
 *
 *	CHANGELOG:
 *  - v0.2.0 - 19.09.2026: Support for the Z-Wave JS stack alongside the legacy one, plus a few new preferences for confirming the gate state.
 *  - v0.1.2 - 19.12.2024: Bring back the explicit request for the state update after command for non-securely included devices.
 *  - v0.1.1 - 17.12.2024: Improved fingerprint and added state update based on supervision report to improve speed and reliability.
 *  - v0.1.0 - 12.12.2024: Initial working version.
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

@Field String VERSION = "0.2.0"

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
    input name: "watchdogEnable", type: "bool", title: "Poll for state if no report arrives after a command", defaultValue: true
    input name: "watchdogDelay", type: "number", title: "Watchdog delay (seconds)", range: "1..30", defaultValue: 2
    input name: "travelConfirm", type: "bool", title: "Confirm final state after the gate finishes travelling", defaultValue: true
    input name: "travelTime", type: "number", title: "Expected travel time (seconds)", range: "5..300", defaultValue: 30
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
// Any call here means we learned something authoritative, so the watchdog poll is no longer needed.
void reportState(String barrierState, String contactState, String source = "report") {
  state.lastStateAt = now()
  unschedule("stateWatchdog")

  logger("debug", "reportState() - from ${source}")

  sendEventWrapper(name:"door", value: barrierState, descriptionText:"Barrier is ${barrierState}")
  sendEventWrapper(name:"contact", value: contactState, descriptionText:"Contact is ${contactState}")

  // While the gate is travelling, schedule a confirmation poll so a lost final report cannot leave
  // the device stuck on "opening"/"closing" forever. Once it settles, drop the confirmation.
  if (barrierState == "opening" || barrierState == "closing") {
    if (travelConfirm != false) {
      Integer t = (travelTime ?: 30) as Integer
      logger("debug", "Gate is ${barrierState}, scheduling confirmation poll in ${t}s")
      runIn(t, "confirmState")
    }
  } else {
    unschedule("confirmState")
  }
}

// True if we learned the state very recently. Used to avoid polling twice for the same command when
// both the watchdog and the supervision timeout fire around the same moment.
Boolean stateIsFresh(Integer withinMs = 3000) {
  Long last = (state.lastStateAt ?: 0) as Long
  return (last > 0 && (now() - last) < withinMs)
}

// Polls the device for its actual position. Used by the watchdog, the supervision timeout and the
// travel confirmation. Keeping radio traffic low is a goal of this driver, so redundant polls are skipped.
void pollState(String reason, Boolean force = false) {
  if (!force && stateIsFresh()) {
    logger("debug", "pollState() - skipped (${reason}), state already refreshed ${now() - (state.lastStateAt as Long)}ms ago")
    return
  }
  logger("debug", "pollState() - reason: ${reason}")
  sendCommands([secureCmd(zwave.switchMultilevelV4.switchMultilevelGet())])
}

// Fires when a command produced no state-bearing report in time. This is what keeps the driver
// working on Z-Wave JS, where the SupervisionReport is consumed by the stack and never reaches us.
void stateWatchdog() {
  logger("warn", "No state report after command, polling the device. (stack: ${describeStack()})")
  pollState("watchdog")
}

// Fires once the gate should have finished moving, to confirm it actually arrived.
void confirmState() {
  logger("debug", "confirmState() - travel time elapsed, confirming position")
  pollState("travel-confirm", true)
}

// Schedules the watchdog poll after we issue a movement command.
void scheduleWatchdog() {
  if (watchdogEnable == false) { return }
  Integer d = (watchdogDelay ?: 2) as Integer
  runIn(d, "stateWatchdog")
}

// Human readable description of what we believe the hub's Z-Wave stack is doing, for logs.
String describeStack() {
  return isZwaveJs() ? "Z-Wave JS" : "legacy Z/IP"
}

// Removes working values that earlier revisions of this driver wrote to `state`, so they stop showing
// on the device page. Only touches keys this driver no longer uses; safe to call at any time.
private void cleanupState() {
  ["posCurrent", "posTarget", "lastStateSource", "commandedTarget", "cleaned",
   "stackIsJs", "supervisionWorks", "supervisionMisses"].each {
    if (state.containsKey(it)) {
      logger("debug", "cleanupState() - removing obsolete state variable '${it}'")
      state.remove(it)
    }
  }
}

// Removes preferences that earlier revisions of this driver exposed and that are now fixed behaviour.
// Hubitat keeps a setting's value after its `input` disappears, so it has to be dropped explicitly.
private void cleanupSettings() {
  ["supervisionMode", "optimisticWindow", "useBasicReport"].each {
    if (settings?.containsKey(it)) {
      logger("debug", "cleanupSettings() - removing obsolete preference '${it}'")
      device.removeSetting(it)
    }
  }
}

/* Z-Wave stack detection */

// True when the hub is running the Z-Wave JS stack rather than the legacy Z/IP one.
//
// zwaveSecureEncap() formats a command for whichever stack is active and returns the string that would
// be transmitted: a JSON document on Z-Wave JS, a hex frame on legacy. It only formats - nothing is
// sent, and a Get allocates no supervision session - so this is a pure local check with no I/O.
// Measured at 1-2 ms, against ~1.9 s for zwave.getStoredValues(), which is a blocking round trip to
// the zwave-js server and therefore unusable anywhere near a command path.
//
// Being cheap and synchronous, this needs no cached flag and no traffic to have arrived first, which
// is why the driver no longer keeps a latched stackIsJs / supervisionWorks state variable.
Boolean isZwaveJs() {
  try {
    return zwaveSecureEncap(zwave.versionV3.versionGet().format())?.trim()?.startsWith("{")
  } catch (e) {
    logger("warn", "isZwaveJs() - could not determine the Z-Wave stack (${e}), assuming legacy")
    return false
  }
}

void installed() {
  log.info "installed(${VERSION})"
  cleanupState()
  sendEvent(name: "door", value: "unknown")
  sendEvent(name: "contact", value: "unknown")
  runIn(10, refresh)  // Get current device state after being installed.
}

void updated() {
  log.debug "updated()"
  cleanupState()
  cleanupSettings()
  log.warn "reporting stopped state is: ${reportStopped == true}"
  log.warn "debug logging is: ${logEnable == true}"
  log.warn "description logging is: ${txtEnable == true}"
  log.warn "Z-Wave stack detected: ${describeStack()}"
  log.warn "outbound supervision is: ${useSupervision() ? 'on' : 'off'} (automatic: on for S2 devices on the legacy stack only)"
  log.warn "state watchdog is: ${watchdogEnable != false} (${watchdogDelay ?: 2}s), travel confirmation is: ${travelConfirm != false} (${travelTime ?: 30}s)"

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
  cleanupState()

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

  // Remember what we asked for and when. Z-Wave JS echoes the commanded position straight back as if
  // the gate were already there; knowing what we commanded is what lets us tell that echo apart from
  // the gate actually having arrived.
  state.commandedPosition = 99
  state.commandedAt = now()

  List<hubitat.zwave.Command> cmds=[
    supervisionEncap(zwave.switchMultilevelV4.switchMultilevelSet(value: 0x63, dimmingDuration: 0x00))
  ]

  sendCommands(cmds, 200)

  // Guarantee a state update regardless of the hub's Z-Wave stack.
  //
  // On the legacy stack the device answers the supervised Set with a "working" SupervisionReport within
  // milliseconds, we report "opening" from it and the watchdog is cancelled before it ever fires.
  //
  // On Z-Wave JS the stack consumes the SupervisionReport itself, so that fast path never happens. The
  // watchdog then polls the device and we learn the state from a SwitchMultilevelReport instead. Costs
  // one extra packet, only on the stack that needs it.
  scheduleWatchdog()
}

void close() {
  logger("debug", "close()")

  // Remember what we asked for and when. Z-Wave JS echoes the commanded position straight back as if
  // the gate were already there; knowing what we commanded is what lets us tell that echo apart from
  // the gate actually having arrived.
  state.commandedPosition = 0
  state.commandedAt = now()

  List<hubitat.zwave.Command> cmds=[
    supervisionEncap(zwave.switchMultilevelV4.switchMultilevelSet(value: 0x00, dimmingDuration: 0x00))
  ]

  sendCommands(cmds, 200)

  // Guarantee a state update regardless of the hub's Z-Wave stack.
  //
  // On the legacy stack the device answers the supervised Set with a "working" SupervisionReport within
  // milliseconds, we report "closing" from it and the watchdog is cancelled before it ever fires.
  //
  // On Z-Wave JS the stack consumes the SupervisionReport itself, so that fast path never happens. The
  // watchdog then polls the device and we learn the state from a SwitchMultilevelReport instead. Costs
  // one extra packet, only on the stack that needs it.
  scheduleWatchdog()
}

void parse(String description) {
  logger("debug", "parse() - description: ${description.inspect()}")
  cleanupState()

  // The Z-Wave JS stack hands drivers a JSON value-update document instead of a Z-Wave frame. Feeding
  // that to zwave.parse() throws whenever the device reports an unknown position, because Z-Wave JS
  // encodes "unknown" as the string "null" and the legacy parser tries to cast it to a Short. That
  // exception used to kill the exact report that tells us the gate is moving, which is why the gate
  // jumped straight to "open"/"closed" and never showed "opening"/"closing". So we read it ourselves.
  if (description?.trim()?.startsWith("{")) {
    parseZwaveJs(description)
    return
  }

  hubitat.zwave.Command cmd = null
  try {
    cmd = zwave.parse(description, CMD_CLASS_VERS)
  } catch (e) {
    logger("error", "parse() - zwave.parse() failed: ${e}. description: ${description?.inspect()}")
    return
  }

  if (cmd) {
    logger("debug", "parse() - parsed to cmd: ${cmd?.inspect()}")
    zwaveEvent(cmd)
  } else {
    logger("error", "parse() - non-parsed - description: ${description?.inspect()}")
  }
}

/* Z-Wave JS value documents */

@Field static final Integer POSITION_UNKNOWN = 254

// How long after our own Set an echoed position is still taken for the Z-Wave JS optimistic echo
// rather than a genuine arrival. Measured echoes land 0.7-1.0 s after the command; the gate itself
// never settles in under ~15 s, so 3 s has margin on both sides and needs no tuning.
@Field static final Integer OPTIMISTIC_WINDOW_MS = 3000

// Z-Wave JS reports a position as a number, or as the string "null"/"unknown" when the device says it
// does not know where it is. The gate reports exactly that while it is travelling, and it is the single
// most useful signal this device produces.
Integer normalisePosition(def v) {
  if (v == null) { return null }
  if (v instanceof Number) { return ((Number) v).intValue() }
  String sv = v.toString()
  if (sv == "null" || sv == "unknown") { return POSITION_UNKNOWN }
  if (sv.isInteger()) { return sv.toInteger() }
  return POSITION_UNKNOWN
}

// Reads a Z-Wave JS value-update document and funnels Multilevel Switch updates into handlePosition().
void parseZwaveJs(String description) {
  Map doc
  try {
    doc = (Map) new groovy.json.JsonSlurper().parseText(description)
  } catch (e) {
    logger("error", "parseZwaveJs() - could not read document: ${e}")
    return
  }

  Integer cc = doc?.cc as Integer
  if (cc != 0x26) {   // Multilevel Switch is the only class this device uses for position.
    logger("debug", "parseZwaveJs() - ignoring command class ${cc}")
    return
  }

  Integer current = null, target = null, previous = null
  Boolean sawDuration = false, sawPrevious = false
  ((List) (doc?.values ?: [])).each { Map v ->
    switch (v?.propertyName) {
      case "currentValue":
        current = normalisePosition(v.containsKey("newValue") ? v.newValue : v.value)
        // prevValue is what the stack held for currentValue before this update. When it equals the
        // new value the update changed nothing, which is how a no-op command shows up.
        if (v.containsKey("prevValue")) { previous = normalisePosition(v.prevValue); sawPrevious = true }
        break
      case "targetValue":  target  = normalisePosition(v.containsKey("newValue") ? v.newValue : v.value); break
      case "duration":     sawDuration = true; break
    }
  }

  // A document carrying only currentValue is how Z-Wave JS delivers its optimistic post-command echo.
  Boolean partial = (current != null && target == null && !sawDuration)
  Boolean unchanged = (sawPrevious && current != null && previous == current)

  logger("debug", "parseZwaveJs() - currentValue: ${current}, prevValue: ${sawPrevious ? previous : 'n/a'}, targetValue: ${target}, duration present: ${sawDuration}, partial: ${partial}, unchanged: ${unchanged}")
  handlePosition(current, target, partial, "zwave-js", unchanged)
}

// Single funnel for every position update, from either stack.
//
// Z-Wave JS sends partial updates, so we keep the last known current/target and merge into them.
// Three cases produce a state:
//   1. The device says its position is unknown. That is the gate physically moving, and the target
//      tells us which way. This is the most trustworthy signal the device produces.
//   2. Z-Wave JS optimistically echoes the commanded value back as the current position within a
//      second of our Set, long before the gate has gone anywhere. Taking that at face value is what
//      made the gate report "open" the instant it was told to open. We recognise it and report
//      movement instead - unless the echo changed nothing (prevValue == newValue), which means the
//      gate was already where we asked it to go. Reporting movement there flipped the contact to
//      "open" for ~6 s on a close() of an already-closed gate. A no-op echo is a settled position.
//   3. Anything else is a settled position.
//
// `unchanged` is only known for Z-Wave JS documents, which carry prevValue. The legacy
// SwitchMultilevelReport has no such field and leaves it false, which preserves the old behaviour.
void handlePosition(Integer current, Integer target, Boolean partial, String source, Boolean unchanged = false) {
  // No cache of the last seen position/target is kept. Every document that has mattered so far carries
  // the current position, and every "position unknown" document has carried the target alongside it.
  // Where a target is genuinely absent we fall back to what we commanded, which covers any movement we
  // started ourselves. A document we cannot interpret is ignored rather than guessed at - the next
  // report or the watchdog corrects it.
  if (current == null) {
    logger("debug", "handlePosition() - report carried no current position, ignoring")
    return
  }

  Integer cur = current
  Integer tgt = target
  Integer commanded = (state.commandedPosition != null) ? (state.commandedPosition as Integer) : null
  Long since = now() - ((state.commandedAt ?: 0L) as Long)
  Integer window = OPTIMISTIC_WINDOW_MS

  String barrier, contact, why

  if (cur == POSITION_UNKNOWN) {
    // Which source the target comes from decides whether a cache of the last seen target is needed.
    // "from report" means no cache is required. "from our last command" still works for movement we
    // started, but a gate opened by the remote has no command to fall back on - if that case shows up
    // as "no target available", we need to start caching the last seen target.
    Integer t
    String tgtSource
    if (tgt != null && tgt != POSITION_UNKNOWN) {
      t = tgt;        tgtSource = "from report"
    } else if (commanded != null) {
      t = commanded;  tgtSource = "from our last command"
    } else {
      t = POSITION_UNKNOWN
      tgtSource = "NO TARGET AVAILABLE - report carried none and we issued no command"
      logger("warn", "Gate is moving but nothing tells us which way: no targetValue in the report and no command from us. " +
                     "This is the case that would justify caching the last seen target.")
    }
    barrier = getBarrierState((Short) POSITION_UNKNOWN, (Short) t)
    contact = getContactState((Short) POSITION_UNKNOWN, (Short) t)
    why = "position unknown, target ${t} (${tgtSource}) - gate is moving"
  } else if (partial && commanded != null && cur == commanded && since < window) {
    if (unchanged) {
      // The stack already held this position before our command, so the command was a no-op and the
      // gate is not going anywhere. Reporting it as settled sends no events (the attributes already
      // say so) and cancels the watchdog, so a redundant command costs no extra packet.
      barrier = getBarrierState((Short) cur, (Short) cur)
      contact = getContactState((Short) cur, (Short) cur)
      why = "optimistic echo ${since}ms after our command but the position was already ${cur} before it (prevValue == newValue) - no-op command, treating as settled"
      state.commandedPosition = null
    } else {
      barrier = getBarrierState((Short) POSITION_UNKNOWN, (Short) commanded)
      contact = getContactState((Short) POSITION_UNKNOWN, (Short) commanded)
      why = "optimistic echo ${since}ms after our command, treating as movement toward ${commanded}"
    }
  } else {
    barrier = getBarrierState((Short) cur, (Short) cur)
    contact = getContactState((Short) cur, (Short) cur)
    why = "settled at ${cur}"
    if (commanded != null && cur == commanded) {
      state.commandedPosition = null
    }
  }

  // getBarrierState() returns null for combinations it does not describe, never send that as an event.
  if (barrier == null) { barrier = reportStopped ? "stopped" : "unknown" }
  if (contact == null) { contact = (cur == 0) ? "closed" : "open" }

  logger("debug", "handlePosition() - ${why} -> door: ${barrier}, contact: ${contact}")
  reportState(barrier, contact, "${source}: ${why}")
}

// This is the primary, stack-agnostic source of truth. On the legacy stack it arrives unsolicited and in
// response to our polls. On Z-Wave JS the platform also synthesises it from the supervision response it
// consumed on our behalf (added in platform 2.4.3.155, "produce missing reports based on supervision
// response, per Z-Wave specs"), so this handler carries the load there.
void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd){
  logger("trace", "zwaveEvent(SwitchMultilevelReport) - cmd: ${cmd.inspect()}")
  logger("debug", "SwitchMultilevelReport - value: ${cmd.value}, targetValue: ${cmd.targetValue}, duration: ${cmd.duration}")

  Integer cur = (cmd.value != null) ? (cmd.value as Integer) : null
  Integer tgt = (cmd.targetValue != null) ? (cmd.targetValue as Integer) : null
  Boolean partial = (tgt == null && cmd.duration == null)

  handlePosition(cur, tgt, partial, "SwitchMultilevelReport")
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

// The gate emits Access Control notifications (type 6) alongside its position reports. We do not drive
// state from them, but they are logged quietly rather than as warnings while we work out whether any of
// the events are worth acting on.
void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd) {
  logger("debug", "zwaveEvent(NotificationReport) - type: ${cmd.notificationType}, event: ${cmd.event}, parameters: ${cmd.eventParameter}. No action.")
}

// BasicReport is ignored because the device reports properly via SwitchMultilevel on both stacks.
// It is logged in case a future platform change starts mapping the gate's state onto Basic instead.
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd){
  logger("debug", "BasicReport - value: ${cmd.value}, targetValue: ${cmd.targetValue}, duration: ${cmd.duration}. No action.")
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

    // On Z-Wave JS the "success" we get back is manufactured by the stack the moment the command is
    // accepted - it is not the device saying it has arrived. Acting on it reports the gate open before
    // it has moved an inch. On that stack the position reports are richer and authoritative, so let
    // them do the work and ignore supervision for state entirely.
    if (isZwaveJs()) {
        logger("debug", "handleSupervisedCommand() - Z-Wave JS stack, state comes from position reports instead")
        return
    }

    switch (supervisionStatus) {
      case 0x01: // "Working"
        // If the device responded with a working status upon receiving a command to open or close the gate,
        // we can assume the command was accepted and is undergoing, so we can report the state as opening or closing.
        // There is no need to request a report from the device.
        reportState(getBarrierState((Short) 0xFE, cmd.value), getContactState((Short) 0xFE, cmd.value), "SupervisionReport(working)")
        break
      case 0xFF: // "Success"
        // If the device responded with a success status upon receiving a command to open or close the gate,
        // we can assume that the gate was open or closed already.
        // There is no need to request a report from the device.
        reportState(getBarrierState(cmd.value, cmd.value), getContactState(cmd.value, cmd.value), "SupervisionReport(success)")
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
      Map session = supervisedPackets["${device.id}"][cmd.sessionID]
      if (session != null) {
        supervisedPackets["${device.id}"].remove(cmd.sessionID)
        handleSupervisedCommand(session.cmd, cmd.status)
      } else {
        logger("debug", "SupervisionReport for unknown session ${cmd.sessionID}, ignoring")
      }
      break
  }
}

@Field static Map<String, Map<Short, String>> supervisedPackets = new java.util.concurrent.ConcurrentHashMap()
@Field static Map<String, Short> sessionIDs = new java.util.concurrent.ConcurrentHashMap()

// Decides whether to wrap outbound commands in SupervisionGet.
//
// Only S2 devices support it, and only the legacy stack needs the driver to do it: Z-Wave JS supervises
// on the driver's behalf, so encapsulating again there is pure duplication. Earlier revisions exposed a
// manual override for this; it went once stack detection became deterministic.
Boolean useSupervision() {
  if (getDataValue("S2")?.toInteger() == null) { return false }
  return !isZwaveJs()
}

String supervisionEncap(hubitat.zwave.Command cmd) {
  logger("trace", "supervisionEncap(): ${cmd}")
  if (useSupervision()) {
    // Encapsulate with SupervisionGet command.
    Short sessId = getSessionId()
    def cmdEncap = zwave.supervisionV1.supervisionGet(sessionID: sessId).encapsulate(cmd)
    logger("debug", "New supervised packet for session: ${sessId}")
    if (supervisedPackets["${device.id}"] == null) { supervisedPackets["${device.id}"] = [:] }
    // Keep the original command alongside the encapsulation. Z-Wave JS rewrites our SupervisionGet into
    // a node.set_value call and hands back an envelope with an empty command body, so decapsulating it
    // later throws. Holding on to what we actually sent removes the need to decapsulate at all.
    supervisedPackets["${device.id}"][sessId] = [cmd: cmd, encap: cmdEncap]
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

// Runs when supervised packets have gone unacknowledged.
//
// The original implementation re-sent the packet up to three times. That is correct for a dimmer, but
// it is NOT safe here: on Z-Wave JS the acknowledgement never comes back to the driver, so every gate
// command would have been re-issued three times over. For a barrier that is unacceptable, so we never
// re-send. We drop the packet, note the miss and poll for the real state instead.
void supervisionCheck(Integer num) {
  Integer packetsCount = supervisedPackets?."${device.id}"?.size() ?: 0
  logger("debug", "Supervision Check #${num} - Packet Count: ${packetsCount}")

  if (packetsCount == 0) { return }

  supervisedPackets["${device.id}"].each { sid, cmd ->
    logger("warn", "No SupervisionReport for session ${sid}. NOT re-sending it - re-issuing a gate command would be unsafe.")
  }
  supervisedPackets["${device.id}"].clear()

  // Recover the truth rather than guessing.
  pollState("supervision-miss")
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

