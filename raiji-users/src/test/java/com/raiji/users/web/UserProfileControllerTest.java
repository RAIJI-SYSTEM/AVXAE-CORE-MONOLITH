
package com.raiji.users.web;

import com.raiji.users.domain.UserBusinessProfile;
import com.raiji.users.keycloak.KeycloakAdminService;
import com.raiji.users.service.UserBusinessProfileService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserProfileController.class)
@AutoConfigureMockMvc
@Import({
        UserProfileController.class,
        UserProfileControllerTest.TestConfig.class
})
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KeycloakAdminService keycloakAdminService;

    @MockitoBean
    private UserBusinessProfileService businessProfileService;

    private UserBusinessProfile profile(String phone, String photoUrl) {
        UserBusinessProfile profile = new UserBusinessProfile();
        profile.setSub("user-123");
        profile.setPhone(phone);
        profile.setPhotoUrl(photoUrl);
        return profile;
    }

    @Test
    void shouldUpdatePhoneAndPhotoAndReturn200() throws Exception {
        String phone = "+5585999999999";
        String photoUrl = "https://storage.example.com/foto.jpg";

        when(businessProfileService.upsert(
                eq("user-123"), eq(phone), eq(photoUrl)
        )).thenReturn(profile(phone, photoUrl));

        mockMvc.perform(patch("/api/users/me")
                        .with(jwt().jwt(token -> token
                                .subject("user-123")
                                .claim("email", "user@example.com")
                                .claim("name", "Usuário Teste")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "+5585999999999",
                                  "photoUrl": "https://storage.example.com/foto.jpg"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("user-123"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.fullName").value("Usuário Teste"))
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.photoUrl").value(photoUrl));

        verify(businessProfileService).upsert(
                "user-123", phone, photoUrl
        );
    }

    @Test
    void shouldReturn400WhenNoFieldsAreProvided() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .with(jwt().jwt(token -> token.subject("user-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenPhoneIsInvalid() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .with(jwt().jwt(token -> token.subject("user-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "85999999999"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldUpdateOnlyPhone() throws Exception {
        String phone = "+5585999999999";
        String existingPhoto = "https://storage.example.com/foto.jpg";

        when(businessProfileService.upsert(
                eq("user-123"), eq(phone), isNull()
        )).thenReturn(profile(phone, existingPhoto));

        mockMvc.perform(patch("/api/users/me")
                        .with(jwt().jwt(token -> token.subject("user-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "+5585999999999"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.photoUrl").value(existingPhoto));

        verify(businessProfileService).upsert(
                "user-123", phone, null
        );
    }

    @Test
    void shouldUpdateOnlyPhoto() throws Exception {
        String existingPhone = "+5585999999999";
        String photoUrl = "https://storage.example.com/nova-foto.jpg";

        when(businessProfileService.upsert(
                eq("user-123"), isNull(), eq(photoUrl)
        )).thenReturn(profile(existingPhone, photoUrl));

        mockMvc.perform(patch("/api/users/me")
                        .with(jwt().jwt(token -> token.subject("user-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "photoUrl": "https://storage.example.com/nova-foto.jpg"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(existingPhone))
                .andExpect(jsonPath("$.photoUrl").value(photoUrl));

        verify(businessProfileService).upsert(
                "user-123", null, photoUrl
        );
    }

    @Test
    void shouldRejectInvalidPhotoUrl() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .with(jwt().jwt(token -> token.subject("user-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "photoUrl": "isso-nao-e-uma-url"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                        "O JwtDecoder não deve ser chamado neste teste"
                );
            };
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http)
                throws Exception {

            return http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt())
                    .build();
        }
    }
}
