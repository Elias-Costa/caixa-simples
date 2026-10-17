package br.com.caixasimples.sincronizacao;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O texto acima da coluna chega também pelo lote, que chama os mesmos casos de uso da rota. O
 * gesto vira recusa com a mensagem do domínio, e não com a de conflito com outro registro, que
 * mandaria procurar um repetido que não existe.
 */
class TextoLongoNoLoteTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("produto, cliente e sangria com texto longo são recusados com a mensagem, e no limite são aplicados")
    void textoLongoViraRecusaComAMensagem(CapturedOutput log) throws Exception {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        UUID sessaoId = UUID.randomUUID();
        GestoDeTeste abertura = GestoDeTeste.abertura(sessaoId, "100.00");

        GestoDeTeste nomeLongo = produto("n".repeat(121), null);
        GestoDeTeste unidadeLonga = produto("Arroz", "u".repeat(61));
        GestoDeTeste produtoNoLimite = produto("n".repeat(120), "u".repeat(60));
        GestoDeTeste contatoLongo = cliente("Dona Aurora", "c".repeat(181));
        GestoDeTeste clienteNoLimite = cliente("n".repeat(120), "c".repeat(180));
        GestoDeTeste sangriaLonga = sangria(sessaoId, "m".repeat(121), abertura);
        GestoDeTeste sangriaNoLimite = sangria(sessaoId, "m".repeat(120), abertura);

        JsonNode resposta = enviar(admin, List.of(abertura, nomeLongo, unidadeLonga,
                produtoNoLimite, contatoLongo, clienteNoLimite, sangriaLonga, sangriaNoLimite));

        recusado(resposta, nomeLongo, "nome do produto com 121 caracteres; o limite é 120.");
        recusado(resposta, unidadeLonga, "unidade do produto com 61 caracteres; o limite é 60.");
        recusado(resposta, contatoLongo, "contato do cliente com 181 caracteres; o limite é 180.");
        recusado(resposta, sangriaLonga, "motivo da sangria com 121 caracteres; o limite é 120.");
        for (GestoDeTeste aplicado : List.of(abertura, produtoNoLimite, clienteNoLimite,
                sangriaNoLimite)) {
            assertThat(resultadoDe(resposta, aplicado).path("resultado").asText())
                    .as(aplicado.tipo()).isEqualTo("APLICADA");
        }

        http.perform(get("/api/produtos").with(admin))
                .andExpect(jsonPath("$.length()").value(1));
        http.perform(get("/api/clientes").with(admin))
                .andExpect(jsonPath("$.length()").value(1));
        http.perform(get("/api/caixa/sessoes/aberta").with(admin))
                .andExpect(jsonPath("$.valorFechamentoEsperado").value(90.0));

        // A recusa prevista vai ao log como aviso, só com a classe da exceção, sem a pilha.
        assertThat(log).contains("operacao " + nomeLongo.operacaoId()
                + " recusada: IllegalArgumentException");
        assertThat(log).doesNotContain("recusada por excecao inesperada",
                "conflito com registro existente", "Erro inesperado");
    }

    private static GestoDeTeste produto(String nome, String unidade) {
        return GestoDeTeste.de("produto.criar", UUID.randomUUID(), conteudo("tipo", "PRODUTO",
                "nome", nome, "preco", new BigDecimal("5.00"), "unidade", unidade));
    }

    private static GestoDeTeste cliente(String nome, String contato) {
        return GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", nome, "contato", contato));
    }

    private static GestoDeTeste sangria(UUID sessaoId, String motivo, GestoDeTeste abertura) {
        return GestoDeTeste.de("caixa.sangrar", sessaoId, conteudo("valor",
                new BigDecimal("10.00"), "motivo", motivo, "criadoEm", Instant.now().toString()),
                abertura);
    }

    private static void recusado(JsonNode resposta, GestoDeTeste gesto, String mensagem) {
        JsonNode resultado = resultadoDe(resposta, gesto);
        assertThat(resultado.path("resultado").asText()).as(gesto.tipo())
                .isEqualTo("NAO_APLICADA");
        assertThat(resultado.path("detalhe").asText()).as(gesto.tipo()).isEqualTo(mensagem);
    }

    private JsonNode enviar(RequestPostProcessor quem, List<GestoDeTeste> gestos)
            throws Exception {
        String corpo = json.writeValueAsString(Map.of("operacoes",
                gestos.stream().map(GestoDeTeste::paraJson).toList()));
        return json.readTree(http.perform(post("/api/sincronizacao").with(quem)
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode resultadoDe(JsonNode resposta, GestoDeTeste gesto) {
        for (JsonNode resultado : resposta.path("resultados")) {
            if (resultado.path("operacaoId").asText().equals(gesto.operacaoId().toString())) {
                return resultado;
            }
        }
        throw new AssertionError("sem resultado para o gesto " + gesto.tipo());
    }
}
