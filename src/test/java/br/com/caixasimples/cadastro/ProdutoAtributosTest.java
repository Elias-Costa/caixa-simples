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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Dois tipos de negócio com atributos completamente diferentes na <strong>mesma tabela, sem alterar
 * schema</strong> (RF02, RNF12). É a premissa do núcleo genérico levada ao dado: colunas fixas para
 * o que é universal, JSONB para o que varia por nicho.
 *
 * <p>O filtro por atributo roda em SQL direto aqui, e não pelo {@code ProdutoRepository}, porque o
 * que está sob teste é o <em>schema</em>: o operador de contenção do JSONB e o índice GIN da
 * migration. A regra que proíbe query nativa vale para código de negócio, que precisa do
 * {@code @TenantId}; um teste que escreve {@code conta_id} explícito no {@code WHERE} está provando
 * justamente a camada de baixo. Nenhum requisito atual pede filtrar produto por atributo, então o
 * repositório não ganha esse método.
 */
class ProdutoAtributosTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("cafeteria e loja de roupa convivem na mesma tabela com atributos distintos")
    void doisTiposDeNegocioNaMesmaTabela() {
        ContaCriada cafeteria = criador.criar("Cafeteria do Centro", SENHA_DE_TESTE);
        ContaCriada loja = criador.criar("Loja de Roupa", SENHA_DE_TESTE);

        UUID cafe = TenantContext.executarComo(cafeteria.contaId(), () ->
                produtos.save(ProdutoEntity.de(new Produto("Cappuccino", Money.de("9.00"),
                        TipoProduto.PRODUTO, null, "Bebidas", "un",
                        Map.of("tempo_preparo", 5)))).getId());

        UUID camiseta = TenantContext.executarComo(loja.contaId(), () ->
                produtos.save(ProdutoEntity.de(new Produto("Camiseta lisa", Money.de("49.90"),
                        TipoProduto.PRODUTO, null, "Vestuario", "un",
                        Map.of("tamanho", "M", "cor", "preta")))).getId());

        // Ida e volta pelo JPA: o mapa retorna com os mesmos pares, sem coluna nova para nenhum dos
        // dois tipos de negócio.
        TenantContext.executarComo(cafeteria.contaId(), () ->
                assertThat(produtos.findById(cafe).orElseThrow().paraDominio().getAtributos())
                        .containsExactlyEntriesOf(Map.of("tempo_preparo", 5)));

        TenantContext.executarComo(loja.contaId(), () ->
                assertThat(produtos.findById(camiseta).orElseThrow().paraDominio().getAtributos())
                        .containsEntry("tamanho", "M")
                        .containsEntry("cor", "preta"));
    }

    @Test
    @DisplayName("filtro por atributo dentro do JSONB encontra só quem casa")
    void filtraPorAtributo() {
        ContaCriada loja = criador.criar("Loja com Grades", SENHA_DE_TESTE);

        UUID tamanhoM = TenantContext.executarComo(loja.contaId(), () -> {
            produtos.save(ProdutoEntity.de(new Produto("Camiseta P", Money.de("49.90"),
                    TipoProduto.PRODUTO, null, "Vestuario", "un", Map.of("tamanho", "P"))));
            produtos.save(ProdutoEntity.de(new Produto("Bermuda G", Money.de("79.90"),
                    TipoProduto.PRODUTO, null, "Vestuario", "un", Map.of("tamanho", "G"))));
            return produtos.save(ProdutoEntity.de(new Produto("Camiseta M", Money.de("49.90"),
                    TipoProduto.PRODUTO, null, "Vestuario", "un", Map.of("tamanho", "M")))).getId();
        });

        List<UUID> encontrados = jdbc.sql("""
                        SELECT id FROM produto
                         WHERE conta_id = ?
                           AND atributos @> cast(? as jsonb)
                        """)
                .param(loja.contaId().valor())
                .param("{\"tamanho\": \"M\"}")
                .query(UUID.class)
                .list();

        assertThat(encontrados).containsExactly(tamanhoM);
    }
}
