package com.lanchat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Security policy and one-time bootstrap settings for an organization-owned node. */
@Data
@Component
@ConfigurationProperties(prefix = "lanchat.private-deployment")
public class LanChatPrivateDeploymentProperties {

    private boolean enabled;
    private boolean selfRegistrationEnabled = true;
    private String bootstrapAdminPassword = "";
    private String bootstrapAdminNickname = "系统管理员";

    public boolean allowsSelfRegistration() {
        return !enabled && selfRegistrationEnabled;
    }
}
