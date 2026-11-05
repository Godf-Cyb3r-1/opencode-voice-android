export * as AndroidPhone from "./android-phone.js"

import { ToolFailure } from "@opencode/ai/effect"
import { define } from "@opencode/plugin/effect/plugin"
import { Effect, Schema } from "effect"
import { Session } from "../session.js"
import type { Tool } from "@opencode/schema/tool"



const PhoneInput = Schema.Struct({
        action: Schema.Literals(["status", "contacts", "apps", "open", "screen", "ui", "back", "home", "whatsapp_send", "call", "notifications", "notification_reply", "audit"]),
        query: Schema.optional(Schema.String),
        packageName: Schema.optional(Schema.String),
        node: Schema.optional(Schema.String),
        operation: Schema.optional(Schema.Literals(["click", "set_text", "scroll_forward", "scroll_backward"])),
        text: Schema.optional(Schema.String),
        contact: Schema.optional(Schema.String),
        contactId: Schema.optional(Schema.String),
        phoneNumber: Schema.optional(Schema.String),
        replyRef: Schema.optional(Schema.String),
      })

export const Plugin = define({
  id: "opencode.android.phone",
  effect: Effect.fn("AndroidPhone.Plugin")(function* (ctx) {
    if (!process.env.JARVIS_ANDROID_URL || !process.env.JARVIS_ANDROID_TOKEN) return
    const sessions = yield* Session.Service
    const execute = (input: typeof PhoneInput.Type, context: Tool.Context) => Effect.gen(function* () {
        if (context.agent !== "jarvis" || (yield* sessions.get(context.sessionID)).agent !== "jarvis")
          return yield* new ToolFailure({ message: "Phone tools are only available to the Jarvis assistant session." })
        const messages = yield* sessions.messages({ sessionID: context.sessionID, type: "user", order: "desc", limit: 1 })
        const source = `${context.sessionID}:${messages[0]?.id ?? context.messageID}`
        const output = yield* Effect.tryPromise({
          try: async (signal) => {
            const response = await fetch(`${process.env.JARVIS_ANDROID_URL}/v1/action`, {
              method: "POST",
              headers: { "Content-Type": "application/json", Authorization: `Bearer ${process.env.JARVIS_ANDROID_TOKEN}` },
              body: JSON.stringify({ ...input, source }),
              signal: AbortSignal.any([signal, AbortSignal.timeout(65000)]),
            })
            if (!response.ok) throw new Error(`Native phone bridge returned HTTP ${response.status}`)
            return await response.text()
          },
          catch: (error) => new ToolFailure({ message: "Phone outcome was not confirmed. Inspect status/audit or the current screen and recover the user's requested task; do not claim completion without evidence.", error }),
        })
        return { output, content: output }
      }).pipe(Effect.mapError((error) => error instanceof ToolFailure ? error : new ToolFailure({ message: "Native phone action failed", error })))
    yield* ctx.tool.transform((editor) => {
      editor.add({
      name: "phone",
      options: { codemode: false },
      description: "Native Android assistant actions. status: permissions/capabilities. contacts(query): find saved names and numbers. apps: list launchable apps. open(packageName): launch app. screen: target app UI with fresh node refs and progressKey. ui actions return the resulting screen, so use those fresh refs instead of an extra screen call. ui(node,operation,text): click, set_text, scroll_forward or scroll_backward. back/home: navigate. Prefer send_whatsapp_message for WhatsApp. UI controls remain available for recovery and other explicitly requested interactions. call(contact): make an explicitly requested phone call. notifications: list current notifications and reply refs. notification_reply(replyRef,text): reply to the exact selected notification. audit: recent action outcomes without message content. Phone permissions must be enabled once by the user. Never claim delivered based only on submission.",
      input: PhoneInput,
      output: Schema.String,
      execute,

      })
      editor.add({
        name: "send_whatsapp_message",
        options: { codemode: false },
        description: "Send the user's exact message to a saved WhatsApp contact in ONE native operation. Resolves contacts, opens the number-specific conversation, verifies its header, backs up any existing draft, enters only the requested text, presses Send once and checks the visible outcome. Use this directly for messaging; do not navigate WhatsApp with generic screen/ui tools. If the operation fails before sending, inspect the actual result and recover the task. Native send receipts prevent accidentally submitting the same send twice.",
        input: Schema.Struct({ contact: Schema.String, message: Schema.String, contactId: Schema.optional(Schema.String), phoneNumber: Schema.optional(Schema.String), packageName: Schema.optional(Schema.Literals(["com.whatsapp", "com.whatsapp.w4b"])) }),
        output: Schema.String,
        execute: (input, context) => execute({ action: "whatsapp_send", contact: input.contact, text: input.message, contactId: input.contactId, phoneNumber: input.phoneNumber, packageName: input.packageName }, context),
      })
    })
  }),
})
