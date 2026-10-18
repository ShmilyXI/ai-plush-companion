package zixuan.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zixuan.common.exception.RenException;
import zixuan.modules.companion.capability.service.impl.CapabilityServiceImpl;

class CapabilityServiceContractTest {
    @Test
    void runtimeBundleRequiresOwnerVersionNamespaceAndProjectionFields() {
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("agent_id", "agent-a");
        bundle.put("agent_version_no", 4);
        bundle.put("profile_memory_namespace", "companion:7:agent-a");
        bundle.put("skills", Map.of());
        bundle.put("tools", Map.of());
        bundle.put("model_refs", Map.of("LLM", "model-a"));
        bundle.put("voice_ref", "voice-a");
        bundle.put("source_policy", Map.of("app", "public"));

        assertDoesNotThrow(() -> CapabilityServiceImpl.validateRuntimeBundle(bundle));
        bundle.remove("tools");
        assertThrows(RenException.class, () -> CapabilityServiceImpl.validateRuntimeBundle(bundle));
    }
}
