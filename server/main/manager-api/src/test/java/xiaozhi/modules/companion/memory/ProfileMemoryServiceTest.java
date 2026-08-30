package xiaozhi.modules.companion.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProfileMemoryServiceTest {
    @Test
    void canonicalNamespaceDependsOnlyOnOwnerAndProfile() {
        assertEquals("companion:7:p1", ProfileMemoryNamespace.of(7L, "p1"));
        assertEquals("companion:7:p2", ProfileMemoryNamespace.of(7L, "p2"));
    }

    @Test
    void rejectsIncompleteIdentity() {
        assertThrows(IllegalArgumentException.class, () -> ProfileMemoryNamespace.of(null, "p1"));
        assertThrows(IllegalArgumentException.class, () -> ProfileMemoryNamespace.of(7L, " "));
    }
}
