import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DuplexStore, completedTurn, textOnly } from '../core.mjs';
import { createBridge } from '../server.mjs';
import { attachSession } from '../index.mjs';

const config = { sessionId: 'session-a', binding: 'binding-a', world: 'world-a', player: 'player-a', maid: 'maid-a', token: 'test-only-token-abcdefghijklmnopqrstuvwxyz' };
const message = (id, text, role = 'user') => ({ id, role, text });
const block = text => ({ type: 'text', text });
const end = { type: 'turn/end', seq: 9, data: { turn: 1, reason: { kind: 'completed' } } };
const events = [
  { type: 'turn/start', seq: 0, data: { turn: 1 } },
  { type: 'user/message', seq: 1, data: { source: { kind: 'user' }, content: [block('hello')] } },
  { type: 'user/message', seq: 2, data: { source: { kind: 'plugin' }, content: [block('synthetic')] } },
  { type: 'assistant/message', seq: 3, data: { message: { content: [block('working'), { type: 'tool-call', name: 'shell' }] } } },
  { type: 'tool/result', seq: 4, data: { message: { content: [block('secret tool result')] } } },
  { type: 'assistant/message', seq: 8, data: { message: { content: [{ type: 'reasoning', text: 'private' }, block('done')] } } }, end,
];

test('exports human text and final reply only', () => {
  assert.deepEqual(completedTurn(events, end), [message('1', 'hello'), message('8', 'done', 'assistant')]);
  assert.equal(textOnly([{ type: 'reasoning', text: 'private' }]), '');
});
test('failed, interrupted and tool-ending turns never export assistant output', () => {
  assert.deepEqual(completedTurn(events, { ...end, data: { turn: 1, reason: { kind: 'error' } } }), []);
  const toolEnd = [...events.slice(0, 5), { type: 'assistant/message', seq: 8, data: { message: { content: [{ type: 'tool-call' }] } } }, end];
  assert.deepEqual(completedTurn(toolEnd, end), [message('1', 'hello')]);
  const interrupted = [...events.slice(0, 5), { type: 'assistant/message', seq: 8, data: { interrupted: true, message: { content: [block('partial')] } } }, end];
  assert.deepEqual(completedTurn(interrupted, end), [message('1', 'hello')]);
});
test('scope, token and missing binding rejected', () => {
  const s = new DuplexStore(config);
  assert.equal(s.authorize(`Bearer ${config.token}`, config), true);
  for (const k of ['binding', 'world', 'player', 'maid']) assert.equal(s.authorize(`Bearer ${config.token}`, { ...config, [k]: 'other' }), false);
  assert.equal(s.authorize('Bearer invalid', config), false);
  assert.throws(() => new DuplexStore({ ...config, sessionId: '' }));
});
test('directional context does not re-export imported data or tools', () => {
  const s = new DuplexStore(config);
  s.append('mc', { ...message('1', 'MC'), tool_calls: [{ name: 'attack' }] });
  s.append('dsh', message('1', 'DSH', 'assistant'));
  assert.match(s.contextFor('mc'), /DSH/); assert.doesNotMatch(s.contextFor('mc'), /"text":"MC"|attack/);
  assert.match(s.contextFor('dsh'), /"text":"MC"/); assert.doesNotMatch(s.contextFor('dsh'), /DSH|attack/);
  assert.throws(() => s.append('mc', { ...message('2', 'x'), role: 'tool' }));
});
test('retry IDs deduplicate and conflicting payload rejects', () => {
  const s = new DuplexStore(config);
  assert.equal(s.append('mc', message('1', 'hello')), true);
  assert.equal(s.append('mc', message('1', 'hello')), false);
  assert.throws(() => s.append('mc', message('1', 'changed')));
  assert.equal(s.messages.length, 1);
});
test('restart preserves history/dedupe, prevents cross-session adoption and does not persist token', t => {
  const dir = mkdtempSync(join(tmpdir(), 'maid-duplex-')); t.after(() => rmSync(dir, { recursive: true, force: true }));
  const file = join(dir, 'state.json');
  const s = new DuplexStore({ ...config, file }); s.append('mc', message('1', 'hello'));
  const restored = new DuplexStore({ ...config, file });
  assert.equal(restored.append('mc', message('1', 'hello')), false);
  assert.match(restored.contextFor('dsh'), /hello/);
  assert.doesNotMatch(readFileSync(file, 'utf8'), /test-only-token/);
  assert.throws(() => new DuplexStore({ ...config, sessionId: 'session-b', file }));
});
test('persona version changes, empty persona invalidates stale identity', () => {
  const s = new DuplexStore(config); s.setPersona('A'); const a = s.personaVersion;
  s.setPersona('B'); assert.notEqual(a, s.personaVersion);
  assert.throws(() => s.setPersona('')); assert.equal(s.persona, '');
});
test('context bounded without cutting JSON records', () => {
  const s = new DuplexStore({ ...config, maxMessages: 2, maxChars: 40 });
  for (let i = 0; i < 5; i++) s.append('mc', message(String(i), 'text'));
  assert.equal(s.messages.length, 2);
  assert.equal(s.contextFor('dsh').split('\n').length, 2);
});
test('HTTP rejects unauthorized reads; parallel retries commit exactly once; refreshes persona', async t => {
  const s = new DuplexStore(config); let card = 'persona-a';
  const server = createBridge(s, async () => s.setPersona(card));
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const query = new URLSearchParams(Object.fromEntries(['binding', 'world', 'player', 'maid'].map(k => [k, config[k]])));
  const url = `${base}/v1/context?${query}`;
  assert.equal((await fetch(url)).status, 403);
  const headers = { authorization: `Bearer ${config.token}`, 'content-type': 'application/json' };
  assert.equal((await (await fetch(url, { headers })).json()).persona, card);
  card = 'persona-b'; assert.equal((await (await fetch(url, { headers })).json()).persona, card);
  const replies = await Promise.all(Array.from({ length: 10 }, () => fetch(`${base}/v1/messages`, { method: 'POST', headers, body: JSON.stringify({ ...config, ...message('same', 'hello') }) }).then(r => r.json())));
  assert.equal(replies.filter(r => r.added).length, 1);
  assert.equal((await fetch(`${base}/v1/messages`, { method: 'POST', headers, body: '{' })).status, 400);
  card = ''; assert.equal((await fetch(url, { headers })).status, 503);
});
test('native adapter binds enabling agent only; never binds another session or child', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'maid-plugin-')); t.after(() => rmSync(dir, { recursive: true, force: true }));
  const listeners = new Map(); let provider; let removed = false;
  const systemPrompt = {
    assemble: async () => ({ sections: [{ name: 'deployment:persona', text: 'actual card' }, { name: 'tools', text: 'private shell tools' }], variables: {}, tools: [], contexts: [] }),
    context: p => { provider = p; return () => { removed = true; }; },
  };
  const agent = { id: 'session-a', ctx: { systemPrompt } };
  const ctx = { agent, effect: factory => { const list = listeners.get('dispose') ?? []; list.push(factory()); listeners.set('dispose', list); }, on: (name, fn) => { const list = listeners.get(name) ?? []; list.push(fn); listeners.set(name, list); } };
  const render = { PERSONA_SECTION: 'deployment:persona', renderPrompt: a => a.sections.map(s => s.text).join('\n') };
  await assert.rejects(attachSession({ agent: null }, config, render), /single agent/);
  const { port } = await attachSession(ctx, { ...config, dataDir: dir, port: 0 }, render);
  t.after(() => listeners.get('dispose').forEach(fn => fn()));
  const base = `http://127.0.0.1:${port}`;
  const headers = { authorization: `Bearer ${config.token}`, 'content-type': 'application/json' };
  await fetch(`${base}/v1/messages`, { method: 'POST', headers, body: JSON.stringify({ ...config, ...message('mc1', 'MC memory') }) });
  assert.match(provider.text({ scope: agent }), /MC memory/);
  assert.equal(provider.text({ scope: { id: 'session-b' } }), '');
  assert.equal(provider.text({ scope: { id: 'child' } }), '');
  const listener = listeners.get('session/event')[0];
  listener({ id: 'session-b', events }, end);
  assert.equal(new DuplexStore({ ...config }).messages.length, 0);
  const query = new URLSearchParams(Object.fromEntries(['binding', 'world', 'player', 'maid'].map(k => [k, config[k]])));
  const snapshot = () => fetch(`${base}/v1/context?${query}`, { headers }).then(r => r.json());
  assert.equal((await snapshot()).context, '');
  listener({ id: 'session-a', events }, end);
  const current = await snapshot(); assert.match(current.context, /hello/);
  assert.equal(current.persona, 'actual card'); assert.doesNotMatch(JSON.stringify(current), /private shell tools|synthetic|secret tool/);
});

