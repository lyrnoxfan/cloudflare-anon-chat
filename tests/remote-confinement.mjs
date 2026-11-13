// Test-runner-only confinement. Install before creating ANY page in the context.
// This is not proof that the approved Worker/D1/R2/DO/SFU resources are isolated.
async function confineRemoteContext(context, origin) {
  const approved = new URL(origin);
  if (approved.protocol !== 'https:' || approved.origin !== origin || approved.username || approved.password) {
    throw new Error('Expected a canonical approved HTTPS origin');
  }
  if (context.pages().length || context.serviceWorkers().length) {
    throw new Error('Remote confinement must be installed once, before any page or service worker');
  }
  const socketOrigin = `wss://${approved.host}`;
  const violations = [];
  const allowed = (raw, socket = false) => {
    try {
      const url = new URL(raw);
      return !url.username && !url.password && url.origin === (socket ? socketOrigin : origin);
    } catch { return false; }
  };
  const reject = (kind, raw) => {
    // Do not print presigned query values or URL credentials in test errors.
    const url = new URL(raw);
    violations.push({kind, url: url.origin + url.pathname});
  };
  await context.route('**/*', async route => {
    const request = route.request();
    if (!allowed(request.url())) {
      reject('http-origin', request.url());
      await route.abort('blockedbyclient');
      return;
    }
    let response;
    try {
      // context.route alone only sees the FIRST hop of a redirect chain.
      // Fetch the approved hop ourselves, NEVER follow redirects, and never
      // deliver a redirect response to the browser (including navigations).
      response = await route.fetch({maxRedirects: 0, maxRetries: 0});
      if (response.status() >= 300 && response.status() < 400) {
        reject('http-redirect', request.url());
        await route.abort('blockedbyclient');
      } else {
        await route.fulfill({response});
      }
    } catch {
      reject('http-transport', request.url());
      await route.abort('failed').catch(() => {});
    } finally {
      await response?.dispose();
    }
  });
  // HTTP routing does not intercept WebSocket handshakes. Deny unapproved
  // socket origins BEFORE connectToServer; Chromium rejects WS redirects.
  // The offline wire-level regression verifies both that behavior and that
  // this check applies to same-origin connections and newly created pages.
  await context.routeWebSocket('**/*', socket => {
    if (!allowed(socket.url(), true)) {
      reject('websocket-origin', socket.url());
      socket.close({code: 1008, reason: 'Remote acceptance origin denied'});
      return;
    }
    socket.connectToServer();
  });
  return {
    violations,
    assertClean() {
      if (violations.length) throw new Error(`Remote acceptance confinement blocked ${JSON.stringify(violations)}`);
    },
  };
}

export async function newConfinedRemoteContext(browser, origin, options = {}) {
  // A service worker can bypass context.route; never let options turn it on.
  const context = await browser.newContext({...options, serviceWorkers: 'block'});
  try {
    const guard = await confineRemoteContext(context, origin);
    return {context, guard};
  } catch (error) {
    await context.close();
    throw error;
  }
}
