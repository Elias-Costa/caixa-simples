package br.com.caixasimples.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * O erro do aplicativo chega por uma rota autenticada e vira uma linha em ERROR só com o que localiza o
 * defeito, sem nada que forje outra linha e sem a Conta, o usuário ou a origem.
 */
@ExtendWith(OutputCaptureExtension.class)
class ErroDoAplicativoHttpTest extends TesteDeIntegracao {

    private static final String ROTA = "/api/erros-do-aplicativo";

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper json;

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

    private static Map<String, Object> relato() {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("tipo", "TypeError");
        corpo.put("local", "assets/index-B2x9.js:1:23456");
        corpo.put("tela", "/vender");
        corpo.put("versao", "0a557ae");
        return corpo;
    }

    private ResultActions enviar(Map<String, Object> corpo, RequestPostProcessor quem) throws Exception {
        return http.perform(post(ROTA).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))
                .with(quem)
                .with(requisicao -> {
                    requisicao.setRemoteAddr("203.0.113.80");
                    return requisicao;
                }));
    }

    @Test
    @DisplayName("o relato vira uma linha em ERROR com os quatro campos, sem Conta, usuário nem origem")
    void relatoViraLinhaDeErro(CapturedOutput saida) throws Exception {
        enviar(relato(), autenticado).andExpect(status().isNoContent());

        String linha = saida.getAll().lines()
                .filter(texto -> texto.contains("Erro no aplicativo")).findFirst().orElseThrow();
        assertThat(linha)
                .contains(" ERROR ")
                .endsWith("Erro no aplicativo tipo=TypeError local=assets/index-B2x9.js:1:23456 tela=/vender"
                        + " versao=0a557ae")
                .doesNotContain(conta.contaId().toString())
                .doesNotContain(conta.usuarioId().toString())
                .doesNotContain("203.0.113.80");
    }

    @Test
    @DisplayName("espaço ou quebra de linha num campo é recusado com 400 e não chega ao log")
    void caractereForaDoConjuntoEhRecusado(CapturedOutput saida) throws Exception {
        Map<String, Object> comEspaco = relato();
        comEspaco.put("tipo", "Type Error");
        enviar(comEspaco, autenticado).andExpect(status().isBadRequest());

        Map<String, Object> comQuebra = relato();
        comQuebra.put("local", "assets/index.js:1:2\n2026-11-13T10:00:00Z ERROR forjado");
        enviar(comQuebra, autenticado).andExpect(status().isBadRequest());

        Map<String, Object> semTela = relato();
        semTela.remove("tela");
        enviar(semTela, autenticado).andExpect(status().isBadRequest());

        assertThat(saida.getAll()).doesNotContain("Erro no aplicativo").doesNotContain("forjado");
    }

    @Test
    @DisplayName("sem token, 401, e nada no log")
    void semTokenEhRecusado(CapturedOutput saida) throws Exception {
        enviar(relato(), requisicao -> requisicao).andExpect(status().isUnauthorized());

        assertThat(saida.getAll()).doesNotContain("Erro no aplicativo");
    }

    @Test
    @DisplayName("acima de 2 KB, 413 antes de ler o corpo")
    void corpoGrandeEhRecusado(CapturedOutput saida) throws Exception {
        Map<String, Object> grande = relato();
        grande.put("tipo", "A".repeat(3000));
        enviar(grande, autenticado).andExpect(status().isContentTooLarge());

        assertThat(saida.getAll()).doesNotContain("Erro no aplicativo");
    }
}
