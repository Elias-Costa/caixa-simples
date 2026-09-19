package br.com.caixasimples.cadastro.application;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.domain.MovimentoEstoque;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do cadastro de produto e serviço: cadastrar (RF01, RF02), editar (RF04), inativar
 * (RF05), buscar por nome ou código durante a venda (RF06), responder o preço vigente a quem
 * monta a venda, dar baixa no estoque de um produto vendido (RF18), ajustar o estoque à mão
 * (RF19) e responder quais produtos estão com estoque baixo (RF20).
 *
 * <p>Cada caso de uso de escrita é sempre a mesma sequência: carrega a linha, deixa a raiz do
 * agregado decidir, grava o que ela decidiu. Nenhuma regra mora aqui. É {@link Produto} que sabe o
 * que é nome válido, preço válido e o que pode mudar depois do cadastro.
 *
 * <p><strong>Este pacote é a API do módulo para os outros módulos</strong>, e por isso
 * {@link #consultarParaVenda} devolve um record próprio, e não {@link Produto}: quem está fora do
 * cadastro recebe o que precisa copiar, sem receber a raiz do agregado e seus mutadores.
 *
 * <p><strong>Os casos de uso de estoque são públicos, e a exceção que eles abrem é medida.</strong>
 * A regra do projeto é que efeito colateral entre módulos viaja por evento; aqui é o módulo de
 * estoque que ouve a venda concluída e decide, com a conta ligada ou não, se há o que baixar.
 * Decidido, ele pede ao cadastro, porque o agregado é daqui e ninguém de fora toca a entidade.
 * O evento continua desacoplando a venda de quem reage a ela; o que a baixa faz é executar a
 * reação de outro módulo sobre o agregado que ele não pode abrir. O ajuste manual, o estoque
 * mínimo e a lista de estoque baixo seguem o mesmo desenho: o módulo de estoque decide se a
 * conta participa, e este serviço executa sobre o agregado.
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

    private static final Logger log = LoggerFactory.getLogger(ProdutoService.class);

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

    /**
     * Baixa de estoque de um item vendido (RF18). Grava o movimento de SAIDA e o saldo novo do
     * produto na mesma transação; é o único caminho pelo qual {@code estoque_atual} muda.
     *
     * <p><strong>Em SERVICO não faz nada</strong>, e não é erro: vender um serviço é legítimo, só
     * não há estoque a baixar. Quem chama recebe os itens da venda sem saber o tipo de cada um, e
     * é aqui, com o produto na mão, que a distinção se faz. A raiz recusa a baixa em serviço por
     * conta própria; este método pergunta antes para não chegar lá.
     *
     * <p><strong>A mesma venda não baixa duas vezes.</strong> O evento de venda concluída é
     * entregue ao menos uma vez, e quem chama deve perguntar por {@link #jaDeuBaixaPorVenda} antes,
     * e pular a reentrega. A recusa aqui é a invariante em si, para qualquer chamador: estoque
     * baixado em dobro é uma falta que nunca existiu. A raiz não carrega o histórico, então a
     * pergunta vai ao repositório; a rede embaixo é o índice único da migration V9.
     *
     * <p>Não olha se o produto está ativo, de propósito: a venda aconteceu antes de qualquer
     * inativação, e o estoque que saiu, saiu.
     *
     * @param produtoId  o produto vendido, nesta conta
     * @param quantidade o que a venda levou; positiva
     * @param vendaId    a venda que levou
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se esta venda já deu baixa neste produto
     * @throws IllegalArgumentException      se a quantidade não é positiva
     */
    @Transactional
    public void darBaixaPorVenda(UUID produtoId, BigDecimal quantidade, UUID vendaId) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");

        ProdutoEntity linha = buscar(produtoId);
        Produto produto = linha.paraDominio();

        if (!produto.controlaEstoque()) {
            log.debug("produto {} e servico; venda {} nao gera movimento de estoque", produtoId,
                    vendaId);
            return;
        }
        if (produtos.existsByIdAndMovimentosVendaId(produtoId, vendaId)) {
            throw new IllegalStateException(
                    "venda " + vendaId + " ja deu baixa no produto " + produtoId
                            + " e nao baixa de novo. O estoque de uma venda sai uma vez so.");
        }

        MovimentoEstoque movimento = produto.darBaixaPorVenda(quantidade, vendaId);

        linha.registrarMovimento(produto, movimento);
        produtos.save(linha);
    }

    /**
     * Se esta venda já deu baixa neste produto. É a pergunta que o ouvinte do evento faz antes de
     * pedir a baixa, porque o evento pode chegar mais de uma vez e a reentrega tem de terminar sem
     * erro.
     *
     * <p>Responde falso para um produto que não existe nesta conta, em vez de lançar: a pergunta
     * é sobre o movimento, e a ausência do produto vai estourar logo em seguida, em
     * {@link #darBaixaPorVenda}, com a exceção certa.
     */
    @Transactional(readOnly = true)
    public boolean jaDeuBaixaPorVenda(UUID produtoId, UUID vendaId) {
        Objects.requireNonNull(produtoId, "id do produto nao pode ser nulo");
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        return produtos.existsByIdAndMovimentosVendaId(produtoId, vendaId);
    }

    /**
     * Ajuste manual do estoque (RF19): perda, quebra ou contagem, com motivo obrigatório. Grava o
     * movimento de AJUSTE e o saldo novo na mesma transação, pelo mesmo caminho único da baixa por
     * venda.
     *
     * <p>A diferença carrega o sinal: negativa subtrai, positiva soma. As regras, motivo
     * obrigatório, diferença diferente de zero, só produto ativo, moram na raiz; aqui é carregar,
     * deixar decidir e gravar.
     *
     * @param produtoId o produto, nesta conta
     * @param diferenca o que soma ou subtrai do saldo, com sinal; nunca zero
     * @param motivo    obrigatório (RF19)
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se o item é SERVICO ou está inativo
     * @throws IllegalArgumentException      se a diferença é zero ou falta o motivo
     */
    @Transactional
    public void ajustarEstoque(UUID produtoId, BigDecimal diferenca, String motivo) {
        ProdutoEntity linha = buscar(produtoId);
        Produto produto = linha.paraDominio();

        MovimentoEstoque movimento = produto.ajustarEstoque(diferenca, motivo);

        linha.registrarMovimento(produto, movimento);
        produtos.save(linha);
    }

    /**
     * Define o limiar do alerta de estoque baixo de um produto (RF20). É caso de uso próprio, e
     * não campo de {@link DadosDoProduto}, porque o limiar é política de estoque e não descrição
     * do item: o formulário de cadastro não carrega um campo que só faz sentido com o controle de
     * estoque ligado.
     *
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se o item é SERVICO ou está inativo
     * @throws IllegalArgumentException      se o mínimo é negativo ou tem mais de três casas
     */
    @Transactional
    public void definirEstoqueMinimo(UUID produtoId, BigDecimal minimo) {
        ProdutoEntity linha = buscar(produtoId);
        Produto produto = linha.paraDominio();

        produto.definirEstoqueMinimo(minimo);

        linha.atualizarCom(produto);
        produtos.save(linha);
    }

    /**
     * Os produtos ativos cujo saldo está no limiar do alerta ou abaixo dele (RF20), na ordem em
     * que o banco os devolve.
     *
     * <p>Só entre os ativos e só PRODUTO: serviço não tem estoque, e item fora do catálogo não
     * vai ser reposto. A comparação com o mínimo é feita em memória pelo domínio, porque consulta
     * derivada não compara duas colunas e o projeto não escreve {@code @Query}; o catálogo de uma
     * conta é pequeno, e é o mesmo custo já aceito na busca do balcão.
     *
     * <p>Não pergunta se a conta ligou o controle de estoque: essa decisão é do módulo de estoque,
     * que é quem chama. Aqui é só a resposta.
     */
    @Transactional(readOnly = true)
    public List<EstoqueDoProduto> listarComEstoqueBaixo() {
        return produtos.findByAtivoTrueAndTipo(TipoProduto.PRODUTO).stream()
                .map(ProdutoEntity::paraDominio)
                .filter(Produto::estaComEstoqueBaixo)
                .map(produto -> new EstoqueDoProduto(produto.getId(), produto.getNome(),
                        produto.getCodigo(), produto.getUnidade(), produto.getEstoqueAtual(),
                        produto.getEstoqueMinimo()))
                .toList();
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
     * A resposta de {@link #listarComEstoqueBaixo}: o que uma tela de alerta mostra de cada item.
     *
     * <p>Record próprio pelo mesmo motivo de {@link ProdutoParaVenda}: atravessa a fronteira do
     * módulo, e o módulo de estoque recebe os números para mostrar, não a raiz do agregado com
     * seus mutadores.
     *
     * @param id            o produto
     * @param nome          para a tela nomear o item
     * @param codigo        pode ser nulo, como no cadastro
     * @param unidade       pode ser nula; dá sentido ao saldo (2 kg, 2 un)
     * @param estoqueAtual  o saldo consolidado, que pode ser negativo
     * @param estoqueMinimo o limiar que o saldo atingiu
     */
    public record EstoqueDoProduto(UUID id, String nome, String codigo, String unidade,
            BigDecimal estoqueAtual, BigDecimal estoqueMinimo) {
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
