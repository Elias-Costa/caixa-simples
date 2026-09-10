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
 * Cadeia de segurança da aplicação (RNF06).
 *
 * <p>Fica no módulo {@code contas} porque identidade e autenticação são o Bounded Context dele. O
 * encanamento de multi-tenancy continua em {@code shared/internal}, que é transversal.
 *
 * <p>Sem sessão: o estado da autenticação vive inteiro no token, e é isso que permite ao cliente
 * operar offline com o token guardado no dispositivo.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TenantDoTokenFilter tenantDoToken,
            RenovacaoDeTokenFilter renovacaoDeToken) throws Exception {

        return http
                // API sem sessão e sem formulário: CSRF por token de sessão não se aplica.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // Os dois dependem da autenticação já resolvida, então vêm depois do filtro que a
                // resolve.
                .addFilterAfter(tenantDoToken, BasicAuthenticationFilter.class)
                .addFilterAfter(renovacaoDeToken, BasicAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
