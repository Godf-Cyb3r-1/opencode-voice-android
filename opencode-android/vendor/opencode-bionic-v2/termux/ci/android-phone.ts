export * as AndroidPhone from "./android-phone.js"
import { ToolFailure } from "@opencode/ai/effect"
import { define } from "@opencode/plugin/effect/plugin"
import { Effect, Schema, Semaphore } from "effect"
import { Session } from "../session.js"
import { KV } from "../kv.js"
import type { Tool } from "@opencode/schema/tool"
import { completed, describe, expand, parameterize, resolve, screenOf, selectorFor, type Recipe, type Screen, type Step } from "./android-workflows.js"

const Selector = Schema.Struct({viewId:Schema.optional(Schema.String),text:Schema.optional(Schema.String),description:Schema.optional(Schema.String)})
const Fields = {
  action: Schema.Literals(["status","contacts","apps","open","screen","ui","back","home","whatsapp_send","call","notifications","notification_reply","audit"]),
  query:Schema.optional(Schema.String), packageName:Schema.optional(Schema.String),
  node:Schema.optional(Schema.String), selector:Schema.optional(Selector),
  operation:Schema.optional(Schema.Literals(["click","set_text","scroll_forward","scroll_backward"])),
  text:Schema.optional(Schema.String),contact:Schema.optional(Schema.String),contactId:Schema.optional(Schema.String),
  phoneNumber:Schema.optional(Schema.String),replyRef:Schema.optional(Schema.String),description:Schema.optional(Schema.String),
}
const PhoneInput=Schema.Struct(Fields)
const WorkflowInput=Schema.Struct({
  action:Schema.Literals(["list","run","sequence","remember","delete"]),
  name:Schema.optional(Schema.String),description:Schema.optional(Schema.String),
  parameters:Schema.optional(Schema.Record(Schema.String,Schema.String)),
  steps:Schema.optional(Schema.Array(PhoneInput)),
  repetitions:Schema.optional(Schema.Number),
})
type Input=typeof PhoneInput.Type

