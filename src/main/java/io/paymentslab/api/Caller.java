package io.paymentslab.api;

import java.util.UUID;

/** An authenticated API client. Handlers get it from the auth filter, never from the request. */
public record Caller(UUID id, String name, CallerRole role) {}
