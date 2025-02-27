/* groovylint-disable DuplicateListLiteral, DuplicateNumberLiteral, DuplicateStringLiteral, ImplementationAsType, InvertedCondition, LineLength, MethodReturnTypeRequired, MethodSize, NestedBlockDepth, NoDef, UnnecessaryGString, UnnecessaryObjectReferences, UnnecessaryToString, VariableTypeRequired */
/*****************************************************************************************************************
 *  Source: https://github.com/HubitatCommunity/InfluxDB-Logger
 *
 *  Raw Source: https://raw.githubusercontent.com/HubitatCommunity/InfluxDB-Logger/master/influxdb-logger.groovy
 *
 *  Forked from: https://github.com/codersaur/SmartThings/tree/master/smartapps/influxdb-logger
 *  Original Author: David Lomas (codersaur)
 *  Hubitat Elevation version maintained by HubitatCommunity (https://github.com/HubitatCommunity/InfluxDB-Logger)
 *
 *  License:
 *   Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *   for the specific language governing permissions and limitations under the License.
 *
 *   Modifcation History
 *   Date       Name            Change
 *   2019-02-02 Dan Ogorchock   Use asynchttpPost() instead of httpPost() call
 *   2019-09-09 Caleb Morse     Support deferring writes and doing buld writes to influxdb
 *   2022-06-20 Denny Page      Remove nested sections for device selection.
 *   2023-01-08 Denny Page      Address whitespace related lint issues. No functional changes.
 *   2023-01-09 Craig King      Added InfluxDb2.x support.
 *   2023=01-12 Denny Page      Automatic migration of Influx 1.x settings.
 *   2023-01-15 Denny Page      Clean up various things:
 *                              Remove Group ID/Name which are not supported on Hubitat.
 *                              Remove Location ID and Hub ID which are not supported on Hubitat (always 1).
 *                              Remove blocks of commented out code.
 *                              Don't set page sections hidden to false where hideable is false.
 *                              Remove state.queuedData.
 *   2023=01-22 PJ              Add filterEvents option for subscribe.
 *                              Fix event timestamps.
 *   2023=01-23 Denny Page      Allow multiple instances of the application to be installed.
 *                              NB: This requires Hubitat 2.2.9 or above.
 *   2023-01-25 Craig King      Updated Button selection to valid capability for Hubitat
 *   2023-02-16 PJ              Add error message to log for http response >= 400
 *                              Allow ssl cert verification to be disabled (self signed certificates)
 *   2023-02-26 Denny Page      Cleanup and rationalize UI
 *                              Use time since first data value to trigger post rather than periodic timer
 *                              Only create a keep alive event (softpoll) when no real event has been seen
 *                              Cleanup and rationalize logging
 *                              Further code cleanup
 *   2023-02-28 Denny Page      Retry failed posts
 *                              Enhance post logging
 *                              Allow Hub Name and Location tags to be disabled for device events
 *                              Further code cleanup
 *****************************************************************************************************************/

definition(
    name: "InfluxDB Logger",
    namespace: "nowhereville",
    author: "Joshua Marker (tooluser)",
    description: "Log device states to InfluxDB",
    category: "My Apps",
    importUrl: "https://raw.githubusercontent.com/HubitatCommunity/InfluxDB-Logger/master/influxdb-logger.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleThreaded: true
)

preferences {
    page(name: "setupMain")
    page(name: "connectionPage")
}

