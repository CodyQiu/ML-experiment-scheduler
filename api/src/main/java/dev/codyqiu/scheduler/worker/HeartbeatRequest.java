package dev.codyqiu.scheduler.worker;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record HeartbeatRequest(@NotNull UUID attemptId) {
}
