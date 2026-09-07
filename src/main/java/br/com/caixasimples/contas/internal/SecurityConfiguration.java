package br.com.caixasimples.contas.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Cadeia de seguranca da aplicacao (RNF06).
 *
 * <p>Fica no modulo {@code contas} porque identidade e autenticacao sao o Bounded Context dele
 * (decisao D4). O encanamento de multi-tenancy continua em {@code shared/internal}, que e
 * transversal.
 *
 * <p>Sem sessao: o estado da autenticacao vive inteiro no token, o que e o que permite o PWA
 * operar offline com o token guardado no dispositivo (A6).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TenantDoTokenFilter tenantDoToken,
            RenovacaoDeTokenFilter renovacaoDeToken) throws Exception {

        return http
                // API sem sessao e sem formulario: CSRF por token de sessao nao se aplica.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // Ambos dependem da autenticacao ja resolvida, entao vem depois do filtro que a resolve.
                .addFilterAfter(tenantDoToken, BasicAuthenticationFilter.class)
                .addFilterAfter(renovacaoDeToken, BasicAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
