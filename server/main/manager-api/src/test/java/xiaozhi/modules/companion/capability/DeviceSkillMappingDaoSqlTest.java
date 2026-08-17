package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;

import xiaozhi.modules.companion.capability.dao.DeviceSkillMappingDao;

class DeviceSkillMappingDaoSqlTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "bumpLatestDeviceConfigVersions",
            "bumpAllDeviceConfigVersions",
            "bumpEveryEnabledDeviceConfigVersion"
    })
    void bulkVersionUpdatesAreAcceptedByBlockAttackProtection(String methodName) throws Exception {
        Method method = Arrays.stream(DeviceSkillMappingDao.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        String sql = String.join(" ", method.getAnnotation(Update.class).value())
                .replaceAll("#\\{[^}]+}", "?");

        BlockAttackInnerInterceptor interceptor = new BlockAttackInnerInterceptor();

        assertDoesNotThrow(() -> interceptor.parserMulti(sql, null));
    }
}
