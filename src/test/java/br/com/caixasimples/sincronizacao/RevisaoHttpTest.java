package br.com.caixasimples.sincronizacao;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A lista de revisões e recusas do administrador e a conferência de cada uma (RNF02, RNF05).
 *
 * <p>Os gestos são enviados pelo serviço como um segundo usuário da Conta, sem credencial, e a
 * lista é lida pelo administrador pela API: é o caso de quem confere o que outro aparelho enviou.
 */
class RevisaoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SincronizacaoService sincronizacao;

    @Test
    @DisplayName("o administrador vê a revisão e a recusa do atendente, confere uma vez só, e o reenvio não muda")
    void administradorConfereORevisadoDoAtendente() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        UsuarioCriado atendente = criador.criarOperadorEm(conta.contaId(), "Atendente");
        // Relógio uma hora adiantado: aplicada, mas o dia do registro precisa ser conferido.
        GestoDeTeste adiantado = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Maria", "contato", null))
                .registradoEm(umaHoraAdiantado());
        // Operador não cadastra produto: recusado.
        GestoDeTeste recusado = GestoDeTeste.de("produto.criar", UUID.randomUUID(),
                conteudo("tipo", "PRODUTO", "nome", "Bolo", "preco", new BigDecimal("8.00"),
                        "codigo", null, "categoria", null, "unidade", "un", "atributos",
                        Map.of()));
        GestoDeTeste semPendencia = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Joana", "contato", null));
        List<ResultadoDaOperacao> primeiroEnvio = enviarComo(atendente,
                List.of(adiantado, recusado, semPendencia));

        JsonNode antes = listar(admin, hoje());
        JsonNode revisao = daOperacao(antes.path("pendentes"), adiantado);
        assertThat(revisao.path("resultado").asText()).isEqualTo("APLICADA_COM_REVISAO");
        assertThat(revisao.path("usuarioId").asText())
                .isEqualTo(atendente.usuarioId().toString());
        assertThat(revisao.path("tipo").asText()).isEqualTo("cliente.criar");
        assertThat(revisao.path("payload").path("nome").asText()).isEqualTo("Maria");
        assertThat(revisao.path("detalhe").asText()).contains("relogio");
        assertThat(revisao.has("conferidaEm")).isFalse();
        JsonNode recusa = daOperacao(antes.path("pendentes"), recusado);
        assertThat(recusa.path("resultado").asText()).isEqualTo("NAO_APLICADA");
        assertThat(recusa.path("detalhe").asText()).contains("administrador");
        assertThat(recusa.path("payload").path("preco").decimalValue())
                .isEqualByComparingTo(new BigDecimal("8.00"));
        assertThat(antes.path("pendentes")).hasSize(2);
        assertThat(antes.path("conferidas")).isEmpty();

        conferir(admin, adiantado.operacaoId()).andExpect(status().isNoContent());

        JsonNode depois = listar(admin, hoje());
        assertThat(depois.path("pendentes")).hasSize(1);
        assertThat(daOperacao(depois.path("pendentes"), recusado)).isNotNull();
        JsonNode conferida = daOperacao(depois.path("conferidas"), adiantado);
        assertThat(conferida.path("conferidaPor").asText()).isEqualTo(conta.usuarioId().toString());
        String conferidaEm = conferida.path("conferidaEm").asText();
        assertThat(conferidaEm).isNotBlank();

        // Conferir de novo não troca a primeira conferência.
        conferir(admin, adiantado.operacaoId()).andExpect(status().isNoContent());
        assertThat(daOperacao(listar(admin, hoje()).path("conferidas"), adiantado)
                .path("conferidaEm").asText()).isEqualTo(conferidaEm);
        // Nem aparece noutro dia.
        assertThat(listar(admin, hoje().minusDays(1)).path("conferidas")).isEmpty();

        // O reenvio do lote inteiro, com a revisão já conferida, devolve o que foi gravado.
        assertThat(enviarComo(atendente, List.of(adiantado, recusado, semPendencia)))
                .isEqualTo(primeiroEnvio);
        assertThat(primeiroEnvio.get(0).resultado()).isEqualTo(Resultado.APLICADA_COM_REVISAO);
    }

    @Test
    @DisplayName("operador não lista nem confere; aplicada sem pendência responde 409; id desconhecido, 404")
    void permissaoEEstadosRecusados() throws Exception {
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        RequestPostProcessor tokenDoOperador = autenticador.como(operador);
        http.perform(get("/api/sincronizacao/revisoes").param("dia", hoje().toString())
                        .with(tokenDoOperador))
                .andExpect(status().isForbidden());
        conferir(tokenDoOperador, UUID.randomUUID()).andExpect(status().isForbidden());

        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        GestoDeTeste aplicada = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Ana", "contato", null));
        conta.comoUsuario(() -> sincronizacao.sincronizar(List.of(aplicada.recebida(json))));

        conferir(admin, aplicada.operacaoId()).andExpect(status().isConflict());
        conferir(admin, UUID.randomUUID()).andExpect(status().isNotFound());
        http.perform(get("/api/sincronizacao/revisoes").param("dia", hoje().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a revisão da Conta A não aparece nem é conferida pela Conta B (RNF05)")
    void revisaoNaoAtravessaConta() throws Exception {
        ContaCriada contaA = criador.criar("Loja A", SENHA);
        ContaCriada contaB = criador.criar("Loja B", SENHA);
        GestoDeTeste daContaA = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Bia", "contato", null))
                .registradoEm(umaHoraAdiantado());
        contaA.comoUsuario(() -> sincronizacao.sincronizar(List.of(daContaA.recebida(json))));

        RequestPostProcessor adminB = autenticador.como(contaB);
        assertThat(listar(adminB, hoje()).path("pendentes")).isEmpty();
        conferir(adminB, daContaA.operacaoId()).andExpect(status().isNotFound());

        JsonNode daA = listar(autenticador.como(contaA), hoje());
        assertThat(daOperacao(daA.path("pendentes"), daContaA).has("conferidaEm")).isFalse();
    }

    private List<ResultadoDaOperacao> enviarComo(UsuarioCriado quem, List<GestoDeTeste> gestos) {
        return quem.comoUsuario(() -> sincronizacao.sincronizar(
                gestos.stream().map(gesto -> gesto.recebida(json)).toList()));
    }

    private JsonNode listar(RequestPostProcessor quem, LocalDate dia) throws Exception {
        String corpo = http.perform(get("/api/sincronizacao/revisoes")
                        .param("dia", dia.toString()).with(quem))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo);
    }

    private ResultActions conferir(RequestPostProcessor quem, UUID operacaoId) throws Exception {
        return http.perform(post("/api/sincronizacao/revisoes/{id}/conferencia", operacaoId)
                .with(quem));
    }

    /** Em milissegundos, a precisão do relógio do navegador que grava o gesto. */
    private static Instant umaHoraAdiantado() {
        return Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);
    }

    private static LocalDate hoje() {
        return LocalDate.now(FusoDeReferencia.DO_BALCAO);
    }

    private static JsonNode daOperacao(JsonNode lista, GestoDeTeste gesto) {
        for (JsonNode item : lista) {
            if (item.path("operacaoId").asText().equals(gesto.operacaoId().toString())) {
                return item;
            }
        }
        throw new AssertionError("a lista nao tem a operacao " + gesto.tipo());
    }
}
