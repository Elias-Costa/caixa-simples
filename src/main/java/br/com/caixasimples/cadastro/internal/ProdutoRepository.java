package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.TipoMovimentoEstoque;
import br.com.caixasimples.cadastro.TipoProduto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório da raiz de agregado {@code Produto}.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p><strong>Não há consulta por atributo do JSONB aqui</strong>, apesar de o índice GIN existir.
 * Nenhum requisito atual pede filtrar produto por atributo, já que a busca durante a venda é por
 * nome ou código (RF06). Abstração se justifica com uso, não com previsão.
 *
 * <p>{@code MovimentoEstoque} é membro do agregado e não tem repositório próprio: é gravado pela
 * raiz, junto do saldo. A entidade dele nem sequer é visível fora deste pacote, então a regra não
 * depende apenas de disciplina. A única pergunta sobre o membro que se faz aqui,
 * {@link #existsByIdAndMovimentosVendaIdAndMovimentosTipo}, passa pela raiz.
 */
public interface ProdutoRepository extends JpaRepository<ProdutoEntity, UUID> {

    /**
     * Se este produto já tem movimento deste tipo vindo desta venda: com SAIDA, se a venda já deu
     * baixa; com ENTRADA, se o cancelamento já a estornou. São as perguntas que os casos de uso
     * fazem antes de gravar, porque os eventos de venda concluída e de venda cancelada podem
     * chegar mais de uma vez e a raiz, que não carrega o histórico, não tem como responder
     * sozinha.
     *
     * <p>Derivada, e não {@code @Query}, que o projeto não usa: o caminho {@code movimentos}
     * atravessa a coleção da raiz com um {@code JOIN}, sem carregar o histórico na memória. Os
     * dois filtros sobre {@code movimentos} caem na <strong>mesma linha</strong> do histórico,
     * porque o construtor de consulta reaproveita o {@code JOIN} pelo caminho da propriedade em
     * vez de abrir um por filtro; é isso que faz a pergunta significar a venda desta linha com o
     * tipo desta linha, e não uma venda qualquer com um tipo qualquer. O teste do membro do
     * agregado prova essa leitura com uma SAIDA de uma venda e uma ENTRADA de outra. Sem
     * {@code conta_id} na assinatura, como todo o resto: o {@code @TenantId} filtra (RNF05).
     */
    boolean existsByIdAndMovimentosVendaIdAndMovimentosTipo(UUID id, UUID vendaId,
            TipoMovimentoEstoque tipo);

    List<ProdutoEntity> findByAtivoTrue();

    /**
     * Os produtos ativos de um tipo. É a base da lista de estoque baixo (RF20): só PRODUTO tem
     * estoque, e item fora do catálogo não vai ser reposto.
     *
     * <p>A comparação do saldo com o mínimo <strong>não está aqui</strong>, de propósito. Consulta
     * derivada compara coluna com parâmetro, não coluna com coluna, e o projeto não escreve
     * {@code @Query}; a regra de estar baixo fica no domínio, em {@code Produto}, e o caso de uso
     * a aplica em memória sobre esta lista. O custo é o mesmo já aceito na busca do balcão: o
     * catálogo de uma conta é pequeno, e o filtro de tenant já reduz a varredura ao que é dela.
     */
    List<ProdutoEntity> findByAtivoTrueAndTipo(TipoProduto tipo);

    /**
     * O produto ativo com este código, ignorando maiúsculas (RF06).
     *
     * <p>Devolve {@link Optional}, e não lista, porque a migration V2 garante um código por conta
     * entre os ativos; é o índice único parcial que permite esta assinatura. Casa o código
     * inteiro, nunca prefixo: código é o que o operador digita ou lê da embalagem, e um prefixo
     * devolveria mais de um produto para um valor que deveria ser único.
     */
    Optional<ProdutoEntity> findByAtivoTrueAndCodigoIgnoreCase(String codigo);

    /**
     * Os produtos ativos cujo nome contém o termo, ignorando maiúsculas, em ordem alfabética
     * (RF06).
     *
     * <p>{@code Containing} derivado escapa {@code %} e {@code _} do termo por conta própria, o que
     * é um motivo a mais para não escrever o {@code LIKE} à mão. Sem índice para sustentar a
     * busca: o catálogo de uma conta é pequeno, e o índice de tenant já reduz a varredura ao que é
     * dela.
     */
    List<ProdutoEntity> findByAtivoTrueAndNomeContainingIgnoreCaseOrderByNome(String termo);
}
