export class PhoneTurn {
  uiCalls = 0
  sendDone = false
  senderFailed = false
  get uiStopped() { return this.uiCalls >= 6 || this.senderFailed }
  admit(action: string) {
    if (action === "whatsapp_send") {
      if (this.sendDone) return false
      this.sendDone = true
      return true
    }
    if (["screen", "ui", "open", "back", "home"].includes(action)) {
      if (this.uiStopped) return false
      this.uiCalls++
    }
    return true
  }
  settleSend(status: string) { this.senderFailed = status !== "submitted_to_whatsapp" }
}
