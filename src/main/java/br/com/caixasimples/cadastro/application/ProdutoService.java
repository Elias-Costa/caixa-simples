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
 * Casos de uso do cadastro de produto e servico: cadastrar (RF01, RF02), editar (RF04) e inativar
 * (RF05). Passo R03 do roteiro, etapa 1.1 do plano.
 *
 * <p>Cada caso de uso e sempre a mesma sequencia — carrega a linha, deixa a raiz do agregado
 * decidir, grava o que ela decidiu. Nenhuma regra mora aqui: {@link Produto} e que sabe o que e
 * nome valido, preco valido e o que pode mudar depois do cadastro.
 *
 * <p><strong>Nao existe caso de uso de reativacao</strong> (D17b), nem de exclusao: RF05 e soft
 * delete, e {@code DELETE} nao aparece em lugar nenhum deste modulo.
 *
 * <p>O codigo duplicado nao e checado antes de gravar. Quem garante a unicidade da D11 e o indice
 * unico parcial da {@code V2}, provado em {@code CodigoDeProdutoTest}: uma consulta previa aqui
 * duplicaria a regra em dois lugares e ainda assim nao dispensaria o indice, porque duas
 * requisicoes simultaneas passariam pela checagem juntas. Traduzir a violacao numa mensagem
 * amigavel e trabalho da camada {@code web/}, que nasce depois.
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
     * <p><strong>{@code tipo} e parametro solto, e nao campo de {@link DadosDoProduto}</strong>:
     * assim {@link #editar} nao recebe um tipo que teria de ser ignorado em silencio. A D17a fica
     * visivel na assinatura, sem precisar de comentario no ponto de chamada.
     *
     * @return o id do produto criado — gerado na aplicacao, nunca pelo banco (RNF01/RNF03)
     */
    @Transactional
    public UUID cadastrar(TipoProduto tipo, DadosDoProduto dados) {
        Objects.requireNonNull(dados, "dados do produto nao podem ser nulos");

        Produto produto = new Produto(dados.nome(), dados.preco(), tipo, dados.codigo(),
                dados.categoria(), dados.unidade(), dados.atributos());

        return produtos.save(ProdutoEntity.de(produto)).getId();
    }

    /**
     * Edicao do cadastro (RF04). Substitui todos os campos editaveis pelo que veio em
     * {@code dados} — inclusive os atributos, que nao sao mesclados.
     *
     * @throws ProdutoNaoEncontradoException se o id nao existe nesta conta
     * @throws IllegalStateException         se o produto ja foi inativado (D17c)
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
     * Inativacao (RF05) — soft delete, nunca {@code DELETE}: o item some da listagem ativa e o
     * registro fica, para o historico de vendas nao perder a referencia.
     *
     * <p>Idempotente (D17c): inativar de novo o que ja esta inativo nao estoura.
     *
     * @throws ProdutoNaoEncontradoException se o id nao existe nesta conta
     */
    @Transactional
    public void inativar(UUID id) {
        ProdutoEntity linha = buscar(id);
        Produto produto = linha.paraDominio();

        produto.inativar();

        linha.atualizarCom(produto);
        produtos.save(linha);
    }

    /** Catalogo da conta. Produto inativado nao aparece aqui — e o efeito visivel do RF05. */
    @Transactional(readOnly = true)
    public List<Produto> listarAtivos() {
        return produtos.findByAtivoTrue().stream().map(ProdutoEntity::paraDominio).toList();
    }

    private ProdutoEntity buscar(UUID id) {
        Objects.requireNonNull(id, "id do produto nao pode ser nulo");
        return produtos.findById(id).orElseThrow(() -> new ProdutoNaoEncontradoException(id));
    }

    /**
     * Os campos editaveis de um produto, na ordem em que uma tela de cadastro os apresenta.
     *
     * <p>Nao tem {@code tipo} (D17a), nem {@code estoqueAtual} (so se move por
     * {@code MovimentoEstoque}, R15), nem {@code ativo} (a inativacao e caso de uso proprio) — as
     * tres ausencias sao deliberadas.
     *
     * <p>Aninhado no servico, como {@code CriadorDeContaDeTeste.ContaCriada}: um record de seis
     * campos usado por um caso de uso so nao precisa de arquivo proprio.
     *
     * @param nome      obrigatorio
     * @param preco     obrigatorio; zero e valido, negativo nao (D16c)
     * @param codigo    opcional (D11)
     * @param categoria opcional (D16a)
     * @param unidade   opcional (D16a)
     * @param atributos opcional; nulo vira mapa vazio (RF02)
     */
    public record DadosDoProduto(String nome, Money preco, String codigo, String categoria,
            String unidade, Map<String, Object> atributos) {
    }
}
