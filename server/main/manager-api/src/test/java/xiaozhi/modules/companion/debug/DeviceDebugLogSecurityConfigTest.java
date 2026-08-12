package xiaozhi.modules.companion.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.junit.jupiter.api.Test;

import xiaozhi.modules.security.config.ShiroConfig;
import xiaozhi.modules.security.secret.ServerSecretFilter;
import xiaozhi.modules.sys.service.SysParamsService;

class DeviceDebugLogSecurityConfigTest {
    @Test
    void internalDebugLogRouteUsesExistingServerSecretFilterBeforeCatchAll() {
        ShiroFilterFactoryBean filter = ShiroConfig.shirFilter(
                mock(WebSecurityManager.class), mock(SysParamsService.class));
        Map<String, String> chains = filter.getFilterChainDefinitionMap();
        List<String> paths = new ArrayList<>(chains.keySet());

        assertEquals("server", chains.get("/internal/device-debug-logs/**"));
        assertTrue(paths.indexOf("/internal/device-debug-logs/**") < paths.indexOf("/**"));
        assertInstanceOf(ServerSecretFilter.class, filter.getFilters().get("server"));
    }
}