def setupMain() {
    dynamicPage(name: "setupMain", title: "<h2>InfluxDB Logger</h2>", install: true, uninstall: true) {
        section("<h3>\nGeneral Settings:</h3>") {
            input "appName", "text", title: "Aplication Name", multiple: false, required: true, submitOnChange: true, defaultValue: app.getLabel()

            input(
                name: "configLoggingLevelIDE",
                title: "System log level - messages with this level and higher will be sent to the system log",
                type: "enum",
                options: [
                    "0" : "None",
                    "1" : "Error",
                    "2" : "Warning",
                    "3" : "Info",
                    "4" : "Debug"
                ],
                defaultValue: "2",
                required: false
            )

        }

        section("Change Application Name"){
            input "nameOverride", "text", title: "New Name for Application", multiple: false, required: false, submitOnChange: true, defaultValue: app.getLabel()
            if(nameOverride != app.getLabel) app.updateLabel(nameOverride)
        }

        section("\n<h3>InfluxDB Settings:</h3>") {
            href(
                name: "href",
                title: "InfluxDB connection",
                description : prefDatabaseHost == null ? "Configure database connection parameters" : prefDatabaseHost,
                required: true,
                page: "connectionPage"
            )
            input(
                name: "prefBatchTimeLimit",
                title: "Batch time limit - maximum number of seconds before writing a batch to InfluxDB (range 1-300)",
                type: "number",
                range: "1..300",
                defaultValue: "60",
                required: true
            )
            input(
                name: "prefBatchSizeLimit",
                title: "Batch size limit - maximum number of events in a batch to InfluxDB (range 1-250)",
                type: "number",
                range: "1..250",
                defaultValue: "50",
                required: true
            )
            input(
                name: "prefBacklogLimit",
                title: "Backlog size limit - maximum number of queued events before dropping failed posts (range 1000-25000)",
                type: "number",
                range: "1000..25000",
                defaultValue: "5000",
                required: true
            )
        }

        section("\n<h3>Device Event Handling:</h3>") {
            input "includeHubInfo", "bool", title:"Include Hub Name and Hub Location as InfluxDB tags for device events", defaultValue: true
            input "filterEvents", "bool", title:"Only post device events to InfluxDB when the data value changes", defaultValue: true

            input(
                // NB: Called prefSoftPollingInterval for backward compatibility with prior versions
                name: "prefSoftPollingInterval",
                title: "Post keep alive events (aka softpoll) - re-post last value if a new event has not occurred in this time",
                type: "enum",
                options: [
                    "0" : "disabled",
                    "1" : "1 minute (not recommended)",
                    "5" : "5 minutes",
                    "10" : "10 minutes",
                    "15" : "15 minutes",
                    "30" : "30 minutes",
                    "60" : "60 minutes",
                    "180" : "3 hours"
                ],
                defaultValue: "15",
                submitOnChange: true,
                required: true
            )
        }

        section("Devices To Monitor:", hideable:true, hidden:false) {
            input "accessAllAttributes", "bool", title:"Advanced attribute seletion?", defaultValue: false, submitOnChange: true

            if (accessAllAttributes) {
                input name: "allDevices", type: "capability.*", title: "Selected Devices", multiple: true, required: false, submitOnChange: true

                state.selectedAttr = [:]
                settings.allDevices.each { deviceName ->
                    if (deviceName) {
                        deviceId = deviceName.getId()
                        attr = deviceName.getSupportedAttributes().unique()
                        if (attr) {
                            state.options = []
                            index = 0
                            attr.each { at ->
                                state.options[index] = "${at}"
                                index = index + 1
                            }
                            input name:"attrForDev$deviceId", type: "enum", title: "$deviceName", options: state.options, multiple: true, required: false, submitOnChange: true
                            state.selectedAttr[deviceId] = settings["attrForDev" + deviceId]
                        }
                    }
                }
            }
            else {
                input "accelerometers", "capability.accelerationSensor", title: "Accelerometers", multiple: true, required: false
                input "alarms", "capability.alarm", title: "Alarms", multiple: true, required: false
                input "batteries", "capability.battery", title: "Batteries", multiple: true, required: false
                input "beacons", "capability.beacon", title: "Beacons", multiple: true, required: false
                input "buttons", "capability.pushableButton", title: "Buttons", multiple: true, required: false
                input "cos", "capability.carbonMonoxideDetector", title: "Carbon Monoxide Detectors", multiple: true, required: false
                input "co2s", "capability.carbonDioxideMeasurement", title: "Carbon Dioxide Detectors", multiple: true, required: false
                input "colors", "capability.colorControl", title: "Color Controllers", multiple: true, required: false
                input "consumables", "capability.consumable", title: "Consumables", multiple: true, required: false
                input "contacts", "capability.contactSensor", title: "Contact Sensors", multiple: true, required: false
                input "doorsControllers", "capability.doorControl", title: "Door Controllers", multiple: true, required: false
                input "energyMeters", "capability.energyMeter", title: "Energy Meters", multiple: true, required: false
                input "humidities", "capability.relativeHumidityMeasurement", title: "Humidity Meters", multiple: true, required: false
                input "illuminances", "capability.illuminanceMeasurement", title: "Illuminance Meters", multiple: true, required: false
                input "locks", "capability.lock", title: "Locks", multiple: true, required: false
                input "motions", "capability.motionSensor", title: "Motion Sensors", multiple: true, required: false
                input "musicPlayers", "capability.musicPlayer", title: "Music Players", multiple: true, required: false
                input "peds", "capability.stepSensor", title: "Pedometers", multiple: true, required: false
                input "phMeters", "capability.pHMeasurement", title: "pH Meters", multiple: true, required: false
                input "powerMeters", "capability.powerMeter", title: "Power Meters", multiple: true, required: false
                input "presences", "capability.presenceSensor", title: "Presence Sensors", multiple: true, required: false
                input "pressures", "capability.pressureMeasurement", title: "Pressure Sensors", multiple: true, required: false
                input "shockSensors", "capability.shockSensor", title: "Shock Sensors", multiple: true, required: false
                input "signalStrengthMeters", "capability.signalStrength", title: "Signal Strength Meters", multiple: true, required: false
                input "sleepSensors", "capability.sleepSensor", title: "Sleep Sensors", multiple: true, required: false
                input "smokeDetectors", "capability.smokeDetector", title: "Smoke Detectors", multiple: true, required: false
                input "soundSensors", "capability.soundSensor", title: "Sound Sensors", multiple: true, required: false
                input "spls", "capability.soundPressureLevel", title: "Sound Pressure Level Sensors", multiple: true, required: false
                input "switches", "capability.switch", title: "Switches", multiple: true, required: false
                input "switchLevels", "capability.switchLevel", title: "Switch Levels", multiple: true, required: false
                input "tamperAlerts", "capability.tamperAlert", title: "Tamper Alerts", multiple: true, required: false
                input "temperatures", "capability.temperatureMeasurement", title: "Temperature Sensors", multiple: true, required: false
                input "thermostats", "capability.thermostat", title: "Thermostats", multiple: true, required: false
                input "threeAxis", "capability.threeAxis", title: "Three-axis (Orientation) Sensors", multiple: true, required: false
                input "touchs", "capability.touchSensor", title: "Touch Sensors", multiple: true, required: false
                input "uvs", "capability.ultravioletIndex", title: "UV Sensors", multiple: true, required: false
                input "valves", "capability.valve", title: "Valves", multiple: true, required: false
                input "volts", "capability.voltageMeasurement", title: "Voltage Meters", multiple: true, required: false
                input "waterSensors", "capability.waterSensor", title: "Water Sensors", multiple: true, required: false
                input "windowShades", "capability.windowShade", title: "Window Shades", multiple: true, required: false
            }
        }

        if (prefSoftPollingInterval != "0") {
            section("System Monitoring:", hideable:true, hidden:true) {
                input "prefLogHubProperties", "bool", title:"Post Hub Properties such as IP and firmware to InfluxDB", defaultValue: false
                input "prefLogLocationProperties", "bool", title:"Post Location Properties such as sunrise/sunset to InfluxDB", defaultValue: false
                input "prefLogModeEvents", "bool", title:"Post Mode Events to InfluxDB", defaultValue: false
            }
        }
    }
}

