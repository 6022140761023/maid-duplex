import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { DuplexStore, completedTurn } from './core.mjs';
import { createBridge } from './server.mjs';
import { runtimeModule } from './runtime.mjs';

export const name = 'maid-duplex';
export const inject = ['systemPrompt', 'sessions', 'agents'];

// Native session-scoped Cordis plugin, NOT the legacy isolated activate(ctx) SDK.
// It must be mounted through the target agent's context. Global/preset mounts
// deliberately fail: a preset can be shared by multiple unrelated sessions.
export async function apply(ctx, config = {}) {
  return attachSession(ctx, config, await runtimeModule('dsh-system-prompt', config.runtimeRoot));
}

export async function attachSession(ctx, config, { renderPrompt, PERSONA_SECTION }) {
  const agent = ctx.agent;
  if (!agent?.id || !agent.ctx) throw new Error('maid-duplex must be enabled in a single agent session; global/preset activation is refused');
  if (!config.dataDir) throw new Error('maid-duplex requires a private dataDir');
  const sessionId = String(agent.id);
  const stateName = createHash('sha256').update(sessionId).digest('hex') + '.json';
  const store = new DuplexStore({ ...config, sessionId, file: join(config.dataDir, stateName) });
  const refresh = async () => {
    // Assemble the exact agent scope every time; do not read a global soul.md,
    // manually copied card, another tab, or the full tool-bearing system prompt.
    const assembly = await agent.ctx.systemPrompt.assemble({ agent, scope: agent });
    const persona = assembly.sections.filter(s => s.name === PERSONA_SECTION);
    if (persona.length !== 1) { store.setPersona(''); }
    store.setPersona(renderPrompt({ ...assembly, sections: persona }));
  };
  await refresh();
  // Recover recent completed dialogue from this exact live session on enable.
  // This also fills the gap after a plugin restart without copying tool history.
  const session = ctx.sessions?.get(agent.id);
  if (session && String(session.id) === sessionId) {
    const events = session.events;
    for (const end of events.filter(e => e.type === 'turn/end').slice(-100)) {
      for (const message of completedTurn(events, end)) store.append('dsh', message);
    }
  }
  const server = createBridge(store, refresh);
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.port ?? 8790, '127.0.0.1', resolve);
  });
  ctx.effect(() => () => { server.closeAllConnections(); server.close(); }, 'maid-duplex server');
  const removeContext = agent.ctx.systemPrompt.context({
    name: 'maid-duplex:dialogue', order: 750,
    text: assemblyContext => assemblyContext.scope === agent ? store.contextFor('dsh') : '',
  });
  ctx.effect(() => removeContext, 'maid-duplex context');
  ctx.on('session/event', (session, event) => {
    if (String(session.id) !== sessionId) return;
    try {
      for (const message of completedTurn(session.events, event)) store.append('dsh', message);
    } catch {
      // A failure to persist must not interrupt the user's DSH turn.
      // No message contents, credentials or exceptions are logged.
      console.error('[maid-duplex] conversation sync failed');
    }
  });
  return { port: server.address().port };
}
