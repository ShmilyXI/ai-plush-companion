const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const test = require('node:test');

const { redactMqttPayload, resolveServerCapabilities } = require('../capability-config');

function decodeVisionToken(token, secret) {
    const [encodedHeader, encodedPayload, encodedSignature] = token.split('.');
    const signingInput = `${encodedHeader}.${encodedPayload}`;
    const expectedSignature = crypto.createHmac('sha256', secret).update(signingInput).digest('base64url');
    assert.equal(encodedSignature, expectedSignature);

    const outer = JSON.parse(Buffer.from(encodedPayload, 'base64url').toString());
    const encrypted = Buffer.from(outer.data, 'base64url');
    const key = crypto.pbkdf2Sync(secret, 'fixed_salt_placeholder', 100000, 32, 'sha256');
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, encrypted.subarray(0, 12));
    decipher.setAuthTag(encrypted.subarray(-16));
    const plaintext = Buffer.concat([
        decipher.update(encrypted.subarray(12, -16)),
        decipher.final()
    ]);
    return JSON.parse(plaintext.toString());
}

test('adds configured vision capability during device initialization', () => {
    const secret = 'server-secret';
    const deviceId = '7c:0c:5f:40:49:54';
    const capabilities = resolveServerCapabilities(
        { sampling: { enabled: true } },
        {
            ZIXUAN_VISION_EXPLAIN_URL: 'http://192.168.0.102:8003/mcp/vision/explain',
            SERVER_SECRET: secret
        },
        { deviceId }
    );

    assert.deepEqual(capabilities.sampling, { enabled: true });
    assert.equal(capabilities.vision.url, 'http://192.168.0.102:8003/mcp/vision/explain');
    assert.notEqual(capabilities.vision.token, secret);
    const payload = decodeVisionToken(capabilities.vision.token, secret);
    assert.equal(payload.device_id, deviceId);
    assert.ok(payload.exp > Date.now() / 1000);
});

test('does not expose the service secret without a vision URL', () => {
    const capabilities = resolveServerCapabilities({}, { SERVER_SECRET: 'server-secret' });

    assert.deepEqual(capabilities, {});
});

test('redacts credentials from MQTT debug payloads', () => {
    const payload = JSON.stringify({
        type: 'mcp',
        payload: {
            params: {
                capabilities: {
                    vision: { url: 'http://vision', token: 'server-secret' }
                },
                password: 'mqtt-password'
            }
        }
    });

    const rendered = redactMqttPayload(payload);

    assert.doesNotMatch(rendered, /server-secret|mqtt-password/);
    assert.match(rendered, /\[redacted\]/);
});
