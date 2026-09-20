package com.lanchat.push;

public record PushSubscriptionRequest(String platform, String endpoint, String scope) {}
