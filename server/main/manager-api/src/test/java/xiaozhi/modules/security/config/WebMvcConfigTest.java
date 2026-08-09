package xiaozhi.modules.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;

class WebMvcConfigTest {

    @Test
    void modelConnectionElapsedTimeIsSerializedAsJsonNumber() throws Exception {
        ObjectMapper mapper = new WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(
                new CompanionModelTestVO(true, 5, "连接成功")));

        assertEquals(true, json.get("elapsedMillis").isNumber());
        assertEquals(5, json.get("elapsedMillis").intValue());
    }
}
