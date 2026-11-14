package com.raiji.deliveries.dto;

import jakarta.validation.constraints.Size;

public record ReasonRequest(@Size(max = 500) String reason) {
}
