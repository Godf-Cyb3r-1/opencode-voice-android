import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createTurnRequester } from './turn-request.ts';
const snapshot = { transcript:'hello', speaking:false, assistantSpeaking:false, assistantReply:'' };
const model = id => ({id,providerID:'opencode',enabled:true,status:'active',capabilities:{input:['text'],output:['text']},cost:[{input:0,output:0,cache:{read:0,write:0}}]});
const catalog = ['glm-5.3-flash-free','mimo-v2.6-flash-free','big-pickle','flint-40b-free'].map(model);
test('quota switches model; successful model is reused; prompt preserved', async()=>{
 const request=createTurnRequester();const calls=[];
 globalThis.fetch=async(url,options)=>{
  if(url==='/api/model')return Response.json({data:catalog});
  const body=JSON.parse(options.body);calls.push(body.model.id);assert.equal(JSON.parse(body.prompt).transcript,'hello');
  return body.model.id.startsWith('glm')?Response.json({reason:'rate_limit',message:'Quota'}, {status:502}):Response.json({data:{text:'RESPOND'}});
 };
 assert.equal(await request(snapshot,new AbortController().signal),'RESPOND');
 assert.equal(await request(snapshot,new AbortController().signal),'RESPOND');
 assert.deepEqual(calls,['glm-5.3-flash-free','mimo-v2.6-flash-free','mimo-v2.6-flash-free']);
});
test('invalid decision switches; paid and disabled models excluded',async()=>{
 const request=createTurnRequester();let calls=[];
 globalThis.fetch=async(url,options)=>{
  if(url==='/api/model')return Response.json({data:[{...model('paid'),cost:[{input:1,output:1,cache:{read:0,write:0}}]},{...model('disabled'),enabled:false},...catalog]});
  const id=JSON.parse(options.body).model.id;calls.push(id);
  return Response.json({data:{text:calls.length===1?'I think RESPOND perhaps':'LISTEN.'}});
 };
 assert.equal(await request(snapshot,new AbortController().signal),'LISTEN');
 assert.deepEqual(calls,['glm-5.3-flash-free','mimo-v2.6-flash-free']);
});
test('aborted speech does not switch or poison model health',async()=>{
 const request=createTurnRequester();const abort=new AbortController();let calls=0;
 globalThis.fetch=async(url)=>{if(url==='/api/model')return Response.json({data:catalog});calls++;abort.abort();return Response.json({reason:'rate_limit'},{status:502});};
 await assert.rejects(request(snapshot,abort.signal));assert.equal(calls,1);
});
test('all unavailable stops after three models and observes cooldown',async()=>{
 const request=createTurnRequester();let calls=0;
 globalThis.fetch=async(url)=>{if(url==='/api/model')return Response.json({data:catalog.slice(0,3)});calls++;return Response.json({reason:'rate_limit',message:'Quota'},{status:502});};
 await assert.rejects(request(snapshot,new AbortController().signal),/No controller succeeded/);assert.equal(calls,3);
 await assert.rejects(request(snapshot,new AbortController().signal),/cooling down/);assert.equal(calls,3);
});
test('local auth failure stops without model cycling',async()=>{
 const request=createTurnRequester();let calls=0;
 globalThis.fetch=async(url)=>{if(url==='/api/model')return Response.json({data:catalog});calls++;return Response.json({message:'Unauthorized'},{status:401});};
 await assert.rejects(request(snapshot,new AbortController().signal),/Unauthorized/);assert.equal(calls,1);
});
