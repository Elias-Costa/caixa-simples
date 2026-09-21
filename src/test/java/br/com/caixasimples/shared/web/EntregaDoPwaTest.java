package br.com.caixasimples.shared.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O servidor entrega o shell do PWA, por HTTP, contra o {@code index.html} do classpath de teste.
 *
 * <p>O shell é público: quem o pede não tem token, porque ainda vai fazer login por ele. O que
 * continua exigindo token é tudo sob {@code /api}, inclusive rota que não existe, que nunca
 * recebe o shell no lugar de um 404.
 */
class EntregaDoPwaTest extends TesteDeIntegracao {

    private static final String SENHA_VALIDA = "uma senha longa de teste";
    private static final String MARCADOR_DO_SHELL = "<title>shell de teste</title>";

    @Autowired
    private MockMvc http;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private AutenticadorDeTeste autenticador;

    @Test
    @DisplayName("a raiz encaminha para o shell, sem token")
    void raizEncaminhaParaOShell() throws Exception {
        http.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("o shell é servido sem token e sem cache HTTP")
    void shellSemTokenESemCache() throws Exception {
        http.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString(MARCADOR_DO_SHELL)))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    @DisplayName("o manifest sai como JSON e sem cache HTTP")
    void manifestComoJson() throws Exception {
        http.perform(get("/manifest.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    @DisplayName("rota do cliente, com ou sem segmento a mais, recebe o shell para o roteador decidir")
    void rotaDoClienteRecebeOShell() throws Exception {
        http.perform(get("/caixa"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(MARCADOR_DO_SHELL)));

        http.perform(get("/vendas/8f7b1c2e-0000-4000-8000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(MARCADOR_DO_SHELL)));
    }

    @Test
    @DisplayName("arquivo pedido pelo nome que não existe é 404, não o shell")
    void arquivoInexistenteEh404() throws Exception {
        http.perform(get("/assets/nao-existe.js")).andExpect(status().isNotFound());
        http.perform(get("/nao-existe.png")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("rota da API que não existe nunca recebe o shell: 401 sem token, 404 com token")
    void rotaDaApiInexistenteNaoRecebeOShell() throws Exception {
        http.perform(get("/api/nao-existe")).andExpect(status().isUnauthorized());
        http.perform(get("/api")).andExpect(status().isUnauthorized());

        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_VALIDA);

        http.perform(get("/api/nao-existe").with(autenticador.como(conta)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404));
    }
}