def connectionPage() {
    dynamicPage(name: "connectionPage", title: "Connection Properties", install: false, uninstall: false) {
        section {
            input "prefDatabaseTls", "bool", title:"Use TLS?", defaultValue: false, submitOnChange: true
            if (prefDatabaseTls) {
                input "prefIgnoreSSLIssues", "bool", title:"Ignore SSL cert verification issues", defaultValue:false
            }

            input "prefDatabaseHost", "text", title: "Host", defaultValue: "", required: true
            input "prefDatabasePort", "text", title : "Port", defaultValue : prefDatabaseTls ? "443" : "8086", required : false
            input(
                name: "prefInfluxVer",
                title: "Influx Version",
                type: "enum",
                options: [
                    "1" : "v1.x",
                    "2" : "v2.x"
                ],
                defaultValue: "1",
                submitOnChange: true
            )
            if (prefInfluxVer == "1") {
                input "prefDatabaseName", "text", title: "Database Name", defaultValue: "Hubitat", required: true
            }
            else if (prefInfluxVer == "2") {
                input "prefOrg", "text", title: "Org", defaultValue: "", required: true
                input "prefBucket", "text", title: "Bucket", defaultValue: "", required: true
            }
            input(
                name: "prefAuthType",
                title: "Authorization Type",
                type: "enum",
                options: [
                    "none" : "None",
                    "basic" : "Username / Password",
                    "token" : "Token"
                ],
                defaultValue: "none",
                submitOnChange: true
            )
            if (prefAuthType == "basic") {
                input "prefDatabaseUser", "text", title: "Username", defaultValue: "", required: true
                input "prefDatabasePass", "text", title: "Password", defaultValue: "", required: true
            }
            else if (prefAuthType == "token") {
                input "prefDatabaseToken", "text", title: "Token", required: true
            }
        }
    }
}

def getDeviceObj(id) {
    def found
    settings.allDevices.each { device ->
        if (device.getId() == id) {
            //log.debug "Found at $device for $id with id: ${device.id}"
            found = device
        }
    }
    return found
}

/**
 *  installed()
 *
 *  Runs when the app is first installed.
 **/
def installed() {
    state.installedAt = now()
    state.loggingLevelIDE = 3
    state.loggerQueue = []
    updated()
    log.info "${app.label}: Installed"
}

/**
 *  uninstalled()
 *
 *  Runs when the app is uninstalled.
 **/
def uninstalled() {
    log.info "${app.label}: Uninstalled"
}

/**
 *  updated()
 *
 *  Runs when app settings are changed.
 *
 *  Updates device.state with input values and other hard-coded values.
 *  Builds state.deviceAttributes which describes the attributes that will be monitored for each device collection
 *  (used by manageSubscriptions() and softPoll()).
 *  Refreshes scheduling and subscriptions.
 **/
