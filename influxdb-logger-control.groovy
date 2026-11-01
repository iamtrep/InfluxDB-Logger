/*****************************************************************************************************************
 *  Source: https://github.com/HubitatCommunity/InfluxDB-Logger
 *
 *  Control device for an InfluxDB Logger instance: hold posting, stop capture, and report how the logger is doing.
 *  The app creates it. The switch is the hold control only, so a dashboard tile can't drop data by accident.
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
 *****************************************************************************************************************/

metadata {
    definition(name: "InfluxDB Logger Control", namespace: "nowhereville", author: "Hubitat Community") {
        capability "Actuator"
        capability "Switch"

        command "holdPosting"
        command "resumePosting"
        command "stopCapture"
        command "resumeCapture"
        command "flushNow"

        attribute "posting", "enum", ["active", "held", "outage", "replaying"]
        attribute "capture", "enum", ["active", "stopped"]
        attribute "spooledEvents", "number"
        attribute "oldestEventAge", "number"
        attribute "droppedEvents", "number"
        attribute "lastSuccess", "string"
    }
}

void on() {
    parent.controlCommand("resumePosting")
}

void off() {
    parent.controlCommand("holdPosting")
}

void holdPosting() {
    parent.controlCommand("holdPosting")
}

void resumePosting() {
    parent.controlCommand("resumePosting")
}

void stopCapture() {
    parent.controlCommand("stopCapture")
}

void resumeCapture() {
    parent.controlCommand("resumeCapture")
}

void flushNow() {
    parent.controlCommand("flushNow")
}

/**
 *  setStatus()
 *
 *  Called by the app.
 **/
void setStatus(Map status) {
    sendEvent(name: "switch", value: status.posting == "held" ? "off" : "on")
    sendEvent(name: "posting", value: status.posting)
    sendEvent(name: "capture", value: status.capture)
    sendEvent(name: "spooledEvents", value: status.spooledEvents, unit: "events")
    sendEvent(name: "oldestEventAge", value: status.oldestEventAge, unit: "minutes")
    sendEvent(name: "droppedEvents", value: status.droppedEvents, unit: "events")
    sendEvent(name: "lastSuccess", value: status.lastSuccess)
}
