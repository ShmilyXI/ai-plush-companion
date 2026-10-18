package zixuan.modules.volcengine.voice;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import zixuan.common.exception.RenException;

public class VolcengineRequestSigner {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String CONTENT_TYPE = "application/json; charset=UTF-8";
    private static final String SIGNED_HEADERS = "host;x-content-sha256;x-date";

    private final Clock clock;

    public VolcengineRequestSigner(Clock clock) {
        this.clock = clock;
    }

    public SignedVolcengineRequest sign(URI uri, String body, String accessKeyId, String secretAccessKey,
            String region, String service) {
        try {
            String xDate = DATE_TIME.format(clock.instant());
            String shortDate = xDate.substring(0, 8);
            String payloadHash = sha256(body);
            String canonicalQuery = canonicalQuery(uri.getRawQuery());
            String canonicalRequest = "POST\n" + uri.getRawPath() + "\n" + canonicalQuery + "\n"
                    + "host:" + uri.getHost() + "\n"
                    + "x-content-sha256:" + payloadHash + "\n"
                    + "x-date:" + xDate + "\n\n"
                    + SIGNED_HEADERS + "\n"
                    + payloadHash;
            String credentialScope = shortDate + "/" + region + "/" + service + "/request";
            String stringToSign = "HMAC-SHA256\n" + xDate + "\n" + credentialScope + "\n"
                    + sha256(canonicalRequest);
            byte[] kDate = hmac(secretAccessKey.getBytes(UTF_8), shortDate);
            byte[] kRegion = hmac(kDate, region);
            byte[] kService = hmac(kRegion, service);
            byte[] kSigning = hmac(kService, "request");
            String signature = HexFormat.of().formatHex(hmac(kSigning, stringToSign));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", CONTENT_TYPE);
            headers.put("X-Date", xDate);
            headers.put("X-Content-Sha256", payloadHash);
            headers.put("Authorization", "HMAC-SHA256 Credential=" + accessKeyId + "/" + credentialScope
                    + ", SignedHeaders=" + SIGNED_HEADERS + ", Signature=" + signature);
            return new SignedVolcengineRequest(uri, body, Map.copyOf(headers));
        } catch (Exception e) {
            throw new RenException("火山引擎请求签名失败");
        }
    }

    private String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        return rawQuery.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split("&")))
                .sorted()
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
    }

    private byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(UTF_8));
    }
}
