package com.lanchat.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/** Opt-in transport. Credentials remain external; no default public relay. */
@Component
@ConfigurationProperties(prefix = "meshx.push")
public class PushConfiguration {
    private boolean enabled;
    private String fcmServiceAccountFile = "", fcmProjectId = "", fcmApplicationId = "", fcmApiKey = "", fcmSenderId = "";
    private String apnsKeyFile = "", apnsKeyId = "", apnsTeamId = "", apnsTopic = "";
    private boolean apnsSandbox = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getFcmServiceAccountFile() { return fcmServiceAccountFile; }
    public void setFcmServiceAccountFile(String v) { fcmServiceAccountFile = v; }
    public String getFcmProjectId() { return fcmProjectId; }
    public void setFcmProjectId(String v) { fcmProjectId = v; }
    public String getFcmApplicationId() { return fcmApplicationId; }
    public void setFcmApplicationId(String v) { fcmApplicationId = v; }
    public String getFcmApiKey() { return fcmApiKey; }
    public void setFcmApiKey(String v) { fcmApiKey = v; }
    public String getFcmSenderId() { return fcmSenderId; }
    public void setFcmSenderId(String v) { fcmSenderId = v; }
    public boolean fcmReady() { return enabled && !fcmServiceAccountFile.isBlank() && fcmProjectId.matches("[a-z][a-z0-9-]{4,62}") && !fcmApplicationId.isBlank() && !fcmApiKey.isBlank() && !fcmSenderId.isBlank(); }
    public String getApnsKeyFile() { return apnsKeyFile; }
    public void setApnsKeyFile(String value) { apnsKeyFile = value; }
    public String getApnsKeyId() { return apnsKeyId; }
    public void setApnsKeyId(String value) { apnsKeyId = value; }
    public String getApnsTeamId() { return apnsTeamId; }
    public void setApnsTeamId(String value) { apnsTeamId = value; }
    public String getApnsTopic() { return apnsTopic; }
    public void setApnsTopic(String value) { apnsTopic = value; }
    public boolean isApnsSandbox() { return apnsSandbox; }
    public void setApnsSandbox(boolean value) { apnsSandbox = value; }
    public boolean apnsReady() { return enabled && !apnsKeyFile.isBlank() && !apnsKeyId.isBlank() && !apnsTeamId.isBlank() && !apnsTopic.isBlank(); }
}
