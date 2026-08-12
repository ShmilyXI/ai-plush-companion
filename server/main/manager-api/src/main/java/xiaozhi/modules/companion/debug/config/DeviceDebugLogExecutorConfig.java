package xiaozhi.modules.companion.debug.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import xiaozhi.modules.companion.debug.service.DeviceDebugLogSanitizer;

@Configuration
public class DeviceDebugLogExecutorConfig {
    @Bean(name = "deviceDebugLogStreamExecutor", destroyMethod = "close")
    public ExecutorService deviceDebugLogStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public DeviceDebugLogSanitizer deviceDebugLogSanitizer() {
        return new DeviceDebugLogSanitizer();
    }
}