def updated() {
    // Update application name
    app.updateLabel(appName)
    logger("${app.label}: Updated", "info")

    // Update internal state:
    state.loggingLevelIDE = (settings.configLoggingLevelIDE) ? settings.configLoggingLevelIDE.toInteger() : 3

    // Database config:
    setupDB()

    // Build array of device collections and the attributes we want to report on for that collection:
    //  Note, the collection names are stored as strings. Adding references to the actual collection
    //  objects causes major issues (possibly memory issues?).
    state.deviceAttributes = []
    state.deviceAttributes << [ devices: 'accelerometers', attributes: ['acceleration']]
    state.deviceAttributes << [ devices: 'alarms', attributes: ['alarm']]
    state.deviceAttributes << [ devices: 'batteries', attributes: ['battery']]
    state.deviceAttributes << [ devices: 'beacons', attributes: ['presence']]
    state.deviceAttributes << [ devices: 'buttons', attributes: ['pushed', 'doubleTapped', 'held', 'released']]
    state.deviceAttributes << [ devices: 'cos', attributes: ['carbonMonoxide']]
    state.deviceAttributes << [ devices: 'co2s', attributes: ['carbonDioxide']]
    state.deviceAttributes << [ devices: 'colors', attributes: ['hue', 'saturation', 'color']]
    state.deviceAttributes << [ devices: 'consumables', attributes: ['consumableStatus']]
    state.deviceAttributes << [ devices: 'contacts', attributes: ['contact']]
    state.deviceAttributes << [ devices: 'doorsControllers', attributes: ['door']]
    state.deviceAttributes << [ devices: 'energyMeters', attributes: ['energy']]
    state.deviceAttributes << [ devices: 'humidities', attributes: ['humidity']]
    state.deviceAttributes << [ devices: 'illuminances', attributes: ['illuminance']]
    state.deviceAttributes << [ devices: 'locks', attributes: ['lock']]
    state.deviceAttributes << [ devices: 'motions', attributes: ['motion']]
    state.deviceAttributes << [ devices: 'musicPlayers', attributes: ['status', 'level', 'trackDescription', 'trackData', 'mute']]
    state.deviceAttributes << [ devices: 'peds', attributes: ['steps', 'goal']]
    state.deviceAttributes << [ devices: 'phMeters', attributes: ['pH']]
    state.deviceAttributes << [ devices: 'powerMeters', attributes: ['power', 'voltage', 'current', 'powerFactor']]
    state.deviceAttributes << [ devices: 'presences', attributes: ['presence']]
    state.deviceAttributes << [ devices: 'pressures', attributes: ['pressure']]
    state.deviceAttributes << [ devices: 'shockSensors', attributes: ['shock']]
    state.deviceAttributes << [ devices: 'signalStrengthMeters', attributes: ['lqi', 'rssi']]
    state.deviceAttributes << [ devices: 'sleepSensors', attributes: ['sleeping']]
    state.deviceAttributes << [ devices: 'smokeDetectors', attributes: ['smoke']]
    state.deviceAttributes << [ devices: 'soundSensors', attributes: ['sound']]
    state.deviceAttributes << [ devices: 'spls', attributes: ['soundPressureLevel']]
    state.deviceAttributes << [ devices: 'switches', attributes: ['switch']]
    state.deviceAttributes << [ devices: 'switchLevels', attributes: ['level']]
    state.deviceAttributes << [ devices: 'tamperAlerts', attributes: ['tamper']]
    state.deviceAttributes << [ devices: 'temperatures', attributes: ['temperature']]
    state.deviceAttributes << [ devices: 'thermostats', attributes: ['temperature', 'heatingSetpoint', 'coolingSetpoint', 'thermostatSetpoint', 'thermostatMode', 'thermostatFanMode', 'thermostatOperatingState', 'thermostatSetpointMode', 'scheduledSetpoint', 'optimisation', 'windowFunction']]
    state.deviceAttributes << [ devices: 'threeAxis', attributes: ['threeAxis']]
    state.deviceAttributes << [ devices: 'touchs', attributes: ['touch']]
    state.deviceAttributes << [ devices: 'uvs', attributes: ['ultravioletIndex']]
    state.deviceAttributes << [ devices: 'valves', attributes: ['contact']]
    state.deviceAttributes << [ devices: 'volts', attributes: ['voltage']]
    state.deviceAttributes << [ devices: 'waterSensors', attributes: ['water']]
    state.deviceAttributes << [ devices: 'windowShades', attributes: ['windowShade']]

    // Configure device subscriptions:
    manageSubscriptions()

    // Subscribe to system start
    subscribe(location, "systemStart", hubRestartHandler)

    // Flush any pending batch and set up softpoll if requested
    unschedule()
    runIn(1, writeQueuedDataToInfluxDb)

    // Set up softpoll if requested
    // NB: This is called softPoll to maintain backward compatibility wirh prior versions
    state.softPollingInterval = settings.prefSoftPollingInterval.toInteger()
    switch (state.softPollingInterval) {
        case 1:
            runEvery1Minute(softPoll)
            break
        case 5:
            runEvery5Minutes(softPoll)
            break
        case 10:
            runEvery10Minutes(softPoll)
            break
        case 15:
            runEvery15Minutes(softPoll)
            break
        case 30:
            runEvery30Minutes(softPoll)
            break
        case 60:
            runEvery1Hour(softPoll)
            break
        case 180:
            runEvery3Hours(softPoll)
            break
    }

    // Clean up old state variables
    state.remove("queuedData")
    state.remove("writeInterval")
}

/**
 *
 * hubRestartHandler()
 *
 * Handle hub restarts.
**/
def hubRestartHandler(evt)
{
    loggerQueue = state.loggerQueue
    if (loggerQueue && loggerQueue.size()) {
        runIn(10, writeQueuedDataToInfluxDb)
    }
}

/**
 *  handleModeEvent(evt)
 *
 *  Log Mode changes.
 **/
def handleModeEvent(evt) {
    logger("Mode changed to: ${evt.value}", "info")

    def locationName = escapeStringForInfluxDB(location.name)
    def mode = '"' + escapeStringForInfluxDB(evt.value) + '"'
    long eventTimestamp = evt.unixTime * 1e6       // Time is in milliseconds, but InfluxDB expects nanoseconds
    def data = "_stMode,locationName=${locationName} mode=${mode} ${eventTimestamp}"
    queueToInfluxDb(data)
}

/**
 *  handleEvent(evt)
 *
 *  Builds data to send to InfluxDB.
 *   - Escapes and quotes string values.
 *   - Calculates logical binary values where string values can be
 *     represented as binary values (e.g. contact: closed = 1, open = 0)
 **/
