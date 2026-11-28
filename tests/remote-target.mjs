// Remote acceptance creates sessions, messages, rooms, media and call traffic.
// A historical "test" Worker name does not make the live chat host disposable.
export function remoteAcceptanceTarget(env = process.env) {
  const raw = env.CHAT_TEST_BASE_URL;
  const targetName = env.CHAT_TEST_TARGET?.trim();
  if (!raw || !targetName) {
    throw new Error('Remote acceptance requires explicit CHAT_TEST_BASE_URL and CHAT_TEST_TARGET for a separate isolated service');
  }
  const target = new URL(raw);
  const host = target.hostname.toLowerCase().replace(/\.+$/, '');
  if (target.protocol !== 'https:' || target.username || target.password ||
      target.pathname !== '/' || target.search || target.hash ||
      host === 'chat.lyrnox.com' || host.endsWith('.chat.lyrnox.com')) {
    throw new Error('Remote acceptance requires an isolated HTTPS origin, not the production chat host, credentials, path, query or fragment');
  }
  if (['blue-room-test', 'production', 'prod'].includes(targetName.toLowerCase())) {
    throw new Error('CHAT_TEST_TARGET must identify a separate isolated test service');
  }
  return {origin: target.origin, socketOrigin: `wss://${target.host}`, targetName};
}
