package br.com.caixasimples.sincronizacao;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaService.ParcelaParaTela;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A parcela em cartão registrada sem rede e enviada no lote, diante da exigência do NSU: o
 * pagamento aconteceu, então ela é gravada, e a falta do NSU vira revisão para o administrador.
 */
class NsuNoLoteTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SincronizacaoService sincronizacao;
    @Autowired ProdutoService produtos;
    @Autowired ContaService contas;
    @Autowired VendaService vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("com a exigência, a parcela sem NSU é gravada com revisão na lista do administrador, e a com NSU não")
    void parcelaSemNsuViraRevisao() throws Exception {
        ContaCriada conta = criador.criar("Lanchonete Aurora", SENHA);
        conta.comoUsuario(() -> contas.definirNsuObrigatorio(true));
        Dia dia = vendaDoDia(conta);
        GestoDeTeste semNsu = GestoDeTeste.parcelaNoCartao(dia.vendaId(), "10.00", dia.item());
        GestoDeTeste emBranco = GestoDeTeste.parcelaNoCartaoComNsu(dia.vendaId(), "4.00", "  ",
                dia.item());
        GestoDeTeste comNsu = GestoDeTeste.parcelaNoCartaoComNsu(dia.vendaId(), "6.00",
                " 004512 ", dia.item());
        GestoDeTeste conclusao = GestoDeTeste.conclusao(dia.vendaId(), semNsu, emBranco, comNsu);

        List<ResultadoDaOperacao> resultados = enviar(conta, dia.comMais(semNsu, emBranco, comNsu,
                conclusao));

        assertThat(resultadoDe(resultados, semNsu).resultado())
                .isEqualTo(Resultado.APLICADA_COM_REVISAO);
        assertThat(resultadoDe(resultados, semNsu).detalhe())
                .contains("10.00", "sem o NSU que a Conta exige");
        assertThat(resultadoDe(resultados, emBranco).resultado())
                .isEqualTo(Resultado.APLICADA_COM_REVISAO);
        assertThat(resultadoDe(resultados, comNsu).resultado()).isEqualTo(Resultado.APLICADA);
        assertThat(resultadoDe(resultados, conclusao).resultado()).isEqualTo(Resultado.APLICADA);

        assertThat(conta.comoUsuario(() -> vendas.consultar(dia.vendaId())).parcelas())
                .extracting(ParcelaParaTela::id, ParcelaParaTela::nsu)
                .containsExactlyInAnyOrder(
                        tuple(semNsu.payload().get("pagamentoId"), null),
                        tuple(emBranco.payload().get("pagamentoId"), null),
                        tuple(comNsu.payload().get("pagamentoId"), "004512"));

        JsonNode pendentes = revisoesDeHoje(conta).path("pendentes");
        assertThat(pendentes).hasSize(2);
        assertThat(pendentes).extracting(item -> item.path("operacaoId").asText())
                .containsExactlyInAnyOrder(semNsu.operacaoId().toString(),
                        emBranco.operacaoId().toString());
        assertThat(pendentes).allSatisfy(item -> {
            assertThat(item.path("resultado").asText()).isEqualTo("APLICADA_COM_REVISAO");
            assertThat(item.path("detalhe").asText()).contains("NSU");
        });
    }

    @Test
    @DisplayName("sem a exigência, a parcela sem NSU é aplicada sem pendência, e a outra Conta não interfere")
    void semExigenciaNaoHaRevisao() throws Exception {
        ContaCriada conta = criador.criar("Lanchonete da Esquina", SENHA);
        ContaCriada outra = criador.criar("Lanchonete da Praça", SENHA);
        outra.comoUsuario(() -> contas.definirNsuObrigatorio(true));
        Dia dia = vendaDoDia(conta);
        GestoDeTeste semNsu = GestoDeTeste.parcelaNoCartao(dia.vendaId(), "20.00", dia.item());
        GestoDeTeste conclusao = GestoDeTeste.conclusao(dia.vendaId(), semNsu);

        List<ResultadoDaOperacao> resultados = enviar(conta, dia.comMais(semNsu, conclusao));

        assertThat(resultadoDe(resultados, semNsu).resultado()).isEqualTo(Resultado.APLICADA);
        assertThat(resultadoDe(resultados, conclusao).resultado()).isEqualTo(Resultado.APLICADA);
        assertThat(revisoesDeHoje(conta).path("pendentes")).isEmpty();
    }

    @Test
    @DisplayName("o NSU numa parcela que não é de cartão é recusado, como no caminho com rede")
    void nsuForaDoCartaoERecusado() throws Exception {
        ContaCriada conta = criador.criar("Lanchonete do Largo", SENHA);
        Dia dia = vendaDoDia(conta);
        GestoDeTeste emDinheiro = GestoDeTeste.de("venda.registrarPagamento", dia.vendaId(),
                conteudo("pagamentoId", UUID.randomUUID(), "forma", "DINHEIRO", "valor",
                        new BigDecimal("20.00"), "valorRecebido", new BigDecimal("20.00"),
                        "nsu", "004512"), dia.item());

        List<ResultadoDaOperacao> resultados = enviar(conta, dia.comMais(emDinheiro));

        assertThat(resultadoDe(resultados, emDinheiro).resultado())
                .isEqualTo(Resultado.NAO_APLICADA);
        assertThat(resultadoDe(resultados, emDinheiro).detalhe())
                .contains("NSU so existe em cartao");
        assertThat(conta.comoUsuario(() -> vendas.consultar(dia.vendaId())).parcelas()).isEmpty();
    }

    /** A abertura do caixa, o início e um item de 20,00 no preço vigente, todos sem rede. */
    private record Dia(UUID vendaId, List<GestoDeTeste> gestos, GestoDeTeste item) {

        List<GestoDeTeste> comMais(GestoDeTeste... mais) {
            List<GestoDeTeste> todos = new java.util.ArrayList<>(gestos);
            todos.addAll(List.of(mais));
            return todos;
        }
    }

    private Dia vendaDoDia(ContaCriada conta) {
        UUID produtoId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Pastel", Money.de("20.00"), null, null, "un", null)));
        UUID sessaoId = UUID.randomUUID();
        UUID vendaId = UUID.randomUUID();
        GestoDeTeste abertura = GestoDeTeste.abertura(sessaoId, "0");
        GestoDeTeste inicio = GestoDeTeste.inicio(vendaId, sessaoId, abertura);
        GestoDeTeste item = GestoDeTeste.item(vendaId, UUID.randomUUID(), produtoId, "1", "20.00",
                inicio);
        return new Dia(vendaId, List.of(abertura, inicio, item), item);
    }

    private List<ResultadoDaOperacao> enviar(ContaCriada conta, List<GestoDeTeste> gestos) {
        return conta.comoUsuario(() -> sincronizacao.sincronizar(
                gestos.stream().map(gesto -> gesto.recebida(json)).toList()));
    }

    private static ResultadoDaOperacao resultadoDe(List<ResultadoDaOperacao> resultados,
            GestoDeTeste gesto) {
        return resultados.stream()
                .filter(resultado -> resultado.operacaoId().equals(gesto.operacaoId()))
                .findFirst().orElseThrow();
    }

    private JsonNode revisoesDeHoje(ContaCriada conta) throws Exception {
        String corpo = http.perform(get("/api/sincronizacao/revisoes")
                        .param("dia", LocalDate.now(FusoDeReferencia.DO_BALCAO).toString())
                        .with(autenticador.como(conta)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo);
    }
}
