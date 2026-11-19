package com.raiji.deliveries.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.raiji.deliveries.repository.DeliveryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrackingCodeGeneratorTest {

    @Mock
    private DeliveryRepository deliveries;

    private TrackingCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new TrackingCodeGenerator(deliveries);
    }

    @Test
    void generateDeveRespeitarFormato() {
        when(deliveries.existsByTrackingCode(anyString()))
                .thenReturn(false);

        String code = generator.generate();

        assertTrue(Pattern.matches(
                "^RM-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{2}$",
                code
        ));
    }

    @Test
    void generateDeveTentarNovamenteQuandoEncontrarColisao() {
        when(deliveries.existsByTrackingCode(anyString()))
                .thenReturn(true, false);

        String code = generator.generate();

        assertTrue(code.matches(
                "^RM-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{2}$"
        ));

        verify(deliveries, times(2)).existsByTrackingCode(anyString());
    }

    @Test
    void generateDeveFalharDepoisDeCemColisoes() {
        when(deliveries.existsByTrackingCode(anyString()))
                .thenReturn(true);

        assertThrows(
                IllegalStateException.class,
                () -> generator.generate()
        );

        verify(deliveries, times(100))
                .existsByTrackingCode(anyString());
    }

    @Test
    void generateDeveProduzirDezMilCodigosUnicos() {
        when(deliveries.existsByTrackingCode(anyString()))
                .thenReturn(false);

        Set<String> codes = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }

        assertEquals(10_000, codes.size());
    }
}
