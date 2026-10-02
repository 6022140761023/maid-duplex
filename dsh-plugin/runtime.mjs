import { pathToFileURL } from 'node:url';
import { join } from 'node:path';

// Normal installations use peer dependencies. A local source installation may
// explicitly name the host's node_modules directory; never scan user profiles.
export async function runtimeModule(name, runtimeRoot) {
  if (!['dsh-system-prompt', 'dsh-tools'].includes(name)) throw new Error('Unsupported runtime module');
  if (runtimeRoot) return import(pathToFileURL(join(runtimeRoot, '@deepseek-ai', name, 'lib', 'index.js')).href);
  return import(`@deepseek-ai/${name}`);
}
