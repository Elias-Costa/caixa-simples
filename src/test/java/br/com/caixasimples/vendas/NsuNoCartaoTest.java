package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
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
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaService.ParcelaParaTela;
import br.com.caixasimples.vendas.application.VendaService.RecebimentoParaTela;
import br.com.caixasimples.vendas.application.VendaService.VendaParaTela;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O NSU do pagamento em cartão pelos casos de uso: a exigência da Conta, que vale só para o que é
 * lançado com rede depois de ligada, o reenvio com o mesmo id e o que o banco recusa por fora do
 * código.
 */
class NsuNoCartaoTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired private CriadorDeContaDeTeste criador;
    @Autowired private ProdutoService produtos;
    @Autowired private ClienteService clientes;
    @Autowired private SessaoCaixaService caixas;
    @Autowired private VendaService vendas;
    @Autowired private ContaService contas;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    private record Balcao(ContaCriada conta, UUID sessaoId, UUID produtoId) {
    }

    private Balcao abrirBalcao(String nome) {
        ContaCriada conta = criador.criar(nome, SENHA);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID produtoId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café", Money.de("20.00"), null, null, "un", null)));
        return new Balcao(conta, sessaoId, produtoId);
    }

    private UUID comanda(Balcao balcao) {
        return balcao.conta().comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(balcao.sessaoId());
            vendas.adicionarItem(vendaId, balcao.produtoId(), BigDecimal.ONE, Money.ZERO);
            return vendaId;
        });
    }

    private UUID vendaFiada(Balcao balcao) {
        UUID clienteId = balcao.conta().comoUsuario(() ->
                clientes.cadastrar(new DadosDoCliente("Lia", null)));
        return balcao.conta().comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(balcao.sessaoId());
            vendas.adicionarItem(vendaId, balcao.produtoId(), BigDecimal.ONE, Money.ZERO);
            vendas.vincularCliente(vendaId, clienteId);
            vendas.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("20.00")));
            vendas.concluir(vendaId);
            return vendaId;
        });
    }

    private void exigirNsu(ContaCriada conta, boolean obrigatorio) {
        conta.comoUsuario(() -> contas.definirNsuObrigatorio(obrigatorio));
    }

    private static SolicitacaoPagamento cartao(String valor) {
        return SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de(valor));
    }

    private VendaParaTela consultar(ContaCriada conta, UUID vendaId) {
        return conta.comoUsuario(() -> vendas.consultar(vendaId));
    }

    @Test
    @DisplayName("sem a exigência, o cartão aceita a parcela sem NSU e guarda o informado sem os espaços")
    void semExigenciaONsuEOpcional() {
        Balcao balcao = abrirBalcao("Padaria Aurora");
        UUID vendaId = comanda(balcao);
        assertThat(balcao.conta().comoUsuario(() -> contas.nsuObrigatorio())).isFalse();

        UUID semNsu = UUID.randomUUID();
        UUID comNsu = UUID.randomUUID();
        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaId, semNsu, cartao("8.00"), "   ");
            vendas.registrarPagamentoOnline(vendaId, comNsu, cartao("12.00"), " 004512 ");
            vendas.concluir(vendaId);
        });

        // Lida numa transação nova, a parcela volta do banco com o NSU que foi gravado.
        assertThat(consultar(balcao.conta(), vendaId).parcelas())
                .extracting(ParcelaParaTela::id, ParcelaParaTela::nsu)
                .containsExactlyInAnyOrder(
                        tuple(semNsu, null),
                        tuple(comNsu, "004512"));
        UUID outraVenda = comanda(balcao);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(outraVenda, UUID.randomUUID(),
                                SolicitacaoPagamento.emDinheiro(Money.de("20.00"),
                                        Money.de("20.00")), "004512")))
                .withMessageContaining("NSU so existe em cartao");
        assertThat(consultar(balcao.conta(), outraVenda).parcelas()).isEmpty();
    }

    @Test
    @DisplayName("com a exigência, a parcela em cartão sem NSU é recusada sem deixar rastro, e as outras formas seguem livres")
    void exigenciaRecusaOCartaoSemNsu() {
        Balcao balcao = abrirBalcao("Mercearia Aurora");
        UUID vendaId = comanda(balcao);
        exigirNsu(balcao.conta(), true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(),
                                cartao("8.00"), null)))
                .withMessageContaining("exige o NSU");
        // Em branco vale como não informado, então também é recusado.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(),
                                cartao("8.00"), "  ")))
                .withMessageContaining("exige o NSU");
        assertThat(consultar(balcao.conta(), vendaId).parcelas()).isEmpty();

        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(), cartao("8.00"), "A1B2");
            vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(),
                    SolicitacaoPagamento.emDinheiro(Money.de("12.00"), Money.de("20.00")), null);
            vendas.concluir(vendaId);
        });
        assertThat(consultar(balcao.conta(), vendaId).parcelas())
                .extracting(ParcelaParaTela::forma, ParcelaParaTela::nsu)
                .containsExactlyInAnyOrder(
                        tuple(FormaPagamento.CARTAO, "A1B2"),
                        tuple(FormaPagamento.DINHEIRO, null));
    }

    @Test
    @DisplayName("ligar não alcança o passado: a parcela sem NSU continua válida, o reenvio igual passa e o diferente é conflito")
    void exigenciaNaoAlcancaOQueFoiGravado() {
        Balcao balcao = abrirBalcao("Empório Aurora");
        UUID vendaId = comanda(balcao);
        UUID antiga = UUID.randomUUID();
        balcao.conta().comoUsuario(() ->
                vendas.registrarPagamentoOnline(vendaId, antiga, cartao("10.00"), null));

        exigirNsu(balcao.conta(), true);

        // A resposta perdida volta igual, mesmo com a exigência ligada depois.
        Money troco = balcao.conta().comoUsuario(() ->
                vendas.registrarPagamentoOnline(vendaId, antiga, cartao("10.00"), null));
        assertThat(troco).isEqualTo(Money.ZERO);
        // O mesmo id com outro NSU não é a mesma parcela.
        assertThatIllegalStateException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaId, antiga, cartao("10.00"),
                                "004512")))
                .withMessageContaining("outro conteudo");

        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(), cartao("10.00"), "004512");
            vendas.concluir(vendaId);
        });
        assertThat(consultar(balcao.conta(), vendaId).parcelas())
                .extracting(ParcelaParaTela::nsu)
                .containsExactlyInAnyOrder(null, "004512");
    }

    @Test
    @DisplayName("o recebimento de fiado em cartão segue a mesma regra, inclusive no reenvio")
    void recebimentoDeFiadoSegueAMesmaRegra() {
        Balcao balcao = abrirBalcao("Armazém Aurora");
        UUID vendaId = vendaFiada(balcao);
        exigirNsu(balcao.conta(), true);
        UUID recebimentoId = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.receberOnline(vendaId, recebimentoId, Money.de("5.00"),
                                FormaPagamento.CARTAO, null)))
                .withMessageContaining("exige o NSU");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.receberOnline(vendaId, UUID.randomUUID(), Money.de("5.00"),
                                FormaPagamento.PIX, "778899")))
                .withMessageContaining("NSU so existe em cartao");

        var recebido = balcao.conta().comoUsuario(() -> vendas.receberOnline(vendaId,
                recebimentoId, Money.de("5.00"), FormaPagamento.CARTAO, " 778899 "));
        assertThat(recebido.repetido()).isFalse();
        var repetido = balcao.conta().comoUsuario(() -> vendas.receberOnline(vendaId,
                recebimentoId, Money.de("5.00"), FormaPagamento.CARTAO, "778899"));
        assertThat(repetido.repetido()).isTrue();
        assertThatIllegalStateException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() -> vendas.receberOnline(vendaId,
                        recebimentoId, Money.de("5.00"), FormaPagamento.CARTAO, "778800")))
                .withMessageContaining("outro conteudo");
        balcao.conta().comoUsuario(() -> vendas.receberOnline(vendaId, UUID.randomUUID(),
                Money.de("5.00"), FormaPagamento.DINHEIRO, null));

        assertThat(consultar(balcao.conta(), vendaId).recebimentos())
                .extracting(RecebimentoParaTela::forma, RecebimentoParaTela::nsu)
                .containsExactlyInAnyOrder(
                        tuple(FormaPagamento.CARTAO, "778899"),
                        tuple(FormaPagamento.DINHEIRO, null));
    }

    @Test
    @DisplayName("a parcela lançada sem rede não é recusada pela exigência; o pagamento aconteceu")
    void gestoSemRedeNaoERecusadoPelaExigencia() {
        Balcao balcao = abrirBalcao("Quitanda Aurora");
        UUID vendaId = comanda(balcao);
        exigirNsu(balcao.conta(), true);
        UUID parcelaId = UUID.randomUUID();

        balcao.conta().comoUsuario(() -> vendas.registrarPagamento(vendaId, parcelaId,
                cartao("20.00"), Instant.now(), null));

        assertThat(consultar(balcao.conta(), vendaId).parcelas())
                .extracting(ParcelaParaTela::id, ParcelaParaTela::nsu)
                .containsExactly(tuple(parcelaId, null));
    }

    @Test
    @DisplayName("a exigência é da Conta: ligada na A, a B continua aceitando o cartão sem NSU (RNF05)")
    void exigenciaEDaConta() {
        Balcao primeira = abrirBalcao("Banca Aurora");
        Balcao segunda = abrirBalcao("Banca da Esquina");
        exigirNsu(primeira.conta(), true);

        assertThat(segunda.conta().comoUsuario(() -> contas.nsuObrigatorio())).isFalse();
        UUID vendaDaSegunda = comanda(segunda);
        segunda.conta().comoUsuario(() -> vendas.registrarPagamentoOnline(vendaDaSegunda,
                UUID.randomUUID(), cartao("20.00"), null));
        assertThat(consultar(segunda.conta(), vendaDaSegunda).parcelas()).hasSize(1);

        UUID vendaDaPrimeira = comanda(primeira);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> primeira.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaDaPrimeira, UUID.randomUUID(),
                                cartao("20.00"), null)));

        // O recebimento de fiado segue a exigência de cada Conta, e não a da outra.
        UUID fiadaDaSegunda = vendaFiada(segunda);
        segunda.conta().comoUsuario(() -> vendas.receberOnline(fiadaDaSegunda, UUID.randomUUID(),
                Money.de("5.00"), FormaPagamento.CARTAO, null));
        assertThat(consultar(segunda.conta(), fiadaDaSegunda).recebimentos()).hasSize(1);
        UUID fiadaDaPrimeira = vendaFiada(primeira);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> primeira.conta().comoUsuario(() ->
                        vendas.receberOnline(fiadaDaPrimeira, UUID.randomUUID(), Money.de("5.00"),
                                FormaPagamento.CARTAO, null)))
                .withMessageContaining("exige o NSU");
    }

    @Test
    @DisplayName("o banco recusa NSU fora do cartão, vazio ou com espaço nas pontas, gravado por fora do código")
    void bancoRepeteAsRegras() {
        Balcao balcao = abrirBalcao("Feira Aurora");
        UUID vendaId = vendaFiada(balcao);
        UUID parcelaEmCartao = UUID.randomUUID();
        UUID vendaEmCartao = comanda(balcao);
        balcao.conta().comoUsuario(() -> vendas.registrarPagamentoOnline(vendaEmCartao,
                parcelaEmCartao, cartao("20.00"), "004512"));
        var emDinheiro = balcao.conta().comoUsuario(() -> vendas.receberOnline(vendaId,
                UUID.randomUUID(), Money.de("5.00"), FormaPagamento.DINHEIRO, null));
        var emCartao = balcao.conta().comoUsuario(() -> vendas.receberOnline(vendaId,
                UUID.randomUUID(), Money.de("5.00"), FormaPagamento.CARTAO, "778899"));
        UUID parcelaFiada = consultar(balcao.conta(), vendaId).parcelas().getFirst().id();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("UPDATE pagamento SET nsu = '004512' WHERE id = ?",
                        parcelaFiada))
                .withMessageContaining("pagamento_nsu_so_em_cartao");
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("UPDATE pagamento SET nsu = ' 004512' WHERE id = ?",
                        parcelaEmCartao))
                .withMessageContaining("pagamento_nsu_sem_espaco_nas_pontas");
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("UPDATE pagamento SET nsu = '' WHERE id = ?",
                        parcelaEmCartao))
                .withMessageContaining("pagamento_nsu_sem_espaco_nas_pontas");
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("UPDATE recebimento SET nsu = '778899' WHERE id = ?",
                        emDinheiro.id()))
                .withMessageContaining("recebimento_nsu_so_em_cartao");
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("UPDATE recebimento SET nsu = '778899 ' WHERE id = ?",
                        emCartao.id()))
                .withMessageContaining("recebimento_nsu_sem_espaco_nas_pontas");
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("UPDATE pagamento SET nsu = ? WHERE id = ?",
                        "A".repeat(41), parcelaEmCartao));
    }
}
