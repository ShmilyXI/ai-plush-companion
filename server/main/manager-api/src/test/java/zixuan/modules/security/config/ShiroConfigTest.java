package zixuan.modules.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import org.apache.shiro.session.mgt.SessionManager;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import zixuan.modules.security.oauth2.Oauth2Realm;
import zixuan.modules.sys.service.SysParamsService;

class ShiroConfigTest {

    @Test
    void beanPostProcessorFactoriesDoNotInstantiateShiroConfigOrBusinessDependenciesEarly() throws Exception {
        Method lifecycleFactory = ShiroConfig.class.getDeclaredMethod("lifecycleBeanPostProcessor");
        Method filterFactory = ShiroConfig.class.getDeclaredMethod(
                "shirFilter", WebSecurityManager.class, SysParamsService.class);

        assertTrue(Modifier.isStatic(lifecycleFactory.getModifiers()));
        assertTrue(BeanPostProcessor.class.isAssignableFrom(ShiroFilterFactoryBean.class));
        assertTrue(Modifier.isStatic(filterFactory.getModifiers()));
        Lazy securityManagerLazy = filterFactory.getParameters()[0].getAnnotation(Lazy.class);
        Lazy sysParamsServiceLazy = filterFactory.getParameters()[1].getAnnotation(Lazy.class);
        assertNotNull(securityManagerLazy);
        assertNotNull(sysParamsServiceLazy);
        assertTrue(securityManagerLazy.value());
        assertTrue(sysParamsServiceLazy.value());
    }

    @Test
    void authorizationAdvisorIsInfrastructureAndDefersItsSecurityManager() throws Exception {
        Method advisorFactory = ShiroConfig.class.getDeclaredMethod(
                "authorizationAttributeSourceAdvisor", WebSecurityManager.class);

        assertTrue(Modifier.isStatic(advisorFactory.getModifiers()));
        Lazy securityManagerLazy = advisorFactory.getParameters()[0].getAnnotation(Lazy.class);
        assertNotNull(securityManagerLazy);
        assertTrue(securityManagerLazy.value());
        assertEquals(BeanDefinition.ROLE_INFRASTRUCTURE, advisorFactory.getAnnotation(Role.class).value());
    }

    @Test
    void securityManagerRetainsTheWebSecurityContractRequiredByShiroFilter() throws Exception {
        Method securityManagerFactory = ShiroConfig.class.getDeclaredMethod(
                "securityManager", Oauth2Realm.class, SessionManager.class);

        assertEquals(WebSecurityManager.class, securityManagerFactory.getReturnType());
    }

    @Test
    void tencentDbMemoryModelProxyUsesServerSecretBeforeCatchAll() {
        ShiroFilterFactoryBean filter = ShiroConfig.shirFilter(
                org.mockito.Mockito.mock(WebSecurityManager.class),
                org.mockito.Mockito.mock(SysParamsService.class));
        var chains = filter.getFilterChainDefinitionMap();
        var paths = new ArrayList<>(chains.keySet());

        assertEquals("server", chains.get("/internal/tencentdb-memory-model/**"));
        assertTrue(paths.indexOf("/internal/tencentdb-memory-model/**") < paths.indexOf("/**"));
    }

    @Test
    void capabilityRuntimeApisUseServerSecretBeforeCatchAllOauth() {
        ShiroFilterFactoryBean filter = ShiroConfig.shirFilter(
                org.mockito.Mockito.mock(WebSecurityManager.class),
                org.mockito.Mockito.mock(SysParamsService.class));
        var chains = filter.getFilterChainDefinitionMap();
        var paths = new ArrayList<>(chains.keySet());

        assertEquals("server", chains.get("/internal/capabilities/**"));
        assertTrue(paths.indexOf("/internal/capabilities/**") < paths.indexOf("/**"));
    }

    @Test
    void wakeWordAssetsAreDownloadableByDevicesWithoutUserLogin() {
        ShiroFilterFactoryBean filter = ShiroConfig.shirFilter(
                org.mockito.Mockito.mock(WebSecurityManager.class),
                org.mockito.Mockito.mock(SysParamsService.class));
        var chains = filter.getFilterChainDefinitionMap();
        var paths = new ArrayList<>(chains.keySet());

        assertEquals("anon", chains.get("/wake-word-assets/**"));
        assertTrue(paths.indexOf("/wake-word-assets/**") < paths.indexOf("/**"));
    }

    @Test
    void webSessionExchangeIsAvailableBeforeOauthCatchAll() {
        ShiroFilterFactoryBean filter = ShiroConfig.shirFilter(
                org.mockito.Mockito.mock(WebSecurityManager.class),
                org.mockito.Mockito.mock(SysParamsService.class));
        var chains = filter.getFilterChainDefinitionMap();
        var paths = new ArrayList<>(chains.keySet());

        assertEquals("anon", chains.get("/api/v1/web-sessions/exchange"));
        assertTrue(paths.indexOf("/api/v1/web-sessions/exchange") < paths.indexOf("/**"));
    }
}
