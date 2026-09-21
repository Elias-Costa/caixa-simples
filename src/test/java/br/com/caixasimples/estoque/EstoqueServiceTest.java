package br.com.caixasimples.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService.EstoqueDoProduto;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.estoque.application.ControleDeEstoqueDesligadoException;
import br.com.caixasimples.estoque.application.EstoqueService;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A política do módulo de estoque nos casos de uso acionados por pessoa: ajuste manual (RF19),
 * estoque mínimo e lista de estoque baixo (RF20).
 *
 * <p>O que é deste módulo, e portanto o que se prova aqui, é a decisão de participar: a conta que
 * não ligou o controle de estoque (RF17) é recusada nos três, e nada muda. As regras do saldo, do
 * motivo e do limiar são da raiz do agregado, provadas em {@code ProdutoTest} e
 * {@code ProdutoServiceTest}; aqui basta ver que, com o controle ligado, o pedido chega ao
 * cadastro e o efeito aparece na lista.
 *
 * <p>O isolamento entre contas (RNF05) se prova nas duas direções que este módulo abre: a lista de
 * uma conta não traz o produto de outra, e o ajuste de uma conta não alcança o produto de outra.
 */
class EstoqueServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private EstoqueService estoqueService;

    @Autowired
    private ProdutoService produtoService;

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("com o controle de estoque desligado, ajuste, mínimo e alerta são recusados e nada muda")
    void comControleDesligadoOsTresCasosDeUsoRecusam() {
        ContaCriada salao = criador.criar("Salao Aurora", SENHA_DE_TESTE);
        UUID shampooId = cadastrar(salao, "Shampoo");

        assertThatExceptionOfType(ControleDeEstoqueDesligadoException.class)
                .isThrownBy(() -> salao.comoUsuario(() ->
                        estoqueService.ajustar(shampooId, new BigDecimal("5"), "contagem")))
                .withMessageContaining("nao controla estoque");
        assertThatExceptionOfType(ControleDeEstoqueDesligadoException.class)
                .isThrownBy(() -> salao.comoUsuario(() ->
                        estoqueService.definirEstoqueMinimo(shampooId, new BigDecimal("2"))));
        assertThatExceptionOfType(ControleDeEstoqueDesligadoException.class)
                .isThrownBy(() -> salao.comoUsuario(
                        estoqueService::produtosComEstoqueBaixo));

        salao.comoUsuario(() -> {
            assertThat(saldoDe(shampooId)).isEqualByComparingTo("0");
            assertThat(minimoDe(shampooId)).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("com o controle ligado, o ajuste move o saldo e o mínimo decide quem entra no alerta")
    void comControleLigadoAjusteEMinimoChegamAoCadastro() {
        ContaCriada cafeteria = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(cafeteria.contaId());
        UUID leiteId = cadastrar(cafeteria, "Leite");
        UUID cafeId = cadastrar(cafeteria, "Cafe em graos");

        // Sem movimento nenhum, os dois estão zerados, e zerado é baixo por padrão.
        cafeteria.comoUsuario(() ->
                assertThat(estoqueService.produtosComEstoqueBaixo())
                        .extracting(EstoqueDoProduto::id)
                        .containsExactlyInAnyOrder(leiteId, cafeId));

        cafeteria.comoUsuario(() -> {
            estoqueService.ajustar(leiteId, new BigDecimal("12"), "contagem inicial");
            estoqueService.ajustar(cafeId, new BigDecimal("4"), "contagem inicial");
            estoqueService.definirEstoqueMinimo(cafeId, new BigDecimal("5"));
        });

        // O leite repôs e saiu do alerta; o café tem 4 com mínimo 5, e continua.
        cafeteria.comoUsuario(() -> {
            assertThat(saldoDe(leiteId)).isEqualByComparingTo("12");
            assertThat(estoqueService.produtosComEstoqueBaixo())
                    .extracting(EstoqueDoProduto::id)
                    .containsExactly(cafeId);
        });

        // Uma perda leva o leite de volta ao alerta.
        cafeteria.comoUsuario(() ->
                estoqueService.ajustar(leiteId, new BigDecimal("-12"), "vencido"));
        cafeteria.comoUsuario(() ->
                assertThat(estoqueService.produtosComEstoqueBaixo())
                        .extracting(EstoqueDoProduto::id)
                        .containsExactlyInAnyOrder(leiteId, cafeId));
    }

    @Test
    @DisplayName("ajuste sem motivo é recusado mesmo com o controle ligado: a regra é da raiz")
    void ajusteSemMotivoERecusado() {
        ContaCriada cafeteria = criador.criar("Padaria Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(cafeteria.contaId());
        UUID paoId = cadastrar(cafeteria, "Pao frances");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> cafeteria.comoUsuario(() ->
                        estoqueService.ajustar(paoId, new BigDecimal("-3"), null)))
                .withMessageContaining("motivo");

        cafeteria.comoUsuario(() ->
                assertThat(saldoDe(paoId)).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("a lista de uma conta não traz o produto de outra, e o ajuste não o alcança (RNF05)")
    void contaNaoEnxergaNemAjustaProdutoDeOutraConta() {
        ContaCriada contaA = criador.criar("Mercearia Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja da Esquina", SENHA_DE_TESTE);
        criador.habilitarEstoque(contaA.contaId());
        criador.habilitarEstoque(contaB.contaId());
        UUID arrozDaContaA = cadastrar(contaA, "Arroz");

        // Zerado, o arroz está no alerta da conta A.
        contaA.comoUsuario(() ->
                assertThat(estoqueService.produtosComEstoqueBaixo())
                        .extracting(EstoqueDoProduto::id)
                        .containsExactly(arrozDaContaA));

        contaB.comoUsuario(() ->
                assertThat(estoqueService.produtosComEstoqueBaixo())
                        .as("a conta B não vê o alerta da conta A")
                        .isEmpty());
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class)
                .as("para a conta B, o produto da conta A não existe")
                .isThrownBy(() -> contaB.comoUsuario(() ->
                        estoqueService.ajustar(arrozDaContaA, new BigDecimal("50"), "contagem")));
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class)
                .isThrownBy(() -> contaB.comoUsuario(() ->
                        estoqueService.definirEstoqueMinimo(arrozDaContaA, BigDecimal.ONE)));

        contaA.comoUsuario(() ->
                assertThat(saldoDe(arrozDaContaA))
                        .as("a tentativa da conta B não moveu o saldo da conta A")
                        .isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("o operador não ajusta, não define mínimo nem lê o alerta, mesmo com o controle ligado (RF30)")
    void operadorNaoMexeNoEstoque() {
        ContaCriada mercearia = criador.criar("Mercearia com Atendente", SENHA_DE_TESTE);
        criador.habilitarEstoque(mercearia.contaId());
        UsuarioCriado operador = criador.criarOperadorEm(mercearia.contaId(), "Atendente");
        UUID arrozId = cadastrar(mercearia, "Arroz");

        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() ->
                        estoqueService.ajustar(arrozId, new BigDecimal("5"), "contagem")));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() ->
                        estoqueService.definirEstoqueMinimo(arrozId, new BigDecimal("2"))));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(
                        estoqueService::produtosComEstoqueBaixo));

        mercearia.comoUsuario(() -> {
            assertThat(saldoDe(arrozId)).isEqualByComparingTo("0");
            assertThat(minimoDe(arrozId)).isEqualByComparingTo("0");
        });
    }

    private UUID cadastrar(ContaCriada conta, String nome) {
        return conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    private BigDecimal saldoDe(UUID produtoId) {
        return produtos.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }

    private BigDecimal minimoDe(UUID produtoId) {
        return produtos.findById(produtoId).orElseThrow().paraDominio().getEstoqueMinimo();
    }
}
