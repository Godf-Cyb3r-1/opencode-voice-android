import { test } from 'node:test';
import assert from 'node:assert/strict';
import { requestTurn } from './turn-request.ts';
const snapshot={transcript:'hello',speaking:false,assistantSpeaking:false,assistantReply:''};
test('uses exact active session without a model override or discovery',async()=>{
 let calls=0;
 globalThis.fetch=async(url,options)=>{calls++;assert.equal(url,'/api/session/ses_active/generate');const body=JSON.parse(options.body);assert.equal(body.model,undefined);assert.ok(body.prompt.includes('"transcript":"hello"'));return Response.json({data:{text:'RESPOND'}});};
 assert.equal(await requestTurn(snapshot,new AbortController().signal,null,'ses_active'),'RESPOND');assert.equal(calls,1);
});
test('normal Jarvis expression prefix does not invalidate decision',async()=>{
 globalThis.fetch=async()=>Response.json({data:{text:'[confirmation-en] LISTEN.'}});
 assert.equal(await requestTurn(snapshot,new AbortController().signal,null,'ses_active'),'LISTEN');
});
test('arbitrary reply cannot trigger an action',async()=>{
 globalThis.fetch=async()=>Response.json({data:{text:'I think RESPOND'}});
 await assert.rejects(requestTurn(snapshot,new AbortController().signal,null,'ses_active'),/invalid turn decision/);
});
test('new speech abort rejects even a late successful response',async()=>{
 const abort=new AbortController();globalThis.fetch=async()=>{abort.abort();return Response.json({data:{text:'RESPOND'}});};
 await assert.rejects(requestTurn(snapshot,abort.signal,null,'ses_active'));
});
test('missing session status retained for recreation',async()=>{
 globalThis.fetch=async()=>Response.json({message:'Session missing'},{status:404});
 await assert.rejects(requestTurn(snapshot,new AbortController().signal,null,'ses_active'),error=>error.status===404);
});