test('enable catches up only the exact bound live session', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'maid-catchup-'));
  const cleanup = [];
  t.after(() => { cleanup.reverse().forEach(fn => fn()); rmSync(dir, { recursive: true, force: true }); });
  const agent = { id: config.sessionId, ctx: { systemPrompt: {
    assemble: async () => ({ sections: [{ name: 'deployment:persona', text: 'card' }], variables: {}, tools: [], contexts: [] }),
    context: () => () => {},
  } } };
  let requested;
  const ctx = { agent, sessions: { get: id => { requested = id; return { id, events }; } },
    effect: fn => cleanup.push(fn()), on: () => () => {} };
  const { port } = await attachSession(ctx, { ...config, dataDir: dir, port: 0 }, {
    PERSONA_SECTION: 'deployment:persona', renderPrompt: a => a.sections[0].text,
  });
  assert.equal(requested, config.sessionId);
  const query = new URLSearchParams(Object.fromEntries(['binding', 'world', 'player', 'maid'].map(k => [k, config[k]])));
  const result = await (await fetch(`http://127.0.0.1:${port}/v1/context?${query}`, { headers: { authorization: `Bearer ${config.token}` } })).json();
  assert.match(result.context, /hello/); assert.match(result.context, /done/);
  assert.doesNotMatch(result.context, /working|secret tool result|synthetic|private/);
});
