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
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
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
 * A conferência do Pix contra o banco: o Pix lançado à mão e o integrado, a cobrança ainda não paga
 * marcada e a recusada fora, o recebimento de fiado em Pix, o cliente, o filtro de operador, o
 * isolamento entre Contas e as recusas (RF24, RNF05).
 */
class ConferenciaDoPixHttpTest extends TesteDeIntegracao {

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
    void administradorListaOPixDoDiaDeVendaEDeFiado() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        UsuarioCriado atendente = criador.criarOperadorEm(conta.contaId(), "Atendente");
        ContaCriada outra = criador.criar("Loja da Esquina", SENHA);
        criador.contratar(outra.contaId(), Plano.COMPLETO);
        UUID caixa = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID caixaDaAtendente = atendente.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID caixaDaOutra = outra.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID lia = conta.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Lia", null)));

        UUID aMao = venda(conta, caixa, conta.usuarioId(), lia, StatusVenda.CONCLUIDA,
                LocalTime.of(9, 0), StatusPagamento.CONFIRMADO, null);
        UUID integradaPaga = venda(conta, caixaDaAtendente, atendente.usuarioId(), null,
                StatusVenda.CONCLUIDA, LocalTime.of(10, 0), StatusPagamento.CONFIRMADO,
                cobranca(CobrancaPix.Estado.DISPONIVEL));
        UUID aguardando = venda(conta, caixa, conta.usuarioId(), null, StatusVenda.ABERTA,
                LocalTime.of(11, 0), StatusPagamento.PENDENTE, cobranca(CobrancaPix.Estado.AGUARDANDO));
        venda(conta, caixa, conta.usuarioId(), null, StatusVenda.ABERTA, LocalTime.of(11, 30),
                StatusPagamento.RECUSADO, cobranca(CobrancaPix.Estado.AGUARDANDO));
        UUID fiado = fiadoRecebidoNoPix(conta, caixaDaAtendente, lia, LocalTime.of(12, 0));
        vendaNoCartao(conta, caixa, LocalTime.of(13, 0));
        UUID daOutra = venda(outra, caixaDaOutra, outra.usuarioId(), null, StatusVenda.CONCLUIDA,
                LocalTime.of(9, 30), StatusPagamento.CONFIRMADO, null);

        conferencia(conta, DIA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dia").value(DIA.toString()))
                .andExpect(jsonPath("$.lancamentos.length()").value(4))
                .andExpect(jsonPath("$.lancamentos[0].vendaId").value(aMao.toString()))
                .andExpect(jsonPath("$.lancamentos[0].origem").value("PARCELA"))
                .andExpect(jsonPath("$.lancamentos[0].valor").value(12.5))
                .andExpect(jsonPath("$.lancamentos[0].lancadoEm")
                        .value(noBalcao(LocalTime.of(9, 0)).toString()))
                .andExpect(jsonPath("$.lancamentos[0].operadorId")
                        .value(conta.usuarioId().toString()))
                .andExpect(jsonPath("$.lancamentos[0].clienteId").value(lia.toString()))
                .andExpect(jsonPath("$.lancamentos[0].situacaoDaVenda").value("CONCLUIDA"))
                .andExpect(jsonPath("$.lancamentos[0].integrado").value(false))
                .andExpect(jsonPath("$.lancamentos[0].aguardandoConfirmacao").value(false))
                .andExpect(jsonPath("$.lancamentos[1].vendaId").value(integradaPaga.toString()))
                .andExpect(jsonPath("$.lancamentos[1].operadorId")
                        .value(atendente.usuarioId().toString()))
                .andExpect(jsonPath("$.lancamentos[1].clienteId").doesNotExist())
                .andExpect(jsonPath("$.lancamentos[1].integrado").value(true))
                .andExpect(jsonPath("$.lancamentos[1].aguardandoConfirmacao").value(false))
                .andExpect(jsonPath("$.lancamentos[2].vendaId").value(aguardando.toString()))
                .andExpect(jsonPath("$.lancamentos[2].situacaoDaVenda").value("ABERTA"))
                .andExpect(jsonPath("$.lancamentos[2].integrado").value(true))
                .andExpect(jsonPath("$.lancamentos[2].aguardandoConfirmacao").value(true))
                .andExpect(jsonPath("$.lancamentos[3].vendaId").value(fiado.toString()))
                .andExpect(jsonPath("$.lancamentos[3].origem").value("RECEBIMENTO"))
                .andExpect(jsonPath("$.lancamentos[3].valor").value(5.0))
                .andExpect(jsonPath("$.lancamentos[3].operadorId")
                        .value(atendente.usuarioId().toString()))
                .andExpect(jsonPath("$.lancamentos[3].clienteId").value(lia.toString()))
                .andExpect(jsonPath("$.lancamentos[3].integrado").value(false))
                .andExpect(jsonPath("$.lancamentos[3].aguardandoConfirmacao").value(false));

        // O operador da parcela é o da venda, e o do recebimento, quem o recebeu no próprio caixa.
        conferencia(conta, DIA, atendente.usuarioId())
                .andExpect(jsonPath("$.lancamentos.length()").value(2))
                .andExpect(jsonPath("$.lancamentos[0].vendaId").value(integradaPaga.toString()))
                .andExpect(jsonPath("$.lancamentos[1].vendaId").value(fiado.toString()));
        conferencia(conta, DIA.plusDays(1), null)
                .andExpect(jsonPath("$.lancamentos.length()").value(0));
        // A outra Conta vê só o próprio Pix, e o operador desta não traz nada lá (RNF05).
        conferencia(outra, DIA, null)
                .andExpect(jsonPath("$.lancamentos.length()").value(1))
                .andExpect(jsonPath("$.lancamentos[0].vendaId").value(daOutra.toString()));
        conferencia(outra, DIA, atendente.usuarioId())
                .andExpect(jsonPath("$.lancamentos.length()").value(0));
    }

