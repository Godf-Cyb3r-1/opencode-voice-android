export class PhoneTurn {
  sendDone = false
  senderFailed = false
  private screen = ""
  private unchanged = 0
  private tried = new Set<string>()
  stalled = false
  admit(action: string, target = "") {
    if (action === "whatsapp_send") {
      if (this.sendDone) return false
      this.sendDone = true
    }
    // Recovery and inspection remain available; only repeat mutation of a stuck screen stops.
    if (action === "ui") {
      if (this.senderFailed || (this.stalled && this.tried.has(target))) return false
      this.tried.add(target)
    }
    return true
  }
  observe(progressKey?: string) {
    if (!progressKey) return
    if (progressKey !== this.screen) {
      this.screen = progressKey
      this.unchanged = 0
      this.stalled = false
      this.tried.clear()
      return
    }
    this.unchanged++
    this.stalled = this.unchanged >= 4
  }
  settleSend(status: string) { this.senderFailed = status !== "submitted_to_whatsapp" }
}
