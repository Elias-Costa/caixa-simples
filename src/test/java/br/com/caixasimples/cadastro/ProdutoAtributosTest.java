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
 * RF02/RNF12 — dois tipos de negocio com atributos completamente diferentes na <strong>mesma
 * tabela, sem alterar schema</strong>. E a premissa do nucleo generico (escopo §1) levada ao dado:
 * colunas fixas para o universal, JSONB para o que varia por nicho (arquitetura §4).
 *
 * <p>O filtro por atributo roda em SQL direto aqui, e nao pelo {@code ProdutoRepository}: o que
 * esta sob teste e o <em>schema</em> — o operador {@code @>} e o indice GIN da migration. A regra
 * que proibe query nativa vale para codigo de negocio, que precisa do {@code @TenantId}; um teste
 * que escreve {@code conta_id} explicito no {@code WHERE} esta provando justamente a camada de
 * baixo. Nenhum requisito da Fase 1 pede filtrar produto por atributo, entao o repositorio nao
 * ganha esse metodo (RF06, busca por nome ou codigo, e a etapa 1.6).
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

        // Round-trip pelo JPA: o mapa volta com os mesmos pares, sem coluna nova para nenhum dos dois.
        TenantContext.executarComo(cafeteria.contaId(), () ->
                assertThat(produtos.findById(cafe).orElseThrow().paraDominio().getAtributos())
                        .containsExactlyEntriesOf(Map.of("tempo_preparo", 5)));

        TenantContext.executarComo(loja.contaId(), () ->
                assertThat(produtos.findById(camiseta).orElseThrow().paraDominio().getAtributos())
                        .containsEntry("tamanho", "M")
                        .containsEntry("cor", "preta"));
    }

    @Test
    @DisplayName("filtro por atributo dentro do JSONB encontra so quem casa")
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
