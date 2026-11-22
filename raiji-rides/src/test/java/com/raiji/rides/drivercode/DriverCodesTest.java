package com.raiji.rides.drivercode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriverCodesTest {

    @Test
    @DisplayName("código: 8 dígitos, sempre o mesmo para a mesma conta e diferente entre contas")
    void codigo() {
        String a = DriverCodes.of("0b6c3f1e-1111-4a2b-9c3d-000000000001");
        assertThat(a).matches("\\d{8}");
        assertThat(DriverCodes.of("0b6c3f1e-1111-4a2b-9c3d-000000000001")).isEqualTo(a);
        assertThat(DriverCodes.of("0b6c3f1e-1111-4a2b-9c3d-000000000002")).isNotEqualTo(a);
        assertThat(DriverCodes.normalize("4821-3759")).isEqualTo("48213759");
    }
}
