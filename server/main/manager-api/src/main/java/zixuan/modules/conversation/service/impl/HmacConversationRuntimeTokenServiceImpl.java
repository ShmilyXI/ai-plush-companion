package zixuan.modules.conversation.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import zixuan.common.constant.Constant;
import zixuan.common.utils.JsonUtils;
import zixuan.modules.conversation.service.ConversationRuntimeTokenService;
import zixuan.modules.sys.service.SysParamsService;

@Service
public class HmacConversationRuntimeTokenServiceImpl implements ConversationRuntimeTokenService {
    private static final String VERSION = "v1";
    private static final String AUDIENCE = "public-conversation";
    private final byte[] secret;

    public HmacConversationRuntimeTokenServiceImpl(String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("runtime secret is required");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Autowired
    public HmacConversationRuntimeTokenServiceImpl(SysParamsService params) {
        this(params.getValue(Constant.SERVER_SECRET, false));
    }

    @Override
    public String issue(RuntimeTokenClaims claims) {
        validateClaims(claims);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("aud", AUDIENCE);
        payload.put("cid", claims.conversationId());
        payload.put("sub", claims.subject());
        payload.put("aid", claims.agentId());
        payload.put("av", claims.agentVersion());
        payload.put("scopes", sorted(claims.scopes()));
        payload.put("in", sorted(claims.inputModes()));
        payload.put("out", sorted(claims.outputModes()));
        payload.put("iat", claims.issuedAt().getEpochSecond());
        payload.put("exp", claims.expiresAt().getEpochSecond());
        String encoded = encode(JsonUtils.toJsonString(payload).getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + encoded + "." + encode(sign((VERSION + "." + encoded).getBytes(StandardCharsets.US_ASCII)));
    }

    @Override
    public RuntimeTokenClaims verify(String token, Instant now) {
        if (token == null) throw new IllegalArgumentException("token is required");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) throw new IllegalArgumentException("token format is invalid");
        byte[] expected = sign((VERSION + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        byte[] actual = decode(parts[2]);
        if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("token signature is invalid");
        Map<String, Object> payload = JsonUtils.parseMap(new String(decode(parts[1]), StandardCharsets.UTF_8));
        if (payload == null || !Integer.valueOf(1).equals(number(payload.get("v")))
                || !AUDIENCE.equals(payload.get("aud"))) throw new IllegalArgumentException("token audience or version is invalid");
        for (String key : List.of("api_key", "access_token", "secret", "password", "token")) {
            if (payload.containsKey(key)) throw new IllegalArgumentException("token contains a secret claim");
        }
        long issued = requiredLong(payload, "iat");
        long expires = requiredLong(payload, "exp");
        long current = (now == null ? Instant.now() : now).getEpochSecond();
        if (expires <= current || issued > current + 60) throw new IllegalArgumentException("token is expired or not yet valid");
        long version = requiredLong(payload, "av");
        if (version <= 0) throw new IllegalArgumentException("token agent version is invalid");
        return new RuntimeTokenClaims(
                requiredText(payload, "cid"), requiredText(payload, "sub"), requiredText(payload, "aid"), version,
                requiredSet(payload, "scopes"), requiredSet(payload, "in"), requiredSet(payload, "out"),
                Instant.ofEpochSecond(issued), Instant.ofEpochSecond(expires));
    }

    private byte[] sign(byte[] input) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (Exception error) {
            throw new IllegalStateException("cannot sign runtime token", error);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("token encoding is invalid", error);
        }
    }

    private static void validateClaims(RuntimeTokenClaims claims) {
        if (claims == null || blank(claims.conversationId()) || blank(claims.subject()) || blank(claims.agentId())
                || claims.agentVersion() <= 0 || claims.issuedAt() == null || claims.expiresAt() == null
                || !claims.expiresAt().isAfter(claims.issuedAt())) throw new IllegalArgumentException("runtime claims are invalid");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static List<String> sorted(Set<String> values) { return new ArrayList<>(new TreeSet<>(values == null ? Set.of() : values)); }
    private static String requiredText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("token claim " + key + " is invalid");
        return text;
    }
    private static Set<String> requiredSet(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty() || list.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank()))
            throw new IllegalArgumentException("token claim " + key + " is invalid");
        return Set.copyOf(list.stream().map(String.class::cast).toList());
    }
    private static long requiredLong(Map<String, Object> payload, String key) {
        Number value = number(payload.get(key));
        if (value == null) throw new IllegalArgumentException("token claim " + key + " is invalid");
        return value.longValue();
    }
    private static Number number(Object value) { return value instanceof Number number ? number : null; }
}
