package br.com.caixasimples.contas.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

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

    /**
     * Política de conteúdo: tudo da própria origem, nada embutido no HTML e nenhuma moldura.
     *
     * <p>O token fica no aparelho, ao alcance de qualquer script da origem, porque é o que deixa o
     * aplicativo abrir sem rede. Esta política limita o estrago de um script injetado no futuro, por
     * dependência ou por código novo: ele não carrega nada de fora, não envia o token para fora, e a
     * página não pode ser posta dentro de outro site. O build do aplicativo não tem script nem
     * estilo embutido, e o estilo que o React aplica pela propriedade de estilo do elemento passa
     * pelo CSSOM, que a política não barra. No desenvolvimento, quem serve a página é o servidor do
     * Vite, e esta política não chega a ela.
     */
    private static final String POLITICA_DE_CONTEUDO = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self'",
            "connect-src 'self'",
            "font-src 'self'",
            "manifest-src 'self'",
            "worker-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /**
     * A área de transferência e o compartilhamento servem ao copia e cola do Pix, ao pedido de
     * plano e ao comprovante, e já valeriam para a própria origem sem esta linha. O que ela
     * acrescenta é negar o que o aplicativo não usa: um script injetado não consegue pedir câmera,
     * microfone ou localização.
     */
    private static final String POLITICA_DE_PERMISSOES = String.join(", ",
            "camera=()",
            "microphone=()",
            "geolocation=()",
            "payment=()",
            "usb=()",
            "clipboard-write=(self)",
            "web-share=(self)");

    /**
     * Só existe com servidor web. A execução avulsa que cria uma Conta sobe a aplicação sem ele,
     * para terminar sozinha quando a Conta estiver criada, e sem servidor não há requisição para
     * filtrar nem o construtor da cadeia.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain filterChain(HttpSecurity http, IdentidadeDoTokenFilter identidadeDoToken,
            RenovacaoDeTokenFilter renovacaoDeToken) throws Exception {

        return http
                // API sem sessão e sem formulário: CSRF por token de sessão não se aplica.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // Somam-se aos que o Spring Security já escreve em toda resposta, como o HSTS e a
                // recusa de moldura. Saem também no shell e nos arquivos do aplicativo, que passam
                // por esta cadeia como a API. Nenhum Referer sai, nem para a própria origem: o
                // servidor não o lê, e o endereço da tela não tem por que ir a outro site.
                .headers(cabecalhos -> cabecalhos
                        .contentSecurityPolicy(csp -> csp.policyDirectives(POLITICA_DE_CONTEUDO))
                        .referrerPolicy(referer -> referer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(
                                permissoes -> permissoes.policy(POLITICA_DE_PERMISSOES)))
                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/pix/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Fora de /api só existe o aplicativo: o shell, os scripts, o manifest, o
                        // service worker e os ícones, que são públicos por natureza. Todo dado
                        // sai por /api, com token, então liberar o resto não expõe nada.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // A identidade depende da autenticação já resolvida, então vem depois do filtro
                // que a resolve; a renovação depende da identidade, então vem depois dela.
                .addFilterAfter(identidadeDoToken, BasicAuthenticationFilter.class)
                .addFilterAfter(renovacaoDeToken, IdentidadeDoTokenFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
