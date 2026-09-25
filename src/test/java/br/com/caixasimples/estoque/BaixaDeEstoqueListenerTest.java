package br.com.caixasimples.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.VendaConcluida;
import br.com.caixasimples.vendas.VendaConcluida.Item;
import br.com.caixasimples.vendas.VendaConcluida.Parcela;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O estoque reagindo à venda concluída, dentro da transação de quem conclui.
 *
 * <p>Publica o evento diretamente, sem passar por {@code VendaService}, para provar só o que é do
 * estoque: a conta com o controle desligado não baixa nada, a ligada baixa um movimento por item
 * de produto, o mesmo fato não baixa duas vezes, e o evento de outra conta é recusado. O último
 * teste faz o caminho inteiro, da comanda ao saldo. A baixa acontece antes de a transação de quem
 * publica terminar, então cada asserção lê logo em seguida.
 */
class BaixaDeEstoqueListenerTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ApplicationEventPublisher publicador;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private SessaoCaixaService sessoesDeCaixa;

    @Autowired
    private ProdutoService produtoService;

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private VendaService vendaService;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("com o controle de estoque desligado, a venda não gera movimento nem muda saldo")
    void comEstoqueDesligadoNadaAcontece() {
        ContaCriada conta = criador.criar("Salao Vizinho", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID shampooId = cadastrar(conta, "Shampoo", TipoProduto.PRODUTO);

        publicar(conta, evento(conta, vendaId, sessaoId,
                List.of(new Item(shampooId, new BigDecimal("2")))));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(shampooId)).isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(shampooId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("com o controle ligado, cada item de produto vira uma baixa; serviço no meio não gera nada")
    void comEstoqueLigadoBaixaUmMovimentoPorItem() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID cafeId = cadastrar(conta, "Cafe coado", TipoProduto.PRODUTO);
        UUID queijoId = cadastrar(conta, "Queijo minas", TipoProduto.PRODUTO);
        UUID entregaId = cadastrar(conta, "Entrega", TipoProduto.SERVICO);

        publicar(conta, evento(conta, vendaId, sessaoId, List.of(
                new Item(cafeId, new BigDecimal("2")),
                new Item(entregaId, BigDecimal.ONE),
                new Item(queijoId, new BigDecimal("0.750")))));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(cafeId)).isEqualByComparingTo("-2");
            assertThat(saldoDe(queijoId)).isEqualByComparingTo("-0.750");
            assertThat(produtoService.jaDeuBaixaPorVenda(cafeId, vendaId)).isTrue();
            assertThat(produtoService.jaDeuBaixaPorVenda(queijoId, vendaId)).isTrue();
            assertThat(saldoDe(entregaId)).as("serviço não tem estoque").isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(entregaId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("o mesmo fato publicado de novo é recusado pelo cadastro, e o estoque sai uma vez")
    void mesmaVendaNaoBaixaDuasVezes() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID arrozId = cadastrar(conta, "Arroz", TipoProduto.PRODUTO);
        VendaConcluida evento = evento(conta, vendaId, sessaoId,
                List.of(new Item(arrozId, new BigDecimal("5"))));

        publicar(conta, evento);
        // Sem registro de publicação não há reentrega; publicar de novo é defeito de quem
        // publica, e o cadastro recusa a duplicata.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(conta, evento))
                .withMessageContaining("nao baixa de novo");

        conta.comoUsuario(() -> assertThat(saldoDe(arrozId)).isEqualByComparingTo("-5"));
    }

    @Test
    @DisplayName("o evento de outra conta é recusado, e o produto dele não se move (RNF05)")
    void eventoDeOutraContaNaoBaixa() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        criador.habilitarEstoque(contaA.contaId());
        criador.habilitarEstoque(contaB.contaId());
        UUID sessaoDaContaA = abrirCaixa(contaA);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());
        UUID produtoDaContaA = cadastrar(contaA, "Camiseta", TipoProduto.PRODUTO);
        VendaConcluida evento = evento(contaA, vendaDaContaA, sessaoDaContaA,
                List.of(new Item(produtoDaContaA, BigDecimal.ONE)));

        // A transação de quem publica está na conta B: o ouvinte não troca de conta no meio
        // dela, recusa.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(contaB, evento))
                .withMessageContaining("outra conta");

        contaA.comoUsuario(() ->
                assertThat(saldoDe(produtoDaContaA)).isEqualByComparingTo("0"));
        contaB.comoUsuario(() -> {
            assertThat(produtos.findById(produtoDaContaA)).isEmpty();
            assertThat(produtoService.jaDeuBaixaPorVenda(produtoDaContaA, vendaDaContaA))
                    .isFalse();
        });
    }

    @Test
    @DisplayName("da comanda ao saldo: concluir a venda pelo caso de uso baixa o estoque dos produtos")
    void vendaConcluidaPeloCasoDeUsoBaixaOEstoque() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID paoId = cadastrar(conta, "Pao frances", TipoProduto.PRODUTO);
        UUID encomendaId = cadastrar(conta, "Encomenda", TipoProduto.SERVICO);

        UUID vendaId = conta.comoUsuario(() -> vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, paoId, new BigDecimal("12"), Money.ZERO);
            vendaService.adicionarItem(vendaId, encomendaId, BigDecimal.ONE, Money.ZERO);
            // 12 x 5,00 + 1 x 5,00 = 65,00.
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("65.00")));
            vendaService.concluir(vendaId);
        });

        conta.comoUsuario(() -> {
            assertThat(saldoDe(paoId)).isEqualByComparingTo("-12");
            assertThat(saldoDe(encomendaId)).isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(paoId, vendaId)).isTrue();
        });
    }

    private UUID abrirCaixa(ContaCriada conta) {
        return conta.comoUsuario(() -> sessoesDeCaixa.abrir(Money.ZERO));
    }

    private UUID cadastrar(ContaCriada conta, String nome, TipoProduto tipo) {
        return conta.comoUsuario(() ->
                produtoService.cadastrar(tipo,
                        new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    /** O saldo como o domínio o vê: a única leitura pública de {@code estoque_atual} hoje. */
    private BigDecimal saldoDe(UUID produtoId) {
        return produtos.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }

    /** Um evento pago em Pix, para o ouvinte do caixa não ter o que fazer e sair do caminho. */
    private static VendaConcluida evento(ContaCriada conta, UUID vendaId, UUID sessaoId,
            List<Item> itens) {
        return new VendaConcluida(conta.contaId(), vendaId, sessaoId, conta.usuarioId(), itens,
                List.of(new Parcela(FormaPagamento.PIX, Money.de("10.00"),
                        StatusPagamento.CONFIRMADO)),
                Instant.now());
    }

    /** Como numa requisição: a conta no contexto antes de a transação abrir. */
    private void publicar(ContaCriada conta, VendaConcluida evento) {
        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> publicador.publishEvent(evento)));
    }
}