def handleEvent(evt) {
    logger("Handle Event: $evt.displayName($evt.name:$evt.unit) $evt.value", "info")

    // Build data string to send to InfluxDB:
    //  Format: <measurement>[,<tag_name>=<tag_value>] field=<field_value>
    //    If value is an integer, it must have a trailing "i"
    //    If value is a string, it must be enclosed in double quotes.
    String measurement = evt.name
    // tags:
    String deviceId = evt?.deviceId?.toString()
    String deviceName = escapeStringForInfluxDB(evt?.displayName)
    String hubName = escapeStringForInfluxDB(evt?.device?.device?.hub?.name?.toString())
    String locationName = escapeStringForInfluxDB(location.name)

    String unit = escapeStringForInfluxDB(evt.unit)
    String value = escapeStringForInfluxDB(evt.value)
    String valueBinary = ''

    String data = "${measurement},deviceId=${deviceId},deviceName=${deviceName}"
    if (settings.includeHubInfo == null || settings.includeHubInfo) {
        data += ",hubName=${hubName},locationName=${locationName}"
    }

    // Unit tag and fields depend on the event type:
    //  Most string-valued attributes can be translated to a binary value too.
    if ('acceleration' == evt.name) { // acceleration: Calculate a binary value (active = 1, inactive = 0)
        unit = 'acceleration'
        value = '"' + value + '"'
        valueBinary = ('active' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('alarm' == evt.name) { // alarm: Calculate a binary value (strobe/siren/both = 1, off = 0)
        unit = 'alarm'
        value = '"' + value + '"'
        valueBinary = ('off' == evt.value) ? '0i' : '1i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('button' == evt.name) { // button: Calculate a binary value (held = 1, pushed = 0)
        unit = 'button'
        value = '"' + value + '"'
        valueBinary = ('pushed' == evt.value) ? '0i' : '1i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('carbonMonoxide' == evt.name) { // carbonMonoxide: Calculate a binary value (detected = 1, clear/tested = 0)
        unit = 'carbonMonoxide'
        value = '"' + value + '"'
        valueBinary = ('detected' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('consumableStatus' == evt.name) { // consumableStatus: Calculate a binary value ("good" = 1, "missing"/"replace"/"maintenance_required"/"order" = 0)
        unit = 'consumableStatus'
        value = '"' + value + '"'
        valueBinary = ('good' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('contact' == evt.name) { // contact: Calculate a binary value (closed = 1, open = 0)
        unit = 'contact'
        value = '"' + value + '"'
        valueBinary = ('closed' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('door' == evt.name) { // door: Calculate a binary value (closed = 1, open/opening/closing/unknown = 0)
        unit = 'door'
        value = '"' + value + '"'
        valueBinary = ('closed' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('lock' == evt.name) { // door: Calculate a binary value (locked = 1, unlocked = 0)
        unit = 'lock'
        value = '"' + value + '"'
        valueBinary = ('locked' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('motion' == evt.name) { // Motion: Calculate a binary value (active = 1, inactive = 0)
        unit = 'motion'
        value = '"' + value + '"'
        valueBinary = ('active' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('mute' == evt.name) { // mute: Calculate a binary value (muted = 1, unmuted = 0)
        unit = 'mute'
        value = '"' + value + '"'
        valueBinary = ('muted' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('presence' == evt.name) { // presence: Calculate a binary value (present = 1, not present = 0)
        unit = 'presence'
        value = '"' + value + '"'
        valueBinary = ('present' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('shock' == evt.name) { // shock: Calculate a binary value (detected = 1, clear = 0)
        unit = 'shock'
        value = '"' + value + '"'
        valueBinary = ('detected' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('sleeping' == evt.name) { // sleeping: Calculate a binary value (sleeping = 1, not sleeping = 0)
        unit = 'sleeping'
        value = '"' + value + '"'
        valueBinary = ('sleeping' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('smoke' == evt.name) { // smoke: Calculate a binary value (detected = 1, clear/tested = 0)
        unit = 'smoke'
        value = '"' + value + '"'
        valueBinary = ('detected' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('sound' == evt.name) { // sound: Calculate a binary value (detected = 1, not detected = 0)
        unit = 'sound'
        value = '"' + value + '"'
        valueBinary = ('detected' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('switch' == evt.name) { // switch: Calculate a binary value (on = 1, off = 0)
        unit = 'switch'
        value = '"' + value + '"'
        valueBinary = ('on' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('tamper' == evt.name) { // tamper: Calculate a binary value (detected = 1, clear = 0)
        unit = 'tamper'
        value = '"' + value + '"'
        valueBinary = ('detected' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('thermostatMode' == evt.name) { // thermostatMode: Calculate a binary value (<any other value> = 1, off = 0)
        unit = 'thermostatMode'
        value = '"' + value + '"'
        valueBinary = ('off' == evt.value) ? '0i' : '1i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('thermostatFanMode' == evt.name) { // thermostatFanMode: Calculate a binary value (<any other value> = 1, off = 0)
        unit = 'thermostatFanMode'
        value = '"' + value + '"'
        valueBinary = ('off' == evt.value) ? '0i' : '1i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('thermostatOperatingState' == evt.name) { // thermostatOperatingState: Calculate a binary value (heating = 1, <any other value> = 0)
        unit = 'thermostatOperatingState'
        value = '"' + value + '"'
        valueBinary = ('heating' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('thermostatSetpointMode' == evt.name) { // thermostatSetpointMode: Calculate a binary value (followSchedule = 0, <any other value> = 1)
        unit = 'thermostatSetpointMode'
        value = '"' + value + '"'
        valueBinary = ('followSchedule' == evt.value) ? '0i' : '1i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('threeAxis' == evt.name) { // threeAxis: Format to x,y,z values.
        unit = 'threeAxis'
        def valueXYZ = evt.value.split(",")
        def valueX = valueXYZ[0]
        def valueY = valueXYZ[1]
        def valueZ = valueXYZ[2]
        data += ",unit=${unit} valueX=${valueX}i,valueY=${valueY}i,valueZ=${valueZ}i" // values are integers.
    }
    else if ('touch' == evt.name) { // touch: Calculate a binary value (touched = 1, "" = 0)
        unit = 'touch'
        value = '"' + value + '"'
        valueBinary = ('touched' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('optimisation' == evt.name) { // optimisation: Calculate a binary value (active = 1, inactive = 0)
        unit = 'optimisation'
        value = '"' + value + '"'
        valueBinary = ('active' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('windowFunction' == evt.name) { // windowFunction: Calculate a binary value (active = 1, inactive = 0)
        unit = 'windowFunction'
        value = '"' + value + '"'
        valueBinary = ('active' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('touch' == evt.name) { // touch: Calculate a binary value (touched = 1, <any other value> = 0)
        unit = 'touch'
        value = '"' + value + '"'
        valueBinary = ('touched' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('water' == evt.name) { // water: Calculate a binary value (wet = 1, dry = 0)
        unit = 'water'
        value = '"' + value + '"'
        valueBinary = ('wet' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('windowShade' == evt.name) { // windowShade: Calculate a binary value (closed = 1, <any other value> = 0)
        unit = 'windowShade'
        value = '"' + value + '"'
        valueBinary = ('closed' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    else if ('valve' == evt.name) { // switch: Calculate a binary value (open = 1, closed = 0)
        unit = 'valve'
        value = '"' + value + '"'
        valueBinary = ('open' == evt.value) ? '1i' : '0i'
        data += ",unit=${unit} value=${value},valueBinary=${valueBinary}"
    }
    // Catch any other event with a string value that hasn't been handled:
    else if (evt.value ==~ /.*[^0-9\.,-].*/) { // match if any characters are not digits, period, comma, or hyphen.
        logger("Found a string value not explicitly handled: Device Name: ${deviceName}, Event Name: ${evt.name}, Value: ${evt.value}", "warn")
        value = '"' + value + '"'
        data += ",unit=${unit} value=${value}"
    }
    // Catch any other general numerical event (carbonDioxide, power, energy, humidity, level, temperature, ultravioletIndex, voltage, etc).
    else {
        data += ",unit=${unit} value=${value}"
    }

    // add event timestamp
    long eventTimestamp = evt?.unixTime * 1e6   // Time is in milliseconds, InfluxDB expects nanoseconds
    data += " ${eventTimestamp}"

    // Queue data for later write to InfluxDB
    queueToInfluxDb(data)
}

/**
 *  softPoll()
 *
 *  Re-queues last value to InfluxDB unless an event has already been seen in the last softPollingInterval.
 *  Also calls LogSystemProperties().
 *
 *  NB: Function name softPoll must be kept for backward compatibility
 **/
def softPoll() {
    logger("Keepalive check", "debug")

    logSystemProperties()

    long timeNow = new Date().time
    long lastTime = timeNow - (state.softPollingInterval * 60000)

    if (accessAllAttributes) {
        // Iterate over each attribute for each device, in each device collection in deviceAttributes:
        state.selectedAttr.each { entry ->
            d = getDeviceObj(entry.key)
            entry.value.each { attr ->
                if (d.hasAttribute(attr) && d.latestState(attr)?.value != null) {
                    if (d.latestState(attr).date.time <= lastTime) {
                        logger("Keep alive for device ${d}, attribute ${attr}", "info")
                        // Send fake event to handleEvent():
                        handleEvent([
                            name: attr,
                            value: d.latestState(attr)?.value,
                            unit: d.latestState(attr)?.unit,
                            device: d,
                            deviceId: d.id,
                            displayName: d.displayName,
                            unixTime: timeNow
                        ])
                    }
                }
            }
        }
    }
    else {
        def devs // temp variable to hold device collection.
        state.deviceAttributes.each { da ->
            devs = settings."${da.devices}"
            if (devs && (da.attributes)) {
                devs.each { d ->
                    da.attributes.each { attr ->
                        if (d.hasAttribute(attr) && d.latestState(attr)?.value != null) {
                            if (d.latestState(attr).date.time <= lastTime) {
                                logger("Keep alive for device ${d}, attribute ${attr}", "info")
                                // Send fake event to handleEvent():
                                handleEvent([
                                    name: attr,
                                    value: d.latestState(attr)?.value,
                                    unit: d.latestState(attr)?.unit,
                                    device: d,
                                    deviceId: d.id,
                                    displayName: d.displayName,
                                    unixTime: timeNow
                                ])
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 *  logSystemProperties()
 *
 *  Generates measurements for hub and location properties.
 **/
private def logSystemProperties() {
    logger("Logging system properties", "debug")

    def locationName = '"' + escapeStringForInfluxDB(location.name) + '"'
    long timeNow = (new Date().time) * 1e6 // Time is in milliseconds, needs to be in nanoseconds when pushed to InfluxDB

    // Location Properties:
    if (prefLogLocationProperties) {
        try {
            def tz = '"' + escapeStringForInfluxDB(location.timeZone.ID.toString()) + '"'
            def mode = '"' + escapeStringForInfluxDB(location.mode) + '"'
            def times = getSunriseAndSunset()
            def srt = '"' + times.sunrise.format("HH:mm", location.timeZone) + '"'
            def sst = '"' + times.sunset.format("HH:mm", location.timeZone) + '"'

            def data = "_heLocation,locationName=${locationName},latitude=${location.latitude},longitude=${location.longitude},timeZone=${tz} mode=${mode},sunriseTime=${srt},sunsetTime=${sst} ${timeNow}"
            queueToInfluxDb(data)
        }
        catch (e) {
            logger("Unable to log Location properties: ${e}", "error")
        }
    }

    // Hub Properties:
    if (prefLogHubProperties) {
        location.hubs.each { h ->
            try {
                def hubName = '"' + escapeStringForInfluxDB(h.name.toString()) + '"'
                def hubIP = '"' + escapeStringForInfluxDB(h.localIP.toString()) + '"'
                def firmwareVersion =  '"' + escapeStringForInfluxDB(h.firmwareVersionString) + '"'

                def data = "_heHub,locationName=${locationName},hubName=${hubName},hubIP=${hubIP} firmwareVersion=${firmwareVersion} ${timeNow}"
                queueToInfluxDb(data)
            }
            catch (e) {
                logger("Unable to log Hub properties: ${e}", "error")
            }
        }
    }
}

/**
 *  queueToInfluxDb()
 *
 *  Adds events to the InfluxDB queue.
 **/
private def queueToInfluxDb(data) {
    loggerQueue = state.loggerQueue
    if (loggerQueue == null) {
        // Failsafe if coming from an old version
        loggerQueue = []
        state.loggerQueue = loggerQueue
    }

    loggerQueue.add(data)
    // NB: prefBatchSizeLimit does not exist in older configurations
    Integer prefBatchSizeLimit = settings.prefBatchSizeLimit ?: 50
    if (loggerQueue.size() >= prefBatchSizeLimit) {
        logger("Maximum queue size reached", "debug")
        writeQueuedDataToInfluxDb()
    }
    else if (loggerQueue.size() == 1) {
        logger("Scheduling batch", "debug")
        // NB: prefBatchTimeLimit does not exist in older configurations
        Integer prefBatchTimeLimit = settings.prefBatchTimeLimit ?: 60
        runIn(prefBatchTimeLimit, writeQueuedDataToInfluxDb)
    }
}

/**
 *  writeQueuedDataToInfluxDb()
 *
 *  Posts data to InfluxDB queue.
 *
 *  NB: Function name writeQueuedDataToInfluxDb must be kept for backward compatibility
**/
def writeQueuedDataToInfluxDb() {
    loggerQueue = state.loggerQueue
    if (loggerQueue == null) {
        // Failsafe if coming from an old version
        return
    }
    if (state.uri == null) {
        // Failsafe if using an old config
        setupDB()
    }

    Integer loggerQueueSize = loggerQueue.size()
    logger("Number of events queued for InfluxDB: ${loggerQueueSize}", "debug")
    if (loggerQueueSize == 0) {
        return
    }

    // NB: older versions will not have state.postCount set
    Integer postCount = state.postCount ?: 0
    Long now = now()
    if (postCount) {
        // A post is already running
        Long elapsed = now - state.lastPost
        logger("Post of size ${postCount} events already running (elapsed ${elapsed}ms)", "debug")

        // Failsafe in case handleInfluxResponse doesn't get called for some reason such as reboot
        if (elapsed > 90000) {
            logger("Post callback failsafe timeout", "debug")
            state.postCount = 0

            // NB: prefBacklogLimit does not exist in older configurations
            Integer prefBacklogLimit = settings.prefBacklogLimit ?: 5000
            if (loggerQueueSize > prefBacklogLimit) {
                logger("Backlog limit exceeded: dropping ${postCount} events (failsafe)", "warn")
                listRemoveCount(loggerQueue, postCount)
            }
        }
        else {
            // Check again
            runIn(15, writeQueuedDataToInfluxDb)
            return
        }
    }

    // NB: prefBatchSizeLimit does not exist in older configurations
    Integer prefBatchSizeLimit = settings.prefBatchSizeLimit ?: 50
    postCount = loggerQueueSize < prefBatchSizeLimit ? loggerQueueSize : prefBatchSizeLimit
    state.postCount = postCount
    state.lastPost = now

    String data = loggerQueue.subList(0, postCount).toArray().join('\n')
    logger("Posting data to InfluxDB: ${state.uri}, Data: [${data}]", "debug")
    try {
        def postParams = [
            uri: state.uri,
            requestContentType: 'application/json',
            contentType: 'application/json',
            headers: state.headers,
            ignoreSSLIssues: settings.prefIgnoreSSLIssues,
            timeout: 60,
            body: data
        ]
        def closure = [ postTime: now ]
        asynchttpPost('handleInfluxResponse', postParams, closure)
    }
    catch (e) {
        logger("Error creating post to InfluxDB: ${e}", "error")
    }
}

/**
 *  handleInfluxResponse()
 *
 *  Handles response from post made in writeQueuedDataToInfluxDb().
 *
 *  NB: Function name handleInfluxResponse must be kept for backward compatibility
 **/
def handleInfluxResponse(hubResponse, closure) {
    loggerQueue = state.loggerQueue
    if (loggerQueue == null) {
        // Failsafe if coming from an old version
        return
    }
    Integer loggerQueueSize = loggerQueue.size()

    // NB: Transitioning from older versions will not have closure
    Double elapsed = (closure) ? (now() - closure.postTime) / 1000 : 0

    // NB: Transitioning from older versions will not have postCount set
    Integer postCount = state.postCount ?: 0
    state.postCount = 0

    if (hubResponse.status >= 400) {
        logger("Post of ${postCount} events failed - elapsed time ${elapsed} seconds - Status: ${hubResponse.status}, Error: ${hubResponse.errorMessage}, Headers: ${hubResponse.headers}, Data: ${data}", "error")

        // NB: prefBacklogLimit does not exist in older configurations
        Integer prefBacklogLimit = settings.prefBacklogLimit ?: 5000
        if (loggerQueueSize > prefBacklogLimit) {
            logger("Backlog limit exceeded: dropping ${postCount} events", "warn")
            listRemoveCount(loggerQueue, postCount)
        }
        // Try again
        runIn(60, writeQueuedDataToInfluxDb)
    }
    else {
        logger("Post of ${postCount} events complete - elapsed time ${elapsed} seconds - Status: ${hubResponse.status}", "info")
        listRemoveCount(loggerQueue, postCount)
        if (loggerQueueSize) {
            // More to do
            runIn(1, writeQueuedDataToInfluxDb)
        }
    }
}

/**
 *  listRemoveCount()
 *
 *  Remove count items from the beginning of a list.
 **/
private listRemoveCount(list, count) {
    count.times {
        list.remove(0)
    }
}

/**
 *  setupDB()
 *
 *  Set up the database uri and header state variables.
 **/
private setupDB() {
    String uri
    def headers = [:]

    if (settings?.prefDatabaseTls) {
        uri = "https://"
    }
    else {
        uri = "http://"
    }

    uri += settings.prefDatabaseHost
    if (settings?.prefDatabasePort) {
        uri += ":" + settings.prefDatabasePort
    }

    if (settings?.prefInfluxVer == "2") {
        uri += "/api/v2/write?org=${settings.prefOrg}&bucket=${settings.prefBucket}"
    }
    else {
        // Influx version 1
        uri += "/write?db=${settings.prefDatabaseName}"
    }

    if (settings.prefAuthType == null || settings.prefAuthType == "basic") {
        if (settings.prefDatabaseUser && settings.prefDatabasePass) {
            def userpass = "${settings.prefDatabaseUser}:${settings.prefDatabasePass}"
            headers.put("Authorization", "Basic " + userpass.bytes.encodeBase64().toString())
        }
    }
    else if (settings.prefAuthType == "token") {
        headers.put("Authorization", "Token ${settings.prefDatabaseToken}")
    }

    state.uri = uri
    state.headers = headers

    logger("InfluxDB URI: ${uri}", "info")

    // Clean up old state vars if present
    state.remove("databaseHost")
    state.remove("databasePort")
    state.remove("databaseName")
    state.remove("databasePass")
    state.remove("databaseUser")
    state.remove("path")
}

/**
 *  manageSubscriptions()
 *
 *  Configures subscriptions.
 **/
private manageSubscriptions() {
    logger("Establishing subscriptions", "debug")

    // Unsubscribe:
    unsubscribe()

    // Subscribe to mode events:
    if (prefLogModeEvents) {
        subscribe(location, "mode", handleModeEvent)
    }

    if (accessAllAttributes) {
        state.selectedAttr.each { entry ->
            d = getDeviceObj(entry.key)
            entry.value.each { attr ->
                logger("Subscribing to attribute: ${attr}, for device: ${d}", "info")
                subscribe(d, attr, handleEvent, ["filterEvents": filterEvents])
            }
        }
    }
    else {
        // Subscribe to device attributes (iterate over each attribute for each device collection in state.deviceAttributes):
        def devs // dynamic variable holding device collection.
        state.deviceAttributes.each { da ->
            devs = settings."${da.devices}"
            if (devs && (da.attributes)) {
                da.attributes.each { attr ->
                    logger("Subscribing to attribute: ${attr}, for devices: ${da.devices}", "info")
                    // There is no need to check if all devices in the collection have the attribute.
                    subscribe(devs, attr, handleEvent, ["filterEvents": filterEvents])
                }
            }
        }
    }
}

/**
 *  logger()
 *
 *  Wrapper function for all logging.
 **/
private logger(msg, level = "debug") {
    switch (level) {
        case "error":
            if (state.loggingLevelIDE >= 1) log.error msg
            break
        case "warn":
            if (state.loggingLevelIDE >= 2) log.warn msg
            break
        case "info":
            if (state.loggingLevelIDE >= 3) log.info msg
            break
        case "debug":
            if (state.loggingLevelIDE >= 4) log.debug msg
            break
        default:
            log.debug msg
            break
    }
}

/**
 *  escapeStringForInfluxDB()
 *
 *  Escape values to InfluxDB.
 *
 *  If a tag key, tag value, or field key contains a space, comma, or an equals sign = it must
 *  be escaped using the backslash character \. Backslash characters do not need to be escaped.
 *  Commas and spaces will also need to be escaped for measurements, though equals signs = do not.
 *
 *  Further info: https://docs.influxdata.com/influxdb/v0.10/write_protocols/write_syntax/
 **/
private String escapeStringForInfluxDB(String str) {
    if (str) {
        str = str.replaceAll(" ", "\\\\ ") // Escape spaces.
        str = str.replaceAll(",", "\\\\,") // Escape commas.
        str = str.replaceAll("=", "\\\\=") // Escape equal signs.
        str = str.replaceAll("\"", "\\\\\"") // Escape double quotes.
        //str = str.replaceAll("'", "_")  // Replace apostrophes with underscores.
    }
    else {
        str = 'null'
    }
    return str
}


private getLoggerQueue() {
    defaultQueue = new java.util.concurrent.ConcurrentLinkedQueue()
    queue = loggerQueueMap.putIfAbsent(app.getId(), defaultQueue)
    if (queue == null) {
        // key was not in map - return defaultQueue
        logger("allocating new queue for app","warn")
        return defaultQueue
    }
    // key was already in map - return that.
    return queue
}

// Attempt to clean up the ConcurrentLinkedQueue object.
// Only called by uninstalled(), so should be safe.
private releaseLoggerQueue()
{
    // Flush queue just before we release it,
    writeQueuedDataToInfluxDb()
    loggerQueueMap.remove(app.getId())
    logger("released queue for app id ${app.getId()}", "info")
}
