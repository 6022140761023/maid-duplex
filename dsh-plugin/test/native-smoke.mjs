// Optional smoke test against an installed DSH runtime; never loads user config.
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import assert from 'node:assert/strict';
import { attachSession } from '../index.mjs';
import { apply as bootstrap } from '../bootstrap.mjs';
const base = process.argv[2];
if (!base) throw new Error('Pass installed DSH node_modules/@deepseek-ai directory');
const load = name => import(pathToFileURL(join(base, name, 'lib/index.js')).href);
const { Context } = await load('cordis');
const { createScope } = await load('dsh-scope');
const prompt = await load('dsh-system-prompt');
const ctx = new Context();
const dir = mkdtempSync(join(tmpdir(), 'maid-native-'));
const cleanups = [];
try {
  new prompt.default(ctx, { persona: 'GLOBAL SHOULD NOT LEAK', includeHarnessIdentity: false });
  const agent = { id: 'native-a', session: { header: { cwd: 'C:/maid-duplex-fixture' } }, options: {} };
  const scope = createScope(ctx, agent);
  agent.ctx = scope.ctx;
  agent.ctx.systemPrompt.section({ name: prompt.PERSONA_SECTION, order: 0, text: 'BOUND {{nickname}} at {{cwd}}' });
  agent.ctx.systemPrompt.variable('nickname', () => 'PERSONA');
  // Match DSH's installed dsh-agent-loop variable provider. This built-in
  // variable only resolves when assemble() receives the agent in its context.
  agent.ctx.systemPrompt.variable('cwd', context => context.agent?.session.header.cwd);
  const addonContext = { agent, effect: factory => cleanups.push(factory()), on: () => {} };
  const cfg = { binding: 'test', world: 'w', player: 'p', maid: 'm', token: 'test-pairing-token-abcdefghijklmnopqrstuvwxyz', dataDir: dir, port: 0 };
  const { port } = await attachSession(addonContext, cfg, prompt);
  const query = new URLSearchParams(Object.fromEntries(['binding', 'world', 'player', 'maid'].map(k => [k, cfg[k]])));
  const headers = { authorization: `Bearer ${cfg.token}`, 'content-type': 'application/json' };
  const url = `http://127.0.0.1:${port}`;
  const snapshot = await (await fetch(`${url}/v1/context?${query}`, { headers })).json();
  assert.equal(snapshot.persona, 'BOUND PERSONA at C:/maid-duplex-fixture');
  await fetch(`${url}/v1/messages`, { method: 'POST', headers, body: JSON.stringify({ ...cfg, id: 'one', role: 'user', text: 'MC NATIVE MEMORY' }) });
  const bound = await agent.ctx.systemPrompt.assemble({ scope: agent });
  assert.match(prompt.renderContextSnapshot(bound), /MC NATIVE MEMORY/);
  const other = await ctx.systemPrompt.assemble({ scope: { id: 'other' } });
  assert.doesNotMatch(prompt.renderContextSnapshot(other), /MC NATIVE MEMORY/);
  assert.equal(prompt.renderPrompt(other), 'GLOBAL SHOULD NOT LEAK');
  console.log('Native DSH runtime: PASS 4 / FAIL 0 (scoped persona, variables, MC context, unrelated session isolation)');
  for (const fn of cleanups.splice(0)) fn();
  let tool;
  const controls = { tools: { register: t => { tool = t; } }, effect: factory => cleanups.push(factory()), on: () => () => {} };
  await bootstrap(controls, { dataDir: join(dir, 'controls'), runtimeRoot: join(base, '..'), port: 0 });
  const args = { action: 'enable', world: 'test-world', player: '00000000-0000-0000-0000-000000000001', maid: '00000000-0000-0000-0000-000000000002' };
  const enabled = await tool.execute(args, { agent });
  assert.equal(enabled.sessionId, 'native-a'); assert.equal(enabled.enabled, true);
  const otherAgent = { id: 'other', ctx: agent.ctx };
  const otherStatus = await tool.execute({ action: 'status' }, { agent: otherAgent });
  assert.equal(otherStatus.enabled, false);
  await tool.execute({ action: 'disable' }, { agent: otherAgent });
  assert.equal((await tool.execute({ action: 'status' }, { agent })).enabled, true);
  await assert.rejects(tool.execute(args, { agent: otherAgent }), /already bound/);
  await tool.execute({ action: 'disable' }, { agent });
  assert.equal((await tool.execute({ action: 'status' }, { agent })).enabled, false);
  console.log('Native DSH control tool: PASS 7 / FAIL 0 (caller binding, other-session status/disable, cross-session enable refusal, own disable)');
  await scope.dispose();
} finally {
  for (const fn of cleanups) fn();
  await ctx.fiber.dispose();
  rmSync(dir, { recursive: true, force: true });
}
