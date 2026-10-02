import { createServer } from 'node:http';

export function createBridge(store, refreshPersona) {
  return createServer(async (req, res) => {
    const reply = (status, value) => { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }); res.end(JSON.stringify(value)); };
    try {
      const url = new URL(req.url, 'http://127.0.0.1');
      if (req.method === 'GET' && url.pathname === '/health') return reply(200, { ok: true, service: 'maid-duplex', bound: true });
      let body = {};
      if (req.method === 'POST') {
        let size = 0; const chunks = [];
        for await (const chunk of req) {
          size += chunk.length;
          if (size > 65536) { reply(413, { error: 'Request too large' }); return; }
          chunks.push(chunk);
        }
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return reply(400, { error: 'Invalid JSON' }); }
      }
      const scope = req.method === 'GET' ? Object.fromEntries(url.searchParams) : body;
      if (!store.authorize(req.headers.authorization, scope)) return reply(403, { error: 'Invalid pairing' });
      if (req.method === 'GET' && url.pathname === '/v1/context') {
        try { await refreshPersona(); } catch { return reply(503, { error: 'Bound session persona unavailable' }); }
        if (!store.persona) return reply(503, { error: 'Bound session persona unavailable' });
        return reply(200, { persona: store.persona, personaVersion: store.personaVersion, context: store.contextFor('mc') });
      }
      if (req.method === 'POST' && url.pathname === '/v1/messages') {
        try { const added = store.append('mc', body); return reply(200, { ok: true, added }); }
        catch { return reply(400, { error: 'Invalid message or persistence failure' }); }
      }
      reply(404, { error: 'Not found' });
    } catch { if (!res.headersSent) reply(500, { error: 'Bridge request failed' }); else res.end(); }
  });
}
