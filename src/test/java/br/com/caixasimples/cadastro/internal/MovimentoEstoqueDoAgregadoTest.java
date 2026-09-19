package br.com.caixasimples.cadastro.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoMovimentoEstoque;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.domain.MovimentoEstoque;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O <strong>membro</strong> do agregado Produto no banco: isolamento entre contas (RNF05) e a
 * invariante de que o saldo é a soma dos movimentos.
 *
 * <p>Este arquivo está em {@code cadastro.internal} de propósito, pelo mesmo motivo que colocou o
 * teste do movimento de caixa no pacote interno: {@code MovimentoEstoqueEntity} tem visibilidade
 * de pacote, ninguém de fora consegue nomear o tipo, e a única coisa que o caminho público
 * <em>não</em> consegue mostrar é justamente o {@code conta_id} do membro e a linha gravada.
 *
 * <p>A invariante se prova aqui, e não no domínio, porque a raiz não carrega o histórico: o que
 * a garante é a gravação conjunta de saldo e movimento, e isso só se vê no banco. A coleção é
 * {@code LAZY}, então toda leitura dela abre transação.
 */
class MovimentoEstoqueDoAgregadoTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private ProdutoService produtoService;

    @Autowired
    private SessaoCaixaService sessoesDeCaixa;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private TransactionTemplate transacao;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("cada baixa grava o movimento e o saldo juntos, e o saldo é a soma do histórico")
    void saldoEASomaDosMovimentos() {
        ContaCriada conta = criador.criar("Mercearia Teste", SENHA_DE_TESTE);
        UUID produtoId = cadastrarProduto(conta, "Arroz");
        UUID sessaoId = abrirCaixa(conta);
        UUID primeiraVenda = vendaEm(conta, sessaoId);
        UUID segundaVenda = vendaEm(conta, sessaoId);

        TenantContext.executarComo(conta.contaId(), () -> {
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("2"), primeiraVenda);
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("0.750"), segundaVenda);
        });

        TenantContext.executarComo(conta.contaId(), () ->
                transacao.executeWithoutResult(status -> {
                    ProdutoEntity gravado = produtos.findById(produtoId).orElseThrow();
                    List<MovimentoEstoque> historico = gravado.getMovimentos().stream()
                            .map(MovimentoEstoqueEntity::paraDominio)
                            .toList();

                    assertThat(historico)
                            .extracting(MovimentoEstoque::tipo, MovimentoEstoque::vendaId,
                                    MovimentoEstoque::motivo)
                            .containsExactly(
                                    tuple(TipoMovimentoEstoque.SAIDA, primeiraVenda, null),
                                    tuple(TipoMovimentoEstoque.SAIDA, segundaVenda, null));
                    assertThat(historico.get(0).quantidade()).isEqualByComparingTo("2");
                    assertThat(historico.get(1).quantidade()).isEqualByComparingTo("0.750");

                    BigDecimal somaDasSaidas = historico.stream()
                            .map(MovimentoEstoque::quantidade)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertThat(gravado.paraDominio().getEstoqueAtual())
                            .as("saldo = soma assinada dos movimentos: só saídas, logo negativo")
                            .isEqualByComparingTo(somaDasSaidas.negate());

                    assertThat(gravado.getMovimentos())
                            .extracting(MovimentoEstoqueEntity::getContaId)
                            .as("movimento_estoque tem conta_id próprio, vindo do @TenantId, e"
                                    + " não de JOIN com o produto nem de parâmetro de chamada")
                            .containsOnly(conta.contaId());
                }));
    }

    @Test
    @DisplayName("conta B não alcança o movimento da conta A nem pela raiz nem pela pergunta de reentrega")
    void contaNaoEnxergaMovimentoDeOutraConta() {
        ContaCriada contaA = criador.criar("Bar do Teste", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Barbearia Teste", SENHA_DE_TESTE);
        UUID produtoDaContaA = cadastrarProduto(contaA, "Cerveja");
        UUID vendaDaContaA = vendaEm(contaA, abrirCaixa(contaA));

        TenantContext.executarComo(contaA.contaId(), () ->
                produtoService.darBaixaPorVenda(produtoDaContaA, BigDecimal.ONE, vendaDaContaA));

        // Como não existe repositório para o membro do agregado, as portas para ele são a raiz e a
        // pergunta derivada que a atravessa, e as duas já estão fechadas para a conta B.
        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(produtos.findById(produtoDaContaA)).isEmpty();
            assertThat(produtos.existsByIdAndMovimentosVendaId(produtoDaContaA, vendaDaContaA))
                    .as("derived query atravessando tenant")
                    .isFalse();
            assertThat(produtoService.jaDeuBaixaPorVenda(produtoDaContaA, vendaDaContaA))
                    .isFalse();
        });

        // E a conta A continua vendo o próprio dado: o filtro não pode ser esconder de todos.
        TenantContext.executarComo(contaA.contaId(), () ->
                assertThat(produtos.existsByIdAndMovimentosVendaId(produtoDaContaA,
                        vendaDaContaA)).isTrue());
    }

    private UUID cadastrarProduto(ContaCriada conta, String nome) {
        return TenantContext.executarComo(conta.contaId(), () ->
                produtoService.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    /** Um caixa aberto, uma vez por conta: um operador só tem uma sessão ABERTA por vez. */
    private UUID abrirCaixa(ContaCriada conta) {
        return TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.ZERO));
    }

    /** Uma venda vazia nesse caixa: só o alvo da chave estrangeira de {@code venda_id}. */
    private UUID vendaEm(ContaCriada conta, UUID sessaoId) {
        return vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
    }
}
