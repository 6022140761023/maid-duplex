import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, mkdirSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const gson = process.env.GSON_JAR || join(root, '.cache', 'gson-2.10.1.jar');
const checksum = '4241c14a7727c34feea6507ec801318a3d4a90f070e4525681079fb94ee4c593';
if (!existsSync(gson)) {
  console.log('Downloading Gson 2.10.1 from Maven Central for Java tests...');
  const response = await fetch('https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar', { signal: AbortSignal.timeout(60000) });
  if (!response.ok) throw new Error('Gson download HTTP ' + response.status);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (createHash('sha256').update(bytes).digest('hex') !== checksum) throw new Error('Gson checksum mismatch');
  mkdirSync(dirname(gson), { recursive: true });
  writeFileSync(gson, bytes);
}
if (createHash('sha256').update(readFileSync(gson)).digest('hex') !== checksum) throw new Error('Expected unmodified Gson 2.10.1');
const dir = mkdtempSync(join(tmpdir(), 'maid-duplex-java-'));
const executable = name => process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : ''))
  : name;
const prefix = 'local/maidbridge/duplex/';
const classes = ['PromptPolicy', 'ProviderPolicy', 'BridgeClient'];
try {
  const sources = classes.flatMap(name => [
    join(root, 'mc-addon/src/main/java', prefix, name + '.java'),
    join(root, 'mc-addon/src/test/java', prefix, name + 'Test.java'),
  ]);
  execFileSync(executable('javac'), ['--release', '17', '-encoding', 'UTF-8', '-cp', gson, '-d', dir, ...sources], { stdio: 'inherit' });
  for (const name of classes) execFileSync(executable('java'), ['-cp', dir + delimiter + gson, 'local.maidbridge.duplex.' + name + 'Test'], { stdio: 'inherit' });
} finally {
  rmSync(dir, { recursive: true, force: true });
}
