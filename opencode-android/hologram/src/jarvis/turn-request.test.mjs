import { test } from 'node:test';
import assert from 'node:assert/strict';
import { requestTurn } from './turn-request.ts';
const snapshot = { transcript: 'hello', speaking: false, assistantSpeaking: false, assistantReply: '' };
test('temporary 502 recovers once without changing transcript', async () => {
  let calls = 0;
  globalThis.fetch = async (_, options) => {
    assert.equal(JSON.parse(JSON.parse(options.body).prompt).transcript, 'hello');
    return ++calls === 1 ? Response.json({reason:'overloaded',message:'Busy'}, {status:502}) : Response.json({data:{text:'RESPOND'}});
  };
  assert.equal(await requestTurn(snapshot, new AbortController().signal), 'RESPOND');
  assert.equal(calls, 2);
});
test('provider rate limit inside HTTP 502 is not retried and reason survives', async () => {
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json({reason:'rate_limit', message:'Free usage limit reached'}, {status:502}); };
  await assert.rejects(requestTurn(snapshot, new AbortController().signal), /Free usage limit reached.*rate_limit/);
  assert.equal(calls, 1);
});
test('new speech cancels retry without a second request', async () => {
  let calls = 0; const abort = new AbortController();
  globalThis.fetch = async () => { calls++; setTimeout(() => abort.abort(), 10); return new Response('', {status:503}); };
  await assert.rejects(requestTurn(snapshot, abort.signal));
  assert.equal(calls, 1);
});
test('persistent transient failure stops after two attempts', async () => {
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json({message:'Busy',reason:'overloaded'}, {status:502}); };
  await assert.rejects(requestTurn(snapshot, new AbortController().signal), /Busy.*pending/);
  assert.equal(calls, 2);
});