export const Plugin=define({
  id:"opencode.android.phone",
  effect:Effect.fn("AndroidPhone.Plugin")(function*(ctx){
    if(!process.env.JARVIS_ANDROID_URL || !process.env.JARVIS_ANDROID_TOKEN)return
    const sessions=yield* Session.Service
    const kv=yield* KV.Service
    const device=yield* Semaphore.make(1)
    const screens=new Map<string,Screen>()
    const traces=new Map<string,{source:string;steps:Step[];replayable:boolean}>()
    // The existing OpenCode permission store provides persistent Always allow and revocation in Settings.
    yield* ctx.agent.transform(editor=>{
      for(const item of editor.list())editor.update(item.id,agent=>{
        agent.permissions.push({action:"android.debug.*",resource:"*",effect:"ask"})
        if(agent.id==="jarvis")for(const name of ["phone","phone_workflow","send_whatsapp_message"])
          agent.permissions.push({action:name,resource:"*",effect:"allow"})
      })
    })
    const approval=(context:Pick<Tool.Context,"sessionID"|"agent"|"messageID"|"id">,action:string,resource:string,description:string)=>ctx.permission.assert({
      sessionID:context.sessionID,agent:context.agent,action,resources:[resource],save:[resource],
      source:{type:"tool",messageID:context.messageID,id:context.id},
      metadata:{description,saveDescription:`Always allow ${resource} in this OpenCode project. Manage saved approvals in Settings → Permissions.`},
    })
    // Show debug approvals for controller/worker actions too. Phone recipes prompt each contained action instead.
    yield* ctx.tool.hook("execute.before",event=>{
      if(["phone","phone_workflow","send_whatsapp_message"].includes(event.tool))return Effect.void
      return approval(event,"android.debug.tool",event.tool,`Run ${event.tool}: ${JSON.stringify(event.input)}`)
    })
    const owner=(context:Tool.Context)=>Effect.gen(function*(){
      const session=yield* sessions.get(context.sessionID)
      if(context.agent!=="jarvis" || session.agent!=="jarvis")
        return yield* new ToolFailure({message:"Phone execution belongs to the Jarvis controller. Delegate independent research/planning to workers; keep one phone executor."})
      const messages=yield* sessions.messages({sessionID:context.sessionID,type:"user",order:"desc",limit:1})
      const source=`${context.sessionID}:${messages[0]?.id??context.messageID}`
      if(traces.get(context.sessionID)?.source!==source)traces.set(context.sessionID,{source,steps:[],replayable:true})
      return {source,projectID:session.projectID}
    })
    const transport=(input:Input,source:string)=>Effect.tryPromise({
      try:async(signal)=>{
        const response=await fetch(`${process.env.JARVIS_ANDROID_URL}/v1/action`,{
          method:"POST",headers:{"Content-Type":"application/json",Authorization:`Bearer ${process.env.JARVIS_ANDROID_TOKEN}`},
          body:JSON.stringify({...input,source}),signal:AbortSignal.any([signal,AbortSignal.timeout(65000)]),
        })
        if(!response.ok)throw new Error(`Native phone bridge HTTP ${response.status}`)
        return await response.json() as any
      },catch:error=>new ToolFailure({message:"Phone outcome not confirmed. Inspect status/audit/current screen before retrying a possible side effect.",error}),
    })
    const key=(projectID:string,name:string)=>`jarvis:phone-workflow:${projectID}:${encodeURIComponent(name)}`
    const save=(projectID:string,recipe:Recipe)=>kv.set(key(projectID,recipe.name),JSON.parse(JSON.stringify(recipe)))
    const step=(input:Input,context:Tool.Context,source:string)=>Effect.gen(function*(){
      const started=Date.now()
      let prepared:Input={...input}
      let selector=input.selector
      let before=screens.get(context.sessionID)
      if(input.action==="ui" && !selector && before && input.node){
        try{selector=selectorFor(before,input.node)}catch{}
      }
      const pkg=input.packageName??before?.package
      if(input.action==="ui")prepared={...prepared,packageName:pkg,selector}
      const resource=[prepared.packageName??"phone",input.action,input.operation??""].filter(Boolean).join(":")
      yield* approval(context,"android.debug.phone",resource,describe({...prepared,selector}))
      // Resolve after approval: returning to Jarvis to tap Allow changes focus and invalidates old refs.
      if(input.action==="ui" && selector){
        const observation=yield* transport({action:"screen",packageName:pkg},source)
        before=screenOf(observation)
        if(!before || (pkg && before.package!==pkg))return {status:"failed",error:"Target screen unavailable after approval",observation}
        screens.set(context.sessionID,before)
        let node:string
        try{node=resolve(before,selector).node}catch(error){return {status:"failed",error:String(error),screen:before}}
        prepared={...prepared,node}
      }
      const result=yield* transport(prepared,source)
      const screen=screenOf(result)
      if(screen)screens.set(context.sessionID,screen)
      const trace=traces.get(context.sessionID)!
      if(completed(result) && !["status","screen","contacts","apps","audit","notifications"].includes(input.action)){
        if(input.action==="ui" && !selector)trace.replayable=false
        if(input.action==="notification_reply" || input.action==="home")trace.replayable=false
        const {node,replyRef,...stable}=prepared
        trace.steps.push({...stable,selector})
      }else if(!completed(result)){
        // A failed attempt can have changed app state; don't silently learn a partial route.
        trace.replayable=false
      }
      const elapsedMs=Date.now()-started
      yield* context.progress({action:input.action,elapsedMs,status:result.status??"observed"})
      return {...result,elapsedMs}
    })
    const execute=(input:Input,context:Tool.Context)=>device.withPermit(Effect.gen(function*(){
      const {source,projectID}=yield* owner(context)
      const result=yield* step(input,context,source)
      if(input.action==="whatsapp_send" && result.status==="submitted_to_whatsapp"){
        const recipe:Recipe={name:"whatsapp-message",description:"Send an exact message to a saved WhatsApp contact",steps:[{action:"whatsapp_send",contact:"${contact}",text:"${message}",packageName:input.packageName}],learnedAt:Date.now(),runs:0}
        yield* save(projectID,recipe)
        result.learnedWorkflow=recipe.name
      }
      const output=JSON.stringify(result)
      return {output,content:output}
    })).pipe(Effect.mapError(error=>error instanceof ToolFailure?error:new ToolFailure({message:"Phone action failed",error})))
    const workflow=(input:typeof WorkflowInput.Type,context:Tool.Context)=>Effect.gen(function*(){
      const {projectID,source}=yield* owner(context)
      const name=input.name?.trim()
      if(input.action==="list"){
        yield* approval(context,"android.debug.workflow","list","List saved phone workflows and their parameters")
        const recipes:Recipe[]=[]
        let after:string|undefined
        do{
          const page=yield* kv.scan({prefix:`jarvis:phone-workflow:${projectID}:`,after})
          for(const entry of page.entries)recipes.push(entry.value as unknown as Recipe)
          after=page.next
        }while(after)
        return JSON.stringify({workflows:recipes.map(r=>({...r,steps:r.steps,requiredParameters:[...new Set(JSON.stringify(r.steps).match(/\$\{[a-zA-Z][a-zA-Z0-9_]*\}/g)??[])]}))})
      }
      if(input.action!=="sequence" && !name)return yield* new ToolFailure({message:"Workflow name required"})
      if(input.action==="delete"){
        yield* approval(context,"android.debug.workflow",`delete:${name}`,`Delete saved workflow ${name}`)
        yield* kv.remove(key(projectID,name!));return JSON.stringify({status:"deleted",name})
      }
      if(input.action==="remember"){
        return yield* device.withPermit(Effect.gen(function*(){
          const trace=traces.get(context.sessionID)!
          if(!trace.replayable || !trace.steps.length)return JSON.stringify({status:"not_saved",reason:"No complete replayable successful trace in this user turn. Recover the flow, then learn a clean successful run."})
          const recipe:Recipe={name:name!,description:input.description??name!,steps:parameterize(trace.steps,input.parameters??{}),learnedAt:Date.now(),runs:0}
          yield* approval(context,"android.debug.workflow",`remember:${name}`,`Save reusable workflow ${name}: ${recipe.description}. Steps: ${JSON.stringify(recipe.steps)}`)
          yield* save(projectID,recipe)
          return JSON.stringify({status:"saved",name,steps:recipe.steps.length})
        }))
      }
      const stored=input.action==="run" ? yield* kv.get(key(projectID,name!)) : undefined
      const recipe=stored as unknown as Recipe|undefined
      if(input.action==="run" && !recipe)return JSON.stringify({status:"not_found",name})
      let steps:Input[]
      try{steps=Schema.decodeUnknownSync(Schema.Array(PhoneInput))(expand(recipe?.steps??input.steps??[],input.parameters??{})) as Input[]}
      catch(error){return JSON.stringify({status:"invalid_workflow",error:String(error)})}
      if(!steps.length)return JSON.stringify({status:"invalid_workflow",error:"No steps"})
      const repetitions=input.repetitions??1
      if(!Number.isSafeInteger(repetitions)||repetitions<1)return JSON.stringify({status:"invalid_workflow",error:"repetitions must be a positive integer"})
      // One executor holds the device across the sequence, including permission waits.
      return yield* device.withPermit(Effect.gen(function*(){
        const results:any[]=[]
        for(let iteration=0;iteration<repetitions;iteration++)for(let index=0;index<steps.length;index++){
          const result=yield* step(steps[index]!,context,`${source}:workflow:${iteration}:${index}`)
          results.push({iteration:iteration+1,step:index+1,status:result.status??"observed",elapsedMs:result.elapsedMs})
          yield* context.progress({workflow:name??"sequence",iteration:iteration+1,step:index+1,total:steps.length})
          if(!completed(result))return JSON.stringify({status:"needs_repair",completedSteps:results.slice(0,-1),iteration:iteration+1,step:index+1,result,note:"Earlier steps already executed. Repair from this observed state; do not restart and duplicate earlier side effects."})
        }
        if(recipe)yield* save(projectID,{...recipe,runs:recipe.runs+1})
        return JSON.stringify({status:"sequence_completed",name,results,screen:screens.get(context.sessionID),note:"Actions finished; assess the observed final state before claiming the user's task succeeded."})
      }))
    })
    yield* ctx.tool.transform(editor=>{
      editor.add({name:"phone",options:{codemode:false},description:"Native Android actions: status, contacts(query), apps, open(packageName), screen, ui(node or stable selector,operation,text), back/home, call(contact), notifications, notification_reply and audit. UI results include the resulting screen; reuse it. Debug approval shows the exact action with Allow once/Always allow. Prefer send_whatsapp_message for messages; phone_workflow for reusable or multi-step sequences.",input:PhoneInput,output:Schema.String,execute})
      editor.add({name:"send_whatsapp_message",options:{codemode:false},description:"Send the exact message to a saved contact in ONE native operation, resolving recipient and backing up old drafts. Verifies visible submission, not delivery. Debug approval before sending. A successful first send automatically saves parameterized whatsapp-message workflow.",input:Schema.Struct({contact:Schema.String,message:Schema.String,contactId:Schema.optional(Schema.String),phoneNumber:Schema.optional(Schema.String),packageName:Schema.optional(Schema.Literals(["com.whatsapp","com.whatsapp.w4b"]))}),output:Schema.String,execute:(input,context)=>execute({action:"whatsapp_send",contact:input.contact,text:input.message,contactId:input.contactId,phoneNumber:input.phoneNumber,packageName:input.packageName},context)})
      editor.add({name:"phone_workflow",options:{codemode:false},description:"Reusable local phone toolkit. list discovers learned workflows. run(name,parameters,repetitions) replays without a model call between steps. sequence(steps,repetitions) executes a planned series; UI uses unique stable selector {viewId,text,description}, never stored node refs. Every action still uses OpenCode debug approval. remember(name,description,parameters) learns the current successful user-turn trace; parameter map is name→observed literal value, replaced with ${name} for later runs. Call automatically after verified task success. delete removes a recipe. Returns failing step and actual state for iterative repair; never replays completed side effects automatically. No arbitrary code or new MCP server installation required.",input:WorkflowInput,output:Schema.String,execute:(input,context)=>workflow(input,context).pipe(Effect.map(output=>({output,content:output})),Effect.mapError(error=>error instanceof ToolFailure?error:new ToolFailure({message:"Phone workflow failed",error})))})
    })
  }),
})
