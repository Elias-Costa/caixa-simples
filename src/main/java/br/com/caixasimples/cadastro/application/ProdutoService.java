package br.com.caixasimples.cadastro.application;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.shared.Money;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do cadastro de produto e serviço: cadastrar (RF01, RF02), editar (RF04) e inativar
 * (RF05).
 *
 * <p>Cada caso de uso é sempre a mesma sequência: carrega a linha, deixa a raiz do agregado
 * decidir, grava o que ela decidiu. Nenhuma regra mora aqui. É {@link Produto} que sabe o que é
 * nome válido, preço válido e o que pode mudar depois do cadastro.
 *
 * <p><strong>Não existe caso de uso de reativação</strong>, nem de exclusão: o RF05 é soft delete,
 * e {@code DELETE} não aparece em lugar nenhum deste módulo.
 *
 * <p>O código duplicado não é checado antes de gravar. Quem garante a unicidade é o índice único
 * parcial da migration V2, provado em {@code CodigoDeProdutoTest}. Uma consulta prévia aqui
 * duplicaria a regra em dois lugares e ainda assim não dispensaria o índice, porque duas
 * requisições simultâneas passariam pela checagem juntas. Traduzir a violação numa mensagem
 * amigável é trabalho da camada {@code web/}.
 */
@Service
public class ProdutoService {

    private final ProdutoRepository produtos;

    ProdutoService(ProdutoRepository produtos) {
        this.produtos = produtos;
    }

    /**
     * Cadastro de item novo (RF01, RF02).
     *
     * <p><strong>{@code tipo} é parâmetro solto, e não campo de {@link DadosDoProduto}</strong>,
     * para que {@link #editar} não receba um tipo que teria de ser ignorado em silêncio. A regra de
     * que o tipo é imutável depois do cadastro fica visível na assinatura, sem precisar de
     * comentário no ponto de chamada.
     *
     * @return o id do produto criado, gerado na aplicação e nunca pelo banco (RNF01, RNF03)
     */
    @Transactional
    public UUID cadastrar(TipoProduto tipo, DadosDoProduto dados) {
        Objects.requireNonNull(dados, "dados do produto nao podem ser nulos");

        Produto produto = new Produto(dados.nome(), dados.preco(), tipo, dados.codigo(),
                dados.categoria(), dados.unidade(), dados.atributos());

        return produtos.save(ProdutoEntity.de(produto)).getId();
    }

    /**
     * Edição do cadastro (RF04). Substitui todos os campos editáveis pelo que veio em
     * {@code dados}, inclusive os atributos, que não são mesclados.
     *
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se o produto já foi inativado
     */
    @Transactional
    public void editar(UUID id, DadosDoProduto dados) {
        Objects.requireNonNull(dados, "dados do produto nao podem ser nulos");

        ProdutoEntity linha = buscar(id);
        Produto produto = linha.paraDominio();

        produto.alterar(dados.nome(), dados.preco(), dados.codigo(), dados.categoria(),
                dados.unidade(), dados.atributos());

        linha.atualizarCom(produto);
        produtos.save(linha);
    }

    /**
     * Inativação (RF05): soft delete, nunca {@code DELETE}. O item some da listagem ativa e o
     * registro fica, para o histórico de vendas não perder a referência.
     *
     * <p>É idempotente: inativar de novo o que já está inativo não estoura.
     *
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     */
    @Transactional
    public void inativar(UUID id) {
        ProdutoEntity linha = buscar(id);
        Produto produto = linha.paraDominio();

        produto.inativar();

        linha.atualizarCom(produto);
        produtos.save(linha);
    }

    /** Catálogo da conta. Produto inativado não aparece aqui, que é o efeito visível do RF05. */
    @Transactional(readOnly = true)
    public List<Produto> listarAtivos() {
        return produtos.findByAtivoTrue().stream().map(ProdutoEntity::paraDominio).toList();
    }

    private ProdutoEntity buscar(UUID id) {
        Objects.requireNonNull(id, "id do produto nao pode ser nulo");
        return produtos.findById(id).orElseThrow(() -> new ProdutoNaoEncontradoException(id));
    }

    /**
     * Os campos editáveis de um produto, na ordem em que uma tela de cadastro os apresenta.
     *
     * <p>Não tem {@code tipo}, que é imutável depois do cadastro, nem {@code estoqueAtual}, que só
     * se move por movimento de estoque, nem {@code ativo}, porque a inativação é caso de uso
     * próprio. As três ausências são deliberadas.
     *
     * <p>Aninhado no serviço: um record de seis campos usado por um caso de uso só não precisa de
     * arquivo próprio.
     *
     * @param nome      obrigatório
     * @param preco     obrigatório; zero é válido, negativo não
     * @param codigo    opcional
     * @param categoria opcional
     * @param unidade   opcional
     * @param atributos opcional; nulo vira mapa vazio (RF02)
     */
    public record DadosDoProduto(String nome, Money preco, String codigo, String categoria,
            String unidade, Map<String, Object> atributos) {
    }
}
