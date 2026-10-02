import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { attachSession } from './index.mjs';
import { runtimeModule } from './runtime.mjs';

export const name = 'maid-duplex-controls';
export const inject = ['tools', 'systemPrompt', 'sessions', 'agents'];

// Installation only makes the controls available. Activation derives identity
// from exec.agent; there is intentionally no sessionId parameter to spoof.
export async function apply(ctx, config = {}) {
  const { defineTool } = await runtimeModule('dsh-tools', config.runtimeRoot);
  const prompt = await runtimeModule('dsh-system-prompt', config.runtimeRoot);
  if (!isAbsolute(config.dataDir ?? '')) throw new Error('Absolute private dataDir required');
  const active = new Map();
  let enabling = false;
  let enablingSession = null;
  let enableCancelled = false;
  let disposed = false;
  ctx.effect(() => () => { disposed = true; for (const item of active.values()) item.stop(); active.clear(); }, 'maid-duplex controls');
  ctx.tools.register(defineTool({
    name: 'maid_duplex',
    description: 'Enable, disable or inspect MC conversation sharing for THIS DSH conversation only. Requires explicit user request. Never enable in another conversation. Pairing token is saved privately, never returned as dialogue.',
    parameters: {
      action: { type: 'string', enum: ['enable', 'disable', 'status'], required: true },
      world: { type: 'string', description: 'Explicit per-save world ID; required to enable.' },
      player: { type: 'string', description: 'Owner player UUID; required to enable.' },
      maid: { type: 'string', description: 'Maid UUID; required to enable.' },
    },
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute(args, exec) {
      if (disposed) throw new Error('Plugin has stopped');
      const agent = exec.agent;
      if (!agent?.id || !agent.ctx) throw new Error('An agent-backed conversation is required');
      const id = String(agent.id);
      if (args.action === 'status') return { enabled: active.has(id), sessionId: id };
      if (args.action === 'disable') {
        if (enablingSession === id) enableCancelled = true;
        active.get(id)?.stop(); active.delete(id); return { enabled: false, sessionId: id };
      }
      if (args.action !== 'enable') throw new Error('Unknown action');
      if (active.has(id)) return { enabled: true, sessionId: id, pairingFile: active.get(id).pairingFile };
      if (active.size) throw new Error('This bridge port is already bound; disable it in its owning conversation first');
      if (enabling) throw new Error('A pairing operation is already in progress; retry after it finishes');
      for (const key of ['world', 'player', 'maid']) if (typeof args[key] !== 'string' || !args[key].trim() || args[key].length > 256) throw new Error(`${key} required (1–256 characters)`);
      for (const key of ['player', 'maid']) if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(args[key])) throw new Error(`${key} must be a UUID`);
      const fileId = createHash('sha256').update(id).digest('hex');
      const pairingFile = join(config.dataDir, `${fileId}.pairing.json`);
      let pairing;
      try { pairing = JSON.parse(readFileSync(pairingFile, 'utf8')); } catch (e) { if (e.code !== 'ENOENT') throw new Error('Pairing file is invalid'); }
      if (pairing) {
        if (pairing.sessionId !== id || ['world', 'player', 'maid'].some(k => pairing[k] !== args[k])) throw new Error('Existing pairing differs; use a separate data directory to create a new binding');
      } else {
        pairing = { sessionId: id, binding: randomUUID(), world: args.world, player: args.player, maid: args.maid, token: randomBytes(32).toString('hex') };
        mkdirSync(config.dataDir, { recursive: true });
        writeFileSync(pairingFile, JSON.stringify(pairing, null, 2), { encoding: 'utf8', mode: 0o600, flag: 'wx' });
      }
      const cleanups = [];
      const stop = () => { for (const cleanup of cleanups.splice(0).reverse()) cleanup(); };
      const local = {
        agent,
        sessions: ctx.sessions,
        effect: factory => { const disposer = factory(); cleanups.push(disposer); return disposer; },
        on: (event, fn) => { const disposer = ctx.on(event, fn); cleanups.push(disposer); return disposer; },
      };
      enabling = true;
      enablingSession = id;
      enableCancelled = false;
      try {
        const { port } = await attachSession(local, { ...pairing, dataDir: config.dataDir, port: config.port ?? 8790 }, prompt);
        if (disposed || enableCancelled) throw new Error('Plugin stopped during activation');
        local.on('session/disposed', session => { if (String(session.id) === id) { stop(); active.delete(id); } });
        active.set(id, { stop, pairingFile });
        return { enabled: true, sessionId: id, pairingFile, port };
      } catch (error) { stop(); throw error; }
      finally { enabling = false; enablingSession = null; }
    },
  }));
}
