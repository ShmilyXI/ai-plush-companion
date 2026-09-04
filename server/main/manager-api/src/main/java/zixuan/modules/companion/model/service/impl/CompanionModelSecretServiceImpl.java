package zixuan.modules.companion.model.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import zixuan.common.constant.Constant;
import zixuan.common.utils.AESUtils;
import zixuan.modules.companion.model.service.CompanionModelSecretService;
import zixuan.modules.sys.service.SysParamsService;

@Service
@AllArgsConstructor
public class CompanionModelSecretServiceImpl implements CompanionModelSecretService {
    private static final String VERSION = "v1:";
    private final SysParamsService paramsService;

    @Override
    public String encrypt(String plainText) {
        return VERSION + AESUtils.encrypt(secret(), plainText);
    }

    @Override
    public String decrypt(String ciphertext) {
        if (StringUtils.isBlank(ciphertext) || !ciphertext.startsWith(VERSION)) return null;
        return AESUtils.decrypt(secret(), ciphertext.substring(VERSION.length()));
    }

    @Override
    public String encryptMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return null;
        return encrypt(new JSONObject(value).toString());
    }

    @Override
    public Map<String, Object> decryptMap(String ciphertext) {
        String plainText = decrypt(ciphertext);
        if (StringUtils.isBlank(plainText)) return Map.of();
        JSONObject value = new JSONObject(plainText);
        return new LinkedHashMap<>(value);
    }

    private String secret() {
        String value = paramsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(value)) throw new IllegalStateException("server.secret 未配置");
        return value;
    }
}
