package zixuan.modules.config.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CompanionNamespaceTest {
    @Test
    void namespaceIsStableAndSeparatesEveryOwnershipDimension() {
        String first = CompanionNamespace.create(7L, "agent-a", "device-a");

        assertEquals(first, CompanionNamespace.create(7L, "agent-a", "device-a"));
        assertNotEquals(first, CompanionNamespace.create(8L, "agent-a", "device-a"));
        assertNotEquals(first, CompanionNamespace.create(7L, "agent-b", "device-a"));
        assertNotEquals(first, CompanionNamespace.create(7L, "agent-a", "device-b"));
        assertEquals(74, first.length());
        assertEquals("companion:", first.substring(0, 10));
    }
}
