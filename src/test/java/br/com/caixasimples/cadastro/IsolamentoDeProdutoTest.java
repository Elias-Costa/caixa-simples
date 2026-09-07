package br.com.caixasimples.cadastro;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RNF05 para {@code produto} — o passo 6 da skill {@code nova-entidade-multitenant}, no molde de
 * {@code IsolamentoEntreContasTest}: grava na conta A, consulta como conta B, espera vazio.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}. E o {@code @TenantId} do Hibernate que
 * filtra; se ele sair de {@code ProdutoEntity}, este teste quebra — que e exatamente o ponto dele.
 */
class IsolamentoDeProdutoTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta B nao enxerga produto da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaProdutoDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Piloto", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);

        UUID produtoDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                produtos.save(ProdutoEntity.de(new Produto("Cafe coado", Money.de("6.50"),
                        TipoProduto.PRODUTO, null, "Bebidas", "un", Map.of()))).getId());

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(produtos.findById(produtoDaContaA))
                    .as("findById atravessando tenant")
                    .isEmpty();
            assertThat(produtos.findAll())
                    .as("listagem da conta B")
                    .extracting(ProdutoEntity::getId)
                    .doesNotContain(produtoDaContaA);
            assertThat(produtos.findByAtivoTrue())
                    .as("derived query da conta B")
                    .extracting(ProdutoEntity::getId)
                    .doesNotContain(produtoDaContaA);
        });

        // E a conta A continua vendo o proprio dado — filtro nao pode ser "esconde de todos".
        TenantContext.executarComo(contaA.contaId(), () -> {
            assertThat(produtos.findById(produtoDaContaA)).isPresent();
            assertThat(produtos.findAll())
                    .extracting(ProdutoEntity::getId)
                    .containsExactly(produtoDaContaA);
        });
    }

    @Test
    @DisplayName("contaId de um produto vem do contexto, nunca de parametro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Loja Teste", SENHA_DE_TESTE);

        // Repare que nem o construtor de Produto nem o save recebem a conta: nao existe assinatura
        // por onde um chamador pudesse informa-la (RNF05).
        UUID produtoId = TenantContext.executarComo(conta.contaId(), () ->
                produtos.save(ProdutoEntity.de(new Produto("Camiseta", Money.de("59.90"),
                        TipoProduto.PRODUTO, null, null, null, Map.of()))).getId());

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(produtos.findById(produtoId))
                        .get()
                        .extracting(ProdutoEntity::getContaId)
                        .isEqualTo(conta.contaId()));
    }

    @Test
    @DisplayName("o produto volta do banco com o mesmo estado que entrou")
    void produtoSobreviveAoIdaEVolta() {
        ContaCriada conta = criador.criar("Oficina Teste", SENHA_DE_TESTE);

        Produto original = new Produto("Troca de oleo", Money.de("120.00"), TipoProduto.SERVICO,
                "SRV-01", "Manutencao", "hora", Map.of("duracao_minutos", 45));

        TenantContext.executarComo(conta.contaId(), () ->
                produtos.save(ProdutoEntity.de(original)));

        TenantContext.executarComo(conta.contaId(), () -> {
            Produto lido = produtos.findById(original.getId()).orElseThrow().paraDominio();

            assertThat(lido.getId()).isEqualTo(original.getId());
            assertThat(lido.getNome()).isEqualTo("Troca de oleo");
            assertThat(lido.getPreco()).isEqualTo(Money.de("120.00"));
            assertThat(lido.getTipo()).isEqualTo(TipoProduto.SERVICO);
            assertThat(lido.getCodigo()).isEqualTo("SRV-01");
            assertThat(lido.getCategoria()).isEqualTo("Manutencao");
            assertThat(lido.getUnidade()).isEqualTo("hora");
            assertThat(lido.getAtributos()).containsEntry("duracao_minutos", 45);
            assertThat(lido.isAtivo()).isTrue();
            // D16b — servico tambem tem saldo, e nasce zerado.
            assertThat(lido.getEstoqueAtual()).isEqualByComparingTo("0");
        });
    }
}
