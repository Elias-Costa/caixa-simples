package br.com.caixasimples.relatorios.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Recebimento;
import br.com.caixasimples.vendas.domain.Venda;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * O contrato HTTP da conferência do cartão: a forma da resposta, o filtro de operador, o isolamento
 * entre Contas e as recusas (RF24, RNF05). As regras da lista têm os cenários contra o banco no
 * teste do caso de uso; aqui se prova o que a rota acrescenta.
 */
class ConferenciaDoCartaoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SessaoCaixaService caixas;
    @Autowired ProdutoService produtos;
    @Autowired CriadorDeVendaDeTeste vendas;
    @Autowired ClienteService clientes;

    @Test
    void administradorListaOCartaoDoDiaComONsuOuAFaltaDele() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada outra = criador.criar("Loja da Esquina", SENHA);
        criador.contratar(outra.contaId(), Plano.CAIXA_SIMPLES);
        UUID caixa = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID caixaDaOutra = outra.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID comNsu = vendaNoCartao(conta, caixa, StatusVenda.CONCLUIDA, LocalTime.of(9, 0),
                "004512");
        UUID semNsu = vendaNoCartao(conta, caixa, StatusVenda.CANCELADA, LocalTime.of(10, 30),
                null);
        vendaNoCartao(outra, caixaDaOutra, StatusVenda.CONCLUIDA, LocalTime.of(9, 30), "778899");
        UUID fiadoDaOutra = fiadoRecebidoNoCartao(outra, caixaDaOutra, LocalTime.of(11, 0), "445566");

        conferencia(conta, DIA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dia").value(DIA.toString()))
                .andExpect(jsonPath("$.lancamentos.length()").value(2))
                .andExpect(jsonPath("$.lancamentos[0].vendaId").value(comNsu.toString()))
                .andExpect(jsonPath("$.lancamentos[0].origem").value("PARCELA"))
                .andExpect(jsonPath("$.lancamentos[0].situacaoDaVenda").value("CONCLUIDA"))
                .andExpect(jsonPath("$.lancamentos[0].valor").value(12.5))
                .andExpect(jsonPath("$.lancamentos[0].operadorId")
                        .value(conta.usuarioId().toString()))
                .andExpect(jsonPath("$.lancamentos[0].lancadoEm")
                        .value(noBalcao(LocalTime.of(9, 0)).toString()))
                .andExpect(jsonPath("$.lancamentos[0].nsu").value("004512"))
                .andExpect(jsonPath("$.lancamentos[1].vendaId").value(semNsu.toString()))
                .andExpect(jsonPath("$.lancamentos[1].situacaoDaVenda").value("CANCELADA"))
                .andExpect(jsonPath("$.lancamentos[1].nsu").doesNotExist());

        conferencia(conta, DIA, conta.usuarioId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lancamentos.length()").value(2));
        conferencia(conta, DIA, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lancamentos.length()").value(0));
        conferencia(conta, DIA.plusDays(1), null)
                .andExpect(jsonPath("$.lancamentos.length()").value(0));
        // A outra Conta vê só a própria venda e o próprio fiado recebido, e o operador desta não
        // traz nada lá (RNF05).
        conferencia(outra, DIA, null)
                .andExpect(jsonPath("$.lancamentos.length()").value(2))
                .andExpect(jsonPath("$.lancamentos[0].nsu").value("778899"))
                .andExpect(jsonPath("$.lancamentos[1].origem").value("RECEBIMENTO"))
                .andExpect(jsonPath("$.lancamentos[1].vendaId").value(fiadoDaOutra.toString()))
                .andExpect(jsonPath("$.lancamentos[1].nsu").value("445566"));
        conferencia(outra, DIA, conta.usuarioId())
                .andExpect(jsonPath("$.lancamentos.length()").value(0));
    }

    @Test
    void recusaOperadorPlanoSemRelatorioEParametroInvalido() throws Exception {
        ContaCriada conta = criador.criar("Padaria Aurora", SENHA);
        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada operador = criador.criar("Ponto do Operador", SENHA, Perfil.OPERADOR, true);
        criador.contratar(operador.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada gratis = criador.criar("Banca Aurora", SENHA);

        conferencia(operador, DIA, null).andExpect(status().isForbidden());
        conferencia(gratis, DIA, null).andExpect(status().isConflict());
        http.perform(get("/api/relatorios/conferencia-do-cartao")
                        .with(autenticador.como(conta)))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/conferencia-do-cartao").param("dia", "15/09/2026")
                        .with(autenticador.como(conta)))
                .andExpect(status().isBadRequest());
        http.perform(comDia(get("/api/relatorios/conferencia-do-cartao"), DIA)
                        .param("operadorId", "nao-e-um-id")
                        .with(autenticador.como(conta)))
                .andExpect(status().isBadRequest());
        http.perform(comDia(get("/api/relatorios/conferencia-do-cartao"), DIA))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions conferencia(ContaCriada conta, LocalDate dia, UUID operadorId)
            throws Exception {
        MockHttpServletRequestBuilder pedido = comDia(
                get("/api/relatorios/conferencia-do-cartao"), dia);
        if (operadorId != null) {
            pedido = pedido.param("operadorId", operadorId.toString());
        }
        return http.perform(pedido.with(autenticador.como(conta)));
    }

    private static MockHttpServletRequestBuilder comDia(MockHttpServletRequestBuilder pedido,
            LocalDate dia) {
        return pedido.param("dia", dia.toString());
    }

    /** Uma venda de 12,50 paga no cartão na hora dada do dia, gravada no caixa do administrador. */
    private UUID vendaNoCartao(ContaCriada conta, UUID caixa, StatusVenda status, LocalTime hora,
            String nsu) {
        UUID produto = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café " + UUID.randomUUID(), Money.de("12.50"), null, null,
                        "un", null)));
        Instant instante = noBalcao(hora);
        ItemVenda item = new ItemVenda(UUID.randomUUID(), produto, BigDecimal.ONE,
                Money.de("12.50"), Money.ZERO, instante);
        Pagamento cartao = new Pagamento(UUID.randomUUID(), FormaPagamento.CARTAO,
                Money.de("12.50"), StatusPagamento.CONFIRMADO, Money.ZERO, instante, null, nsu);
        Venda venda = Venda.reconstituir(UUID.randomUUID(), caixa, conta.usuarioId(), null,
                status, Money.de("12.50"), Money.ZERO, instante, instante, List.of(item),
                List.of(cartao));
        return vendas.gravar(conta.contaId(), venda);
    }

    /**
     * Uma venda fiada de 12,50, concluída na véspera, com um recebimento de 5,00 no cartão na hora
     * dada do dia, no caixa informado.
     */
    private UUID fiadoRecebidoNoCartao(ContaCriada conta, UUID caixa, LocalTime hora, String nsu) {
        UUID produto = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Bolo " + UUID.randomUUID(), Money.de("12.50"), null, null,
                        "un", null)));
        UUID cliente = conta.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Lia", null)));
        Instant vespera = DIA.minusDays(1).atTime(LocalTime.NOON)
                .atZone(FusoDeReferencia.DO_BALCAO).toInstant();
        ItemVenda item = new ItemVenda(UUID.randomUUID(), produto, BigDecimal.ONE,
                Money.de("12.50"), Money.ZERO, vespera);
        Pagamento fiado = new Pagamento(UUID.randomUUID(), FormaPagamento.FIADO, Money.de("12.50"),
                StatusPagamento.PENDENTE, Money.ZERO, vespera);
        Recebimento recebido = new Recebimento(UUID.randomUUID(), caixa, Money.de("5.00"),
                FormaPagamento.CARTAO, noBalcao(hora), nsu);
        Venda venda = Venda.reconstituir(UUID.randomUUID(), caixa, conta.usuarioId(), cliente,
                StatusVenda.CONCLUIDA, Money.de("12.50"), Money.ZERO, vespera, vespera,
                List.of(item), List.of(fiado), List.of(recebido));
        return vendas.gravar(conta.contaId(), venda);
    }

    private static Instant noBalcao(LocalTime hora) {
        return DIA.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }
}
