import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { validateSnapshot, validateAdvice, suggestRecovery, createGateway } from './ai-recovery.mjs';

const snapshot = () => ({ request_id: 'request_001', frame_fingerprint: 'a'.repeat(64), provider: 'HUNGERSTATION',
  public_text: 'Weekly grocery advert over the restaurant search',
  candidates: [{ id: 42, label: 'Close dialog', class_name: 'android.view.View' }], redacted_image: null, redaction_version: 1 });
const advice = () => ({ screen_type: 'advert', action: 'dismiss_overlay', target_id: 42, confidence: .98, reason: 'إغلاق العرض ثم التحقق من عودة البحث.' });
const stub = payload => async () => ({ ok: true, json: async () => ({ status: 'completed', output: [
  { type: 'message', content: [{ type: 'output_text', text: JSON.stringify(payload) }] },
] }) });

test('only grounded high-confidence overlay dismissal passes', () => {
  assert.deepEqual(validateAdvice(advice(), snapshot()), advice());
  for (const invalid of [ { ...advice(), target_id: 43 }, { ...advice(), confidence: .4 },
    { ...advice(), action: 'pay' }, { ...advice(), screen_type: 'blocked' },
    { ...advice(), x: 1 }, { ...advice(), confidence: NaN } ]) assert.throws(() => validateAdvice(invalid, snapshot()));
});
test('snapshot rejects private values, duplicate candidates and remote image URLs', () => {
  for (const invalid of [ { ...snapshot(), public_text: 'user@example.com' },
    { ...snapshot(), candidates: [...snapshot().candidates, ...snapshot().candidates] },
    { ...snapshot(), redacted_image: 'https://private.example/image.png' },
    { ...snapshot(), redaction_version: 0 }, { ...snapshot(), address: 'private' } ]) assert.throws(() => validateSnapshot(invalid));
});
test('request uses the chosen vision model, strict output, no persistence and offered IDs only', async () => {
  let body;
  const result = await suggestRecovery(snapshot(), { apiKey: 'test-only-placeholder', fetchImpl: async (url, init) => {
    assert.equal(url, 'https://api.openai.com/v1/responses'); body = JSON.parse(init.body);
    return stub(advice())();
  } });
  assert.equal(body.model, 'gpt-6.1-terra'); assert.equal(body.store, false);
  assert.equal(body.text.format.strict, true);
  assert.deepEqual(body.text.format.schema.properties.target_id.enum, [null, 42]);
  assert.equal(result.frame_fingerprint, snapshot().frame_fingerprint);
  assert.deepEqual(result.advice, advice());
});
test('missing credentials, refusal, incomplete response and hallucinated targets fail closed', async () => {
  await assert.rejects(suggestRecovery(snapshot()), /AI_NOT_CONFIGURED/);
  await assert.rejects(suggestRecovery(snapshot(), { apiKey: 'test', fetchImpl: stub({ ...advice(), target_id: 99 }) }), /UNSAFE_ADVICE/);
  for (const response of [{ status: 'incomplete', output: [] },
    { status: 'completed', output: [{ type: 'message', content: [{ type: 'refusal', refusal: 'No' }] }] }]) {
    await assert.rejects(suggestRecovery(snapshot(), { apiKey: 'test', fetchImpl: async () => ({ ok: true, json: async () => response }) }));
  }
});
test('gateway requires a provisioned device token and enforces a bounded model request rate', async t => {
  assert.throws(() => createGateway({ clientToken: 'short' }));
  let calls=0;
  const token='test-device-token-with-at-least-32-characters';
  const server=createGateway({ apiKey: 'test', clientToken: token, now: () => 1000,
    fetchImpl: async () => { calls++;return stub(advice())(); } });
  server.listen(0, '127.0.0.1'); await once(server,'listening');
  t.after(() => new Promise(resolve => server.close(resolve)));
  const url=`http://127.0.0.1:${server.address().port}/v1/recover`;
  const post=auth => fetch(url,{ method:'POST',headers:{authorization:auth,'content-type':'application/json'},body:JSON.stringify(snapshot()) });
  assert.equal((await post('Bearer wrong')).status,401);
  for(let i=0;i<8;i++) assert.equal((await post(`Bearer ${token}`)).status,200);
  assert.equal((await post(`Bearer ${token}`)).status,429);assert.equal(calls,8);
});
