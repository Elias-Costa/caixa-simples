package br.com.caixasimples.cadastro.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
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

        conta.comoUsuario(() -> {
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("2"), primeiraVenda);
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("0.750"), segundaVenda);
        });

        conta.comoUsuario(() ->
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
    @DisplayName("ajuste e baixa dividem o mesmo histórico, e o saldo é a soma assinada: SAIDA negada, AJUSTE como está")
    void saldoEASomaAssinadaComAjuste() {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA_DE_TESTE);
        UUID produtoId = cadastrarProduto(conta, "Feijao");
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));

        conta.comoUsuario(() -> {
            produtoService.ajustarEstoque(produtoId, new BigDecimal("10"), "contagem inicial");
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("3"), vendaId);
            produtoService.ajustarEstoque(produtoId, new BigDecimal("-1.500"), "pacote rasgado");
        });

        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> {
                    ProdutoEntity gravado = produtos.findById(produtoId).orElseThrow();
                    List<MovimentoEstoque> historico = gravado.getMovimentos().stream()
                            .map(MovimentoEstoqueEntity::paraDominio)
                            .toList();

                    assertThat(historico)
                            .extracting(MovimentoEstoque::tipo, MovimentoEstoque::vendaId,
                                    MovimentoEstoque::motivo)
                            .containsExactly(
                                    tuple(TipoMovimentoEstoque.AJUSTE, null, "contagem inicial"),
                                    tuple(TipoMovimentoEstoque.SAIDA, vendaId, null),
                                    tuple(TipoMovimentoEstoque.AJUSTE, null, "pacote rasgado"));
                    assertThat(historico.get(0).quantidade()).isEqualByComparingTo("10");
                    assertThat(historico.get(1).quantidade()).isEqualByComparingTo("3");
                    assertThat(historico.get(2).quantidade())
                            .as("o ajuste grava a diferença com sinal")
                            .isEqualByComparingTo("-1.500");

                    BigDecimal somaAssinada = BigDecimal.ZERO;
                    for (MovimentoEstoque movimento : historico) {
                        if (movimento.tipo() == TipoMovimentoEstoque.SAIDA) {
                            somaAssinada = somaAssinada.subtract(movimento.quantidade());
                        } else {
                            somaAssinada = somaAssinada.add(movimento.quantidade());
                        }
                    }
                    assertThat(gravado.paraDominio().getEstoqueAtual())
                            .isEqualByComparingTo("5.500")
                            .isEqualByComparingTo(somaAssinada);

                    assertThat(gravado.getMovimentos())
                            .extracting(MovimentoEstoqueEntity::getContaId)
                            .as("o ajuste também herda a conta do contexto, não de parâmetro")
                            .containsOnly(conta.contaId());
                }));
    }

    @Test
    @DisplayName("conta B não alcança o movimento da conta A nem pela raiz nem pela pergunta ao histórico")
    void contaNaoEnxergaMovimentoDeOutraConta() {
        ContaCriada contaA = criador.criar("Bar do Teste", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Barbearia Teste", SENHA_DE_TESTE);
        UUID produtoDaContaA = cadastrarProduto(contaA, "Cerveja");
        UUID vendaDaContaA = vendaEm(contaA, abrirCaixa(contaA));

        contaA.comoUsuario(() ->
                produtoService.darBaixaPorVenda(produtoDaContaA, BigDecimal.ONE, vendaDaContaA));

        // Como não existe repositório para o membro do agregado, as portas para ele são a raiz e a
        // pergunta derivada que a atravessa, e as duas já estão fechadas para a conta B.
        contaB.comoUsuario(() -> {
            assertThat(produtos.findById(produtoDaContaA)).isEmpty();
            assertThat(produtos.existsByIdAndMovimentosVendaIdAndMovimentosTipo(produtoDaContaA,
                    vendaDaContaA, TipoMovimentoEstoque.SAIDA))
                    .as("derived query atravessando tenant")
                    .isFalse();
            assertThat(produtoService.jaDeuBaixaPorVenda(produtoDaContaA, vendaDaContaA))
                    .isFalse();
            assertThat(produtoService.jaEstornouPorCancelamento(produtoDaContaA, vendaDaContaA))
                    .isFalse();
        });

        // E a conta A continua vendo o próprio dado: o filtro não pode ser esconder de todos.
        contaA.comoUsuario(() ->
                assertThat(produtos.existsByIdAndMovimentosVendaIdAndMovimentosTipo(
                        produtoDaContaA, vendaDaContaA, TipoMovimentoEstoque.SAIDA)).isTrue());
    }

    @Test
    @DisplayName("o estorno grava a ENTRADA ao lado da SAIDA da mesma venda, e o saldo volta ao anterior (RF12)")
    void estornoDevolveOQueABaixaTirou() {
        ContaCriada conta = criador.criar("Padaria Teste", SENHA_DE_TESTE);
        UUID produtoId = cadastrarProduto(conta, "Pao");
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));

        conta.comoUsuario(() -> {
            produtoService.ajustarEstoque(produtoId, new BigDecimal("10"), "contagem inicial");
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("3"), vendaId);
            produtoService.estornarPorCancelamento(produtoId, new BigDecimal("3"), vendaId);
        });

        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> {
                    ProdutoEntity gravado = produtos.findById(produtoId).orElseThrow();
                    List<MovimentoEstoque> historico = gravado.getMovimentos().stream()
                            .map(MovimentoEstoqueEntity::paraDominio)
                            .toList();

                    // A SAIDA fica: movimento lançado não se edita, lança-se o oposto. O índice
                    // único da V9 inclui o tipo justamente para as duas caberem lado a lado.
                    assertThat(historico)
                            .extracting(MovimentoEstoque::tipo, MovimentoEstoque::vendaId,
                                    MovimentoEstoque::motivo)
                            .containsExactly(
                                    tuple(TipoMovimentoEstoque.AJUSTE, null, "contagem inicial"),
                                    tuple(TipoMovimentoEstoque.SAIDA, vendaId, null),
                                    tuple(TipoMovimentoEstoque.ENTRADA, vendaId, null));
                    assertThat(historico.get(2).quantidade()).isEqualByComparingTo("3");
                    assertThat(gravado.paraDominio().getEstoqueAtual())
                            .as("saldo de volta ao que era antes da venda")
                            .isEqualByComparingTo("10");
                }));

        conta.comoUsuario(() -> {
            assertThat(produtoService.jaDeuBaixaPorVenda(produtoId, vendaId))
                    .as("a baixa aconteceu; o estorno não a apaga")
                    .isTrue();
            assertThat(produtoService.jaEstornouPorCancelamento(produtoId, vendaId)).isTrue();
        });
    }

    @Test
    @DisplayName("não se estorna o que não saiu, nem duas vezes; e a pergunta olha a venda e o tipo na mesma linha")
    void estornoExigeABaixaEUmaVezSo() {
        ContaCriada conta = criador.criar("Quitanda Teste", SENHA_DE_TESTE);
        UUID produtoId = cadastrarProduto(conta, "Banana");
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaA = vendaEm(conta, sessaoId);
        UUID vendaB = vendaEm(conta, sessaoId);

        conta.comoUsuario(() -> {
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("2"), vendaA);
            produtoService.darBaixaPorVenda(produtoId, new BigDecimal("5"), vendaB);
            produtoService.estornarPorCancelamento(produtoId, new BigDecimal("5"), vendaB);
        });

        conta.comoUsuario(() -> {
            // O produto tem SAIDA da venda A e ENTRADA da venda B. Se a consulta derivada abrisse
            // um JOIN por filtro, "venda A com tipo ENTRADA" casaria com a linha da A e a linha da
            // B ao mesmo tempo e responderia sim; ela responde não porque os dois filtros caem na
            // mesma linha do histórico.
            assertThat(produtoService.jaEstornouPorCancelamento(produtoId, vendaA))
                    .as("a ENTRADA da venda B nao e estorno da venda A")
                    .isFalse();
            assertThat(produtoService.jaEstornouPorCancelamento(produtoId, vendaB)).isTrue();

            assertThatIllegalStateException()
                    .as("estornar em dobro")
                    .isThrownBy(() -> produtoService.estornarPorCancelamento(produtoId,
                            new BigDecimal("5"), vendaB))
                    .withMessageContaining("ja foi estornada");

            UUID vendaQueNaoBaixou = vendaEm(conta, sessaoId);
            assertThatIllegalStateException()
                    .as("estornar o que nunca saiu")
                    .isThrownBy(() -> produtoService.estornarPorCancelamento(produtoId,
                            BigDecimal.ONE, vendaQueNaoBaixou))
                    .withMessageContaining("nao deu baixa");

            assertThat(produtos.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual())
                    .as("as recusas não moveram o saldo: -2 -5 +5")
                    .isEqualByComparingTo("-2");
        });
    }

    private UUID cadastrarProduto(ContaCriada conta, String nome) {
        return conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    /** Um caixa aberto, uma vez por conta: um operador só tem uma sessão ABERTA por vez. */
    private UUID abrirCaixa(ContaCriada conta) {
        return conta.comoUsuario(() ->
                sessoesDeCaixa.abrir(Money.ZERO));
    }

    /** Uma venda vazia nesse caixa: só o alvo da chave estrangeira de {@code venda_id}. */
    private UUID vendaEm(ContaCriada conta, UUID sessaoId) {
        return vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
    }
}
