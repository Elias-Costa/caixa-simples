package br.com.caixasimples.cadastro.application;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do cadastro de produto e serviço: cadastrar (RF01, RF02), editar (RF04), inativar
 * (RF05), buscar por nome ou código durante a venda (RF06) e responder o preço vigente a quem
 * monta a venda.
 *
 * <p>Cada caso de uso de escrita é sempre a mesma sequência: carrega a linha, deixa a raiz do
 * agregado decidir, grava o que ela decidiu. Nenhuma regra mora aqui. É {@link Produto} que sabe o
 * que é nome válido, preço válido e o que pode mudar depois do cadastro.
 *
 * <p><strong>Este pacote é a API do módulo para os outros módulos</strong>, e por isso
 * {@link #consultarParaVenda} devolve um record próprio, e não {@link Produto}: quem está fora do
 * cadastro recebe o que precisa copiar, sem receber a raiz do agregado e seus mutadores.
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

    /**
     * Busca durante a venda (RF06): o que o operador digita no balcão, seja nome ou código.
     *
     * <p><strong>Nome contém o termo; código casa inteiro.</strong> Os dois ignoram maiúsculas.
     * Nome parcial é tolerância com quem digita rápido: {@code leite} acha o café com leite. Código
     * parcial não é: código é o que se lê da embalagem ou se decorou, e um prefixo devolveria mais
     * de um produto para um valor que deveria ser único.
     *
     * <p>O produto cujo código bateu vem primeiro, porque é a resposta mais certa; depois vêm os
     * nomes, em ordem alfabética. Um produto que bateu pelos dois aparece uma vez só.
     *
     * <p>Só entre os ativos, como {@link #listarAtivos}: produto inativado não se vende.
     *
     * <p>Termo em branco é recusado em vez de virar o catálogo inteiro. Os dois casos de uso
     * existem separados, e uma busca que silenciosamente vira listagem esconde a diferença de quem
     * a chama.
     *
     * @throws IllegalArgumentException se o termo é nulo ou está em branco
     */
    @Transactional(readOnly = true)
    public List<Produto> buscarPorNomeOuCodigo(String termo) {
        if (termo == null || termo.isBlank()) {
            throw new IllegalArgumentException(
                    "informe nome ou codigo para buscar; o catalogo inteiro e listarAtivos");
        }
        String termoLimpo = termo.trim();

        List<ProdutoEntity> encontrados = new ArrayList<>();
        produtos.findByAtivoTrueAndCodigoIgnoreCase(termoLimpo).ifPresent(encontrados::add);

        for (ProdutoEntity porNome
                : produtos.findByAtivoTrueAndNomeContainingIgnoreCaseOrderByNome(termoLimpo)) {
            boolean jaEntrouPeloCodigo = encontrados.stream()
                    .anyMatch(candidato -> candidato.getId().equals(porNome.getId()));
            if (!jaEntrouPeloCodigo) {
                encontrados.add(porNome);
            }
        }

        return encontrados.stream().map(ProdutoEntity::paraDominio).toList();
    }

    /**
     * O que a montagem da venda precisa saber de um produto: o preço vigente, para copiar, e se
     * ele está ativo, para recusar.
     *
     * <p>Devolve o produto inativo em vez de recusá-lo, de propósito: quem decide o que entra numa
     * venda é o módulo de vendas, e a regra de recusar produto inativado mora lá, junto das outras
     * regras da montagem. Aqui é só a pergunta.
     *
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     */
    @Transactional(readOnly = true)
    public ProdutoParaVenda consultarParaVenda(UUID id) {
        Produto produto = buscar(id).paraDominio();
        return new ProdutoParaVenda(produto.getId(), produto.getPreco(), produto.isAtivo());
    }

    private ProdutoEntity buscar(UUID id) {
        Objects.requireNonNull(id, "id do produto nao pode ser nulo");
        return produtos.findById(id).orElseThrow(() -> new ProdutoNaoEncontradoException(id));
    }

    /**
     * A resposta de {@link #consultarParaVenda}.
     *
     * <p>Record próprio, e não {@link Produto}, porque atravessa a fronteira do módulo: o módulo de
     * vendas recebe o preço para copiar e o estado para conferir, e nada mais. Se recebesse a raiz
     * do agregado, receberia também os mutadores dela.
     *
     * @param id    o mesmo id consultado, devolvido para a chamada ser autoexplicativa
     * @param preco o preço vigente, que a venda copia e nunca lê de novo
     * @param ativo falso quando o produto foi inativado (RF05)
     */
    public record ProdutoParaVenda(UUID id, Money preco, boolean ativo) {
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
