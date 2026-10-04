package br.com.caixasimples.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.application.RecursoForaDoPlanoException;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.relatorios.application.ConferenciaDoCartaoService;
import br.com.caixasimples.relatorios.application.ConferenciaDoCartaoService.ConferenciaDoCartao;
import br.com.caixasimples.relatorios.application.ConferenciaDoCartaoService.Lancamento;
import br.com.caixasimples.relatorios.application.ConferenciaDoCartaoService.Origem;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A conferência do cartão pelo caso de uso e contra o Postgres real: o dia do lançamento, as
 * situações da venda, o recebimento de fiado com quem o recebeu, o filtro de operador, a ordem e o
 * isolamento entre Contas (RF24, RF26, RNF05).
 *
 * <p>As vendas são montadas inteiras e gravadas por {@code CriadorDeVendaDeTeste}, porque o caso de
 * uso grava o instante corrente, e a regra a provar é justamente a do instante de cada pagamento.
 */
class ConferenciaDoCartaoServiceTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    /** Um dia qualquer; o que importa é o fuso em que ele começa e termina. */
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);

    @Autowired private CriadorDeContaDeTeste criador;
    @Autowired private CriadorDeVendaDeTeste vendas;
    @Autowired private SessaoCaixaService caixas;
    @Autowired private ProdutoService produtos;
    @Autowired private ClienteService clientes;
    @Autowired private ConferenciaDoCartaoService conferencia;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("a lista é do dia do lançamento, tem toda situação de venda e o fiado recebido, só cartão, em ordem de hora")
    void listaODiaDoLancamento() {
        Balcao balcao = abrirBalcao("Cafeteria Aurora");

        // Cobrada às 23h58 da véspera, numa venda que só concluiu à 0h02: fica na véspera.
        UUID daVespera = gravar(balcao, balcao.admin(), StatusVenda.CONCLUIDA, "20.00",
                noBalcao(DIA, LocalTime.of(0, 2)),
                cartao("20.00", noBalcao(DIA.minusDays(1), LocalTime.of(23, 58)), "111"));
        UUID concluida = gravar(balcao, balcao.admin(), StatusVenda.CONCLUIDA, "20.00",
                noBalcao(DIA, LocalTime.of(9, 5)),
                cartao("10.00", noBalcao(DIA, LocalTime.of(9, 0)), "222"),
                parcela(FormaPagamento.DINHEIRO, "5.00", noBalcao(DIA, LocalTime.of(9, 1))),
                parcela(FormaPagamento.PIX, "5.00", noBalcao(DIA, LocalTime.of(9, 2))));
        UUID aberta = gravar(balcao, balcao.admin(), StatusVenda.ABERTA, "20.00", null,
                cartao("7.00", noBalcao(DIA, LocalTime.of(10, 0)), null));
        UUID cancelada = gravar(balcao, balcao.admin(), StatusVenda.CANCELADA, "15.00",
                noBalcao(DIA, LocalTime.of(8, 5)),
                cartao("15.00", noBalcao(DIA, LocalTime.of(8, 0)), "333"));
        Fiado fiado = fiadoRecebidoPelaAtendente(balcao);
        UUID daAtendente = gravar(balcao, balcao.atendente(), StatusVenda.CONCLUIDA, "9.00",
                noBalcao(DIA, LocalTime.of(23, 59, 59)),
                cartao("9.00", noBalcao(DIA, LocalTime.of(23, 59, 59)), "555"));
        // À meia-noite em ponto já é o dia seguinte.
        UUID doDiaSeguinte = gravar(balcao, balcao.admin(), StatusVenda.CONCLUIDA, "4.00",
                noBalcao(DIA.plusDays(1), LocalTime.MIDNIGHT),
                cartao("4.00", noBalcao(DIA.plusDays(1), LocalTime.MIDNIGHT), "666"));

        ConferenciaDoCartao doDia = balcao.conta().comoUsuario(() -> conferencia.doDia(DIA));

        assertThat(doDia.dia()).isEqualTo(DIA);
        assertThat(doDia.lancamentos())
                .extracting(Lancamento::vendaId, Lancamento::origem, Lancamento::situacaoDaVenda,
                        Lancamento::valor, Lancamento::operadorId, Lancamento::nsu)
                .containsExactly(
                        tuple(cancelada, Origem.PARCELA, StatusVenda.CANCELADA,
                                Money.de("15.00"), balcao.admin(), "333"),
                        tuple(concluida, Origem.PARCELA, StatusVenda.CONCLUIDA,
                                Money.de("10.00"), balcao.admin(), "222"),
                        tuple(aberta, Origem.PARCELA, StatusVenda.ABERTA,
                                Money.de("7.00"), balcao.admin(), null),
                        tuple(fiado.vendaId(), Origem.RECEBIMENTO, StatusVenda.CONCLUIDA,
                                Money.de("12.00"), balcao.atendente(), "444"),
                        tuple(daAtendente, Origem.PARCELA, StatusVenda.CONCLUIDA,
                                Money.de("9.00"), balcao.atendente(), "555"));
        assertThat(doDia.lancamentos().get(3).id()).isEqualTo(fiado.recebimentoEmCartao());
        assertThat(doDia.lancamentos().get(3).lancadoEm())
                .isEqualTo(noBalcao(DIA, LocalTime.of(11, 0)));

        assertThat(balcao.conta().comoUsuario(() -> conferencia.doDia(DIA.minusDays(1)))
                .lancamentos())
                .extracting(Lancamento::vendaId, Lancamento::nsu)
                .containsExactly(tuple(daVespera, "111"));
        assertThat(balcao.conta().comoUsuario(() -> conferencia.doDia(DIA.plusDays(1)))
                .lancamentos())
                .extracting(Lancamento::vendaId)
                .containsExactly(doDiaSeguinte);
    }

    @Test
    @DisplayName("o filtro de operador usa o da venda na parcela e o do caixa que recebeu no fiado")
    void filtroDeOperador() {
        Balcao balcao = abrirBalcao("Padaria Aurora");
        UUID doAdmin = gravar(balcao, balcao.admin(), StatusVenda.CONCLUIDA, "10.00",
                noBalcao(DIA, LocalTime.of(9, 0)),
                cartao("10.00", noBalcao(DIA, LocalTime.of(9, 0)), "222"));
        // A venda fiada é do administrador, mas quem recebeu foi a atendente, no caixa dela.
        Fiado fiado = fiadoRecebidoPelaAtendente(balcao);
        UUID daAtendente = gravar(balcao, balcao.atendente(), StatusVenda.CONCLUIDA, "9.00",
                noBalcao(DIA, LocalTime.of(12, 0)),
                cartao("9.00", noBalcao(DIA, LocalTime.of(12, 0)), "555"));

        assertThat(balcao.conta().comoUsuario(() ->
                conferencia.doDia(DIA, balcao.atendente())).lancamentos())
                .extracting(Lancamento::vendaId, Lancamento::origem)
                .containsExactly(tuple(fiado.vendaId(), Origem.RECEBIMENTO),
                        tuple(daAtendente, Origem.PARCELA));
        assertThat(balcao.conta().comoUsuario(() ->
                conferencia.doDia(DIA, balcao.admin())).lancamentos())
                .extracting(Lancamento::vendaId)
                .containsExactly(doAdmin);
        assertThat(balcao.conta().comoUsuario(() ->
                conferencia.doDia(DIA, UUID.randomUUID())).lancamentos()).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> balcao.conta().comoUsuario(() ->
                conferencia.doDia(DIA, null)));
    }

    @Test
    @DisplayName("é do administrador, pede o plano com relatórios e não atravessa Contas (RNF05)")
    void permissaoPlanoEIsolamento() {
        Balcao primeira = abrirBalcao("Mercearia Aurora");
        Balcao segunda = abrirBalcao("Mercearia da Esquina");
        UUID daPrimeira = gravar(primeira, primeira.admin(), StatusVenda.CONCLUIDA, "10.00",
                noBalcao(DIA, LocalTime.of(9, 0)),
                cartao("10.00", noBalcao(DIA, LocalTime.of(9, 0)), "222"));
        UUID daSegunda = gravar(segunda, segunda.admin(), StatusVenda.CONCLUIDA, "30.00",
                noBalcao(DIA, LocalTime.of(9, 0)),
                cartao("30.00", noBalcao(DIA, LocalTime.of(9, 0)), "999"));

        assertThat(primeira.conta().comoUsuario(() -> conferencia.doDia(DIA)).lancamentos())
                .extracting(Lancamento::vendaId).containsExactly(daPrimeira);
        assertThat(segunda.conta().comoUsuario(() -> conferencia.doDia(DIA)).lancamentos())
                .extracting(Lancamento::vendaId).containsExactly(daSegunda);
        // O operador de uma Conta, pedido na outra, não traz nada.
        assertThat(segunda.conta().comoUsuario(() ->
                conferencia.doDia(DIA, primeira.admin())).lancamentos()).isEmpty();

        // O fiado recebido em cartão também fica em cada Conta, com a junção à sessão de caixa.
        Fiado fiadoDaPrimeira = fiadoRecebidoPelaAtendente(primeira);
        Fiado fiadoDaSegunda = fiadoRecebidoPelaAtendente(segunda);
        assertThat(primeira.conta().comoUsuario(() -> conferencia.doDia(DIA)).lancamentos())
                .filteredOn(lancamento -> lancamento.origem() == Origem.RECEBIMENTO)
                .extracting(Lancamento::id)
                .containsExactly(fiadoDaPrimeira.recebimentoEmCartao());
        assertThat(segunda.conta().comoUsuario(() -> conferencia.doDia(DIA)).lancamentos())
                .filteredOn(lancamento -> lancamento.origem() == Origem.RECEBIMENTO)
                .extracting(Lancamento::id)
                .containsExactly(fiadoDaSegunda.recebimentoEmCartao());
        assertThat(segunda.conta().comoUsuario(() ->
                conferencia.doDia(DIA, primeira.atendente())).lancamentos()).isEmpty();

        UsuarioCriado atendente = primeira.atendenteCriada();
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> atendente.comoUsuario(() -> conferencia.doDia(DIA)));

        ContaCriada gratis = criador.criar("Banca Aurora", SENHA);
        assertThatExceptionOfType(RecursoForaDoPlanoException.class)
                .isThrownBy(() -> gratis.comoUsuario(() -> conferencia.doDia(DIA)));
    }

    private record Balcao(ContaCriada conta, UsuarioCriado atendenteCriada, UUID caixaDoAdmin,
            UUID caixaDaAtendente, UUID produtoId, UUID clienteId) {

        UUID admin() {
            return conta.usuarioId();
        }

        UUID atendente() {
            return atendenteCriada.usuarioId();
        }

        UUID caixaDe(UUID usuarioId) {
            return usuarioId.equals(admin()) ? caixaDoAdmin : caixaDaAtendente;
        }
    }

    private record Fiado(UUID vendaId, UUID recebimentoEmCartao) {
    }

    private Balcao abrirBalcao(String nome) {
        ContaCriada conta = criador.criar(nome, SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        UsuarioCriado atendente = criador.criarOperadorEm(conta.contaId(), "Atendente");
        UUID caixaDoAdmin = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID caixaDaAtendente = atendente.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID produtoId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café", Money.de("10.00"), null, null, "un", null)));
        UUID clienteId = conta.comoUsuario(() ->
                clientes.cadastrar(new DadosDoCliente("Lia", null)));
        return new Balcao(conta, atendente, caixaDoAdmin, caixaDaAtendente, produtoId, clienteId);
    }

    /**
     * Uma venda no status pedido, no caixa de quem a fez, com um item do total informado e as
     * parcelas dadas; a comanda abre no instante da primeira parcela.
     */
    private UUID gravar(Balcao balcao, UUID usuarioId, StatusVenda status, String total,
            Instant concluidoEm, Pagamento... parcelas) {
        Instant abertaEm = parcelas[0].criadoEm();
        ItemVenda item = new ItemVenda(UUID.randomUUID(), balcao.produtoId(), BigDecimal.ONE,
                Money.de(total), Money.ZERO, abertaEm);
        Venda venda = Venda.reconstituir(UUID.randomUUID(), balcao.caixaDe(usuarioId), usuarioId,
                null, status, Money.de(total), Money.ZERO, abertaEm, concluidoEm, List.of(item),
                List.of(parcelas));
        return vendas.gravar(balcao.conta().contaId(), venda);
    }

    /**
     * Uma venda fiada do administrador, concluída dois dias antes, com dois recebimentos no caixa
     * da atendente: um em cartão às 11h e um em dinheiro às 11h30 do dia.
     */
    private Fiado fiadoRecebidoPelaAtendente(Balcao balcao) {
        Instant concluidaEm = noBalcao(DIA.minusDays(2), LocalTime.of(15, 0));
        ItemVenda item = new ItemVenda(UUID.randomUUID(), balcao.produtoId(), BigDecimal.ONE,
                Money.de("30.00"), Money.ZERO, concluidaEm);
        Pagamento fiado = new Pagamento(UUID.randomUUID(), FormaPagamento.FIADO,
                Money.de("30.00"), StatusPagamento.PENDENTE, Money.ZERO, concluidaEm);
        Recebimento emCartao = new Recebimento(UUID.randomUUID(), balcao.caixaDaAtendente(),
                Money.de("12.00"), FormaPagamento.CARTAO, noBalcao(DIA, LocalTime.of(11, 0)),
                "444");
        Recebimento emDinheiro = new Recebimento(UUID.randomUUID(), balcao.caixaDaAtendente(),
                Money.de("3.00"), FormaPagamento.DINHEIRO, noBalcao(DIA, LocalTime.of(11, 30)));
        Venda venda = Venda.reconstituir(UUID.randomUUID(), balcao.caixaDoAdmin(),
                balcao.admin(), balcao.clienteId(), StatusVenda.CONCLUIDA, Money.de("30.00"),
                Money.ZERO, concluidaEm, concluidaEm, List.of(item), List.of(fiado),
                List.of(emCartao, emDinheiro));
        return new Fiado(vendas.gravar(balcao.conta().contaId(), venda), emCartao.id());
    }

    private static Pagamento cartao(String valor, Instant lancadaEm, String nsu) {
        return new Pagamento(UUID.randomUUID(), FormaPagamento.CARTAO, Money.de(valor),
                StatusPagamento.CONFIRMADO, Money.ZERO, lancadaEm, null, nsu);
    }

    private static Pagamento parcela(FormaPagamento forma, String valor, Instant lancadaEm) {
        return new Pagamento(UUID.randomUUID(), forma, Money.de(valor),
                StatusPagamento.CONFIRMADO, Money.ZERO, lancadaEm);
    }

    private static Instant noBalcao(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }
}
