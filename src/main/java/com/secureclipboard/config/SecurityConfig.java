package com.secureclipboard.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Instant;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // Delegate CORS headers to WebConfig.addCorsMappings() via Spring MVC
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .securityContext(ctx -> ctx
                .securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(auth -> auth
                // Auth endpoints — /me is also open so guests don't get a 401 on every page load
                .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout", "/api/auth/me").permitAll()
                // Guest clipboard flow — fully open, no auth required
                .requestMatchers("/api/clipboards/**", "/api/transfers/**").permitAll()
                // WebSocket
                .requestMatchers("/ws/**").permitAll()
                // Telegram webhook receives external POST from Telegram servers
                .requestMatchers("/telegram/**").permitAll()
                // SPA shell routes
                .requestMatchers(
                    "/", "/index.html",
                    "/create", "/open", "/c/**",
                    "/login", "/register", "/dashboard",
                    "/privacy.html", "/terms.html", "/refund.html"
                ).permitAll()
                // Static assets
                .requestMatchers("/css/**", "/js/**", "/img/**").permitAll()
                .requestMatchers("/*.js", "/*.json", "/*.svg", "/*.ico",
                                 "/*.xml", "/*.txt", "/*.webmanifest").permitAll()
                // Everything else (e.g. /api/users/**) requires a session
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                        "{\"status\":401,\"error\":\"Unauthorized\"," +
                        "\"message\":\"Authentication required\"," +
                        "\"timestamp\":\"" + Instant.now() + "\"}");
                })
                .accessDeniedHandler((request, response, e) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                        "{\"status\":403,\"error\":\"Forbidden\"," +
                        "\"message\":\"" + e.getMessage() + "\"," +
                        "\"timestamp\":\"" + Instant.now() + "\"}");
                })
            );
        return http.build();
    }
}
