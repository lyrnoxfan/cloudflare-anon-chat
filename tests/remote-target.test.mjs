import {test} from 'node:test';
import assert from 'node:assert/strict';
import {remoteAcceptanceTarget} from './remote-target.mjs';

const valid = {CHAT_TEST_BASE_URL: 'https://isolated-qa.example.test', CHAT_TEST_TARGET: 'isolated-qa'};
test('remote acceptance has no implicit production target', () => {
  for (const env of [{}, {CHAT_TEST_TARGET: 'isolated-qa'}, {CHAT_TEST_BASE_URL: valid.CHAT_TEST_BASE_URL}]) {
    assert.throws(() => remoteAcceptanceTarget(env), /requires explicit/);
  }
});
test('known production origin and subdomains are denied, including URL canonicalization', () => {
  for (const host of ['chat.lyrnox.com', 'CHAT.LYRNOX.COM', 'child.chat.lyrnox.com', 'chat.lyrnox.com.', 'child.chat.lyrnox.com.', '%63hat.lyrnox.com']) {
    assert.throws(() => remoteAcceptanceTarget({...valid, CHAT_TEST_BASE_URL: `https://${host}`}), /production chat host/);
  }
});
test('production service names are denied even on a different origin', () => {
  for (const name of ['blue-room-test', 'PRODUCTION', ' prod ', '   ']) {
    assert.throws(() => remoteAcceptanceTarget({...valid, CHAT_TEST_TARGET: name}));
  }
});
test('remote acceptance rejects non-origin URLs and insecure targets', () => {
  for (const url of ['http://isolated-qa.example.test', 'https://user:password@isolated-qa.example.test', 'https://isolated-qa.example.test/path', 'https://isolated-qa.example.test/?query=1', 'https://isolated-qa.example.test/#fragment', 'not-a-url']) {
    assert.throws(() => remoteAcceptanceTarget({...valid, CHAT_TEST_BASE_URL: url}));
  }
});
test('explicit isolated origin derives matching HTTP and WebSocket targets', () => {
  assert.deepEqual(remoteAcceptanceTarget(valid), {origin: valid.CHAT_TEST_BASE_URL, socketOrigin: 'wss://isolated-qa.example.test', targetName: 'isolated-qa'});
  assert.deepEqual(remoteAcceptanceTarget({...valid, CHAT_TEST_BASE_URL: 'https://isolated-qa.example.test:8443/'}), {origin: 'https://isolated-qa.example.test:8443', socketOrigin: 'wss://isolated-qa.example.test:8443', targetName: 'isolated-qa'});
});
