package br.com.caixasimples.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * O contrato de erro da API, por HTTP, contra as rotas de {@link ControllerDeErrosDeTeste}.
 *
 * <p>Toda resposta de erro é Problem Details (RFC 9457), venha a exceção da aplicação ou do
 * próprio Spring MVC; o que muda é o status e o detalhe. É aqui que a recusa por perfil vira 403
 * pela primeira vez, o critério da etapa de perfis que ficou esperando um endpoint.
 */
class TratamentoDeErrosHttpTest extends TesteDeIntegracao {

    private static final String SENHA_VALIDA = "uma senha longa de teste";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc http;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private AutenticadorDeTeste autenticador;

    private RequestPostProcessor autenticado;

    @BeforeEach
    void entrar() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_VALIDA);
        autenticado = autenticador.como(conta);
    }

    @Test
    @DisplayName("recusa por perfil vira 403 com o motivo em detail")
    void acessoNegadoVira403() throws Exception {
        http.perform(get("/api/teste/erros/acesso-negado").with(autenticado))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").value("operacao restrita ao administrador"));
    }

    @Test
    @DisplayName("caso de uso sem usuário ou sem conta no contexto vira 401, sem detalhe")
    void semIdentidadeVira401() throws Exception {
        http.perform(get("/api/teste/erros/sem-usuario").with(autenticado))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").doesNotExist());

        http.perform(get("/api/teste/erros/sem-tenant").with(autenticado))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("argumento que o domínio recusa vira 400 com a mensagem da exceção")
    void argumentoInvalidoVira400() throws Exception {
        http.perform(get("/api/teste/erros/argumento").with(autenticado))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value(ControllerDeErrosDeTeste.MENSAGEM_DE_ARGUMENTO));
    }

    @Test
    @DisplayName("estado que o agregado recusa vira 409 com a mensagem da exceção")
    void estadoInvalidoVira409() throws Exception {
        http.perform(get("/api/teste/erros/estado").with(autenticado))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(ControllerDeErrosDeTeste.MENSAGEM_DE_ESTADO));
    }

    @Test
    @DisplayName("violação de validação vira 400 com a mensagem por campo")
    void validacaoVira400ComCampos() throws Exception {
        http.perform(post("/api/teste/erros/validacao").with(autenticado)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"  \", \"valor\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.campos.nome").isNotEmpty())
                .andExpect(jsonPath("$.campos.valor").isNotEmpty());
    }

    @Test
    @DisplayName("pedido válido passa pela validação e chega ao controller")
    void pedidoValidoPassa() throws Exception {
        http.perform(post("/api/teste/erros/validacao").with(autenticado)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"Cafe\", \"valor\": 12.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Cafe"))
                .andExpect(jsonPath("$.valor").value(12.50));
    }

    @Test
    @DisplayName("JSON ilegível, recusado pelo próprio Spring MVC, sai no mesmo formato")
    void corpoIlegivelSaiNoMesmoFormato() throws Exception {
        http.perform(post("/api/teste/erros/validacao").with(autenticado)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").isNotEmpty());
    }

    @Test
    @DisplayName("erro inesperado vira 500 sem a mensagem interna")
    void inesperadoVira500SemDetalhe() throws Exception {
        String corpo = http.perform(get("/api/teste/erros/inesperado").with(autenticado))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).doesNotContain(ControllerDeErrosDeTeste.MENSAGEM_INTERNA);
    }

    @Test
    @DisplayName("não encontrado é traduzido em 404 pelo tratador local do módulo")
    void naoEncontradoVira404PeloTratadorLocal() throws Exception {
        http.perform(get("/api/teste/erros/nao-encontrado").with(autenticado))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("recurso de teste nao encontrado"));
    }

    @Test
    @DisplayName("sem token, nem a rota de teste responde: a recusa vem antes do controller")
    void semTokenNaoChegaAoController() throws Exception {
        http.perform(get("/api/teste/erros/acesso-negado"))
                .andExpect(status().isUnauthorized());
    }
}