    @Test
    void recusaOperadorPlanoSemRelatorioEParametroInvalido() throws Exception {
        ContaCriada conta = criador.criar("Padaria Aurora", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        ContaCriada operador = criador.criar("Ponto do Operador", SENHA, Perfil.OPERADOR, true);
        criador.contratar(operador.contaId(), Plano.COMPLETO);
        ContaCriada gratis = criador.criar("Banca Aurora", SENHA);

        conferencia(operador, DIA, null).andExpect(status().isForbidden());
        conferencia(gratis, DIA, null).andExpect(status().isConflict());
        http.perform(get("/api/relatorios/conferencia-do-pix").with(autenticador.como(conta)))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/conferencia-do-pix").param("dia", DIA.toString())
                        .param("operadorId", "nao-e-um-id").with(autenticador.como(conta)))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/conferencia-do-pix").param("dia", DIA.toString()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions conferencia(ContaCriada conta, LocalDate dia, UUID operadorId)
            throws Exception {
        MockHttpServletRequestBuilder pedido = get("/api/relatorios/conferencia-do-pix")
                .param("dia", dia.toString());
        if (operadorId != null) {
            pedido = pedido.param("operadorId", operadorId.toString());
        }
        return http.perform(pedido.with(autenticador.como(conta)));
    }

    private static CobrancaPix cobranca(CobrancaPix.Estado estado) {
        String copiaECola = estado == CobrancaPix.Estado.DISPONIVEL ? "00020126codigo" : null;
        return new CobrancaPix(UUID.randomUUID().toString().replace("-", ""), "chave@exemplo.test",
                noBalcao(LocalTime.of(23, 0)), copiaECola, estado);
    }

    /** Uma venda de 12,50 numa parcela Pix na hora dada do dia; sem cobrança, lançada à mão. */
    private UUID venda(ContaCriada conta, UUID caixa, UUID operador, UUID cliente,
            StatusVenda status, LocalTime hora, StatusPagamento statusDoPix, CobrancaPix cobranca) {
        Instant instante = noBalcao(hora);
        ItemVenda item = new ItemVenda(UUID.randomUUID(), produto(conta), BigDecimal.ONE,
                Money.de("12.50"), Money.ZERO, instante);
        Pagamento pix = new Pagamento(UUID.randomUUID(), FormaPagamento.PIX, Money.de("12.50"),
                statusDoPix, Money.ZERO, instante, cobranca, null);
        Instant concluidoEm = status == StatusVenda.CONCLUIDA ? instante : null;
        Venda venda = Venda.reconstituir(UUID.randomUUID(), caixa, operador, cliente, status,
                Money.de("12.50"), Money.ZERO, instante, concluidoEm, List.of(item), List.of(pix));
        return vendas.gravar(conta.contaId(), venda);
    }

    private void vendaNoCartao(ContaCriada conta, UUID caixa, LocalTime hora) {
        Instant instante = noBalcao(hora);
        ItemVenda item = new ItemVenda(UUID.randomUUID(), produto(conta), BigDecimal.ONE,
                Money.de("12.50"), Money.ZERO, instante);
        Pagamento cartao = new Pagamento(UUID.randomUUID(), FormaPagamento.CARTAO,
                Money.de("12.50"), StatusPagamento.CONFIRMADO, Money.ZERO, instante);
        vendas.gravar(conta.contaId(), Venda.reconstituir(UUID.randomUUID(), caixa,
                conta.usuarioId(), null, StatusVenda.CONCLUIDA, Money.de("12.50"), Money.ZERO,
                instante, instante, List.of(item), List.of(cartao)));
    }

    /**
     * Uma venda fiada de 12,50 do cliente, concluída na véspera, com um recebimento de 5,00 em Pix na
     * hora dada do dia, no caixa informado.
     */
    private UUID fiadoRecebidoNoPix(ContaCriada conta, UUID caixa, UUID cliente, LocalTime hora) {
        Instant vespera = DIA.minusDays(1).atTime(LocalTime.NOON)
                .atZone(FusoDeReferencia.DO_BALCAO).toInstant();
        ItemVenda item = new ItemVenda(UUID.randomUUID(), produto(conta), BigDecimal.ONE,
                Money.de("12.50"), Money.ZERO, vespera);
        Pagamento fiado = new Pagamento(UUID.randomUUID(), FormaPagamento.FIADO, Money.de("12.50"),
                StatusPagamento.PENDENTE, Money.ZERO, vespera);
        Recebimento recebido = new Recebimento(UUID.randomUUID(), caixa, Money.de("5.00"),
                FormaPagamento.PIX, noBalcao(hora), null);
        Venda venda = Venda.reconstituir(UUID.randomUUID(), caixa, conta.usuarioId(), cliente,
                StatusVenda.CONCLUIDA, Money.de("12.50"), Money.ZERO, vespera, vespera,
                List.of(item), List.of(fiado), List.of(recebido));
        return vendas.gravar(conta.contaId(), venda);
    }

    private UUID produto(ContaCriada conta) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café " + UUID.randomUUID(), Money.de("12.50"), null, null,
                        "un", null)));
    }

    private static Instant noBalcao(LocalTime hora) {
        return DIA.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }
}
