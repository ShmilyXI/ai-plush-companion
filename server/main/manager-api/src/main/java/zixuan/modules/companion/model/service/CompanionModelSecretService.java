package zixuan.modules.companion.model.service;

import java.util.Map;

public interface CompanionModelSecretService {
    String encrypt(String plainText);
    String decrypt(String ciphertext);
    String encryptMap(Map<String, Object> value);
    Map<String, Object> decryptMap(String ciphertext);
}
