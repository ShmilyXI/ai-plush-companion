package zixuan.modules.companion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "companion.bootstrap")
public class CompanionBootstrapProperties {
    private boolean enabled;
    private String username;
    private String password;
    private String deviceMac;
}
