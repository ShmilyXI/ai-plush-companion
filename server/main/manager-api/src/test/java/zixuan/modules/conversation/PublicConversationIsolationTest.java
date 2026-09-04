package zixuan.modules.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.Test;

import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import zixuan.modules.conversation.vo.PublicConversationSessionVO;

class PublicConversationIsolationTest {
    @Test
    void publicBundleAndSessionHaveNoDeviceToolOrSkillInjectionFields() {
        assertNotNull(PublicConversationRuntimeBundleVO.class.getRecordComponents());
        assertNotNull(PublicConversationSessionVO.class.getRecordComponents());
        for (Class<?> type : new Class<?>[] { PublicConversationRuntimeBundleVO.class, PublicConversationSessionVO.class }) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase();
                assertFalse(name.contains("mcp"));
                assertFalse(name.contains("devicecommand"));
                assertFalse(name.contains("skilltool"));
            }
        }
    }
}
