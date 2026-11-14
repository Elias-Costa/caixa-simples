package br.com.caixasimples.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Toda resposta 5xx deixa uma linha em ERROR, que o alerta de erros da máquina conta; a recusa 4xx e a
 * saúde da instância não deixam.
 */
@ExtendWith(OutputCaptureExtension.class)
class RespostaComErroFilterTest extends TesteDeIntegracao {

    @Autowired
    private MockMvc http;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private AutenticadorDeTeste autenticador;

    private ContaCriada conta;
    private RequestPostProcessor autenticado;

    @BeforeEach
    void entrar() {
        conta = criador.criar("Loja da Esquina", "uma senha longa de teste");
        autenticado = autenticador.como(conta);
    }

    @Test
    @DisplayName("o 503 da indisponibilidade, que não tinha linha nenhuma, sai em ERROR com o status, sem a query nem a Conta")
    void indisponivelVaiAoLog(CapturedOutput saida) throws Exception {
        http.perform(get("/api/teste/erros/indisponivel").queryParam("busca", "Maria").with(autenticado))
                .andExpect(status().isServiceUnavailable());

        String linha = linhaCom(saida, "Resposta 503 em GET /api/teste/erros/indisponivel");
        assertThat(linha).contains(" ERROR ").doesNotContain("Maria").doesNotContain(conta.contaId().toString());
    }

    @Test
    @DisplayName("o 500 sai com a linha da pilha e com a do status")
    void inesperadoTemAsDuasLinhas(CapturedOutput saida) throws Exception {
        http.perform(get("/api/teste/erros/inesperado").with(autenticado))
                .andExpect(status().isInternalServerError());

        assertThat(linhaCom(saida, "Erro inesperado em GET /api/teste/erros/inesperado")).contains(" ERROR ");
        assertThat(linhaCom(saida, "Resposta 500 em GET /api/teste/erros/inesperado")).contains(" ERROR ");
    }

    @Test
    @DisplayName("as recusas 4xx não viram linha de erro")
    void recusaNaoVaiAoLog(CapturedOutput saida) throws Exception {
        http.perform(get("/api/teste/erros/estado").with(autenticado)).andExpect(status().isConflict());
        http.perform(get("/api/teste/erros/nao-encontrado").with(autenticado)).andExpect(status().isNotFound());
        http.perform(get("/api/teste/erros/argumento")).andExpect(status().isUnauthorized());

        assertThat(saida.getAll()).doesNotContain("Resposta 4");
    }

    @Test
    @DisplayName("o 503 da saúde da instância fica de fora: ela tem o sinal de vida, e a troca da aplicação viraria alerta")
    void saudeFicaDeFora(CapturedOutput saida) throws Exception {
        MockHttpServletRequest requisicao = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        requisicao.setServletPath("/actuator/health/readiness");

        new RespostaComErroFilter().doFilter(requisicao, new MockHttpServletResponse(),
                (pedido, resposta) -> ((MockHttpServletResponse) resposta).setStatus(503));

        assertThat(saida.getAll()).doesNotContain("Resposta 503");
    }

    private static String linhaCom(CapturedOutput saida, String trecho) {
        return saida.getAll().lines().filter(linha -> linha.contains(trecho)).findFirst()
                .orElseThrow(() -> new AssertionError("sem a linha: " + trecho));
    }
}
