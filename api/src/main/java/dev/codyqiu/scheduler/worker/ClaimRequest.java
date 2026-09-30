package dev.codyqiu.scheduler.worker;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ClaimRequest(
		@NotNull
		@Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "must be 1-64 characters: letters, digits, '.', '_' or '-'")
		String workerId) {
}
