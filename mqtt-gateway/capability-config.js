const crypto = require('node:crypto');

function generateVisionToken(secret, deviceId) {
    const key = crypto.pbkdf2Sync(secret, 'fixed_salt_placeholder', 100000, 32, 'sha256');
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const payload = JSON.stringify({
        device_id: deviceId,
        exp: Math.floor(Date.now() / 1000) + 3600
    });
    const ciphertext = Buffer.concat([cipher.update(payload), cipher.final()]);
    const encrypted = Buffer.concat([iv, ciphertext, cipher.getAuthTag()]);
    const encryptedPayload = encrypted.toString('base64')
        .replace(/\+/g, '-')
        .replace(/\//g, '_');
    const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
    const outer = Buffer.from(JSON.stringify({ data: encryptedPayload })).toString('base64url');
    const signingInput = `${header}.${outer}`;
    const signature = crypto.createHmac('sha256', secret).update(signingInput).digest('base64url');
    return `${signingInput}.${signature}`;
}

function resolveServerCapabilities(configuredCapabilities, environment = process.env, identity = {}) {
    const capabilities = configuredCapabilities && typeof configuredCapabilities === 'object'
        ? { ...configuredCapabilities }
        : {};
    const visionUrl = environment.ZIXUAN_VISION_EXPLAIN_URL?.trim();
    if (!visionUrl) {
        return capabilities;
    }

    const configuredVision = capabilities.vision && typeof capabilities.vision === 'object'
        ? capabilities.vision
        : {};
    capabilities.vision = { ...configuredVision, url: visionUrl };
    const token = environment.SERVER_SECRET?.trim();
    if (token && identity.deviceId) {
        capabilities.vision.token = generateVisionToken(token, identity.deviceId);
    }
    return capabilities;
}

function redactValue(value) {
    if (Array.isArray(value)) {
        return value.map(redactValue);
    }
    if (!value || typeof value !== 'object') {
        return value;
    }
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
        key,
        /token|password|secret|authorization/i.test(key) ? '[redacted]' : redactValue(item)
    ]));
}

function redactMqttPayload(payload) {
    try {
        return JSON.stringify(redactValue(JSON.parse(payload)));
    } catch {
        return '[unparseable payload]';
    }
}

module.exports = { redactMqttPayload, resolveServerCapabilities };
