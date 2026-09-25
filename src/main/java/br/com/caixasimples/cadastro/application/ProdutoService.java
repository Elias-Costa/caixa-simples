package br.com.caixasimples.cadastro.application;

import br.com.caixasimples.cadastro.TipoMovimentoEstoque;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.domain.MovimentoEstoque;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.UsuarioContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do cadastro de produto e serviço: cadastrar (RF01, RF02), editar (RF04), inativar
 * (RF05), buscar por nome ou código durante a venda (RF06), responder o preço vigente a quem
 * monta a venda e o nome a quem imprime o comprovante (RF11), dar baixa no estoque de um produto
 * vendido (RF18) e devolvê-lo quando a venda é cancelada (RF12), ajustar o estoque à mão (RF19) e
 * responder quais produtos estão com estoque baixo (RF20).
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
 * estoque que ouve a venda concluída, e a venda cancelada, e decide, com a conta ligada ou não,
 * se há o que baixar ou devolver. Decidido, ele pede ao cadastro, porque o agregado é daqui e
 * ninguém de fora toca a entidade. O evento continua desacoplando a venda de quem reage a ela; o
 * que a baixa e o estorno fazem é executar a reação de outro módulo sobre o agregado que ele não
 * pode abrir. O ajuste manual, o estoque mínimo e a lista de estoque baixo seguem o mesmo
 * desenho: o módulo de estoque decide se a conta participa, e este serviço executa sobre o
 * agregado.
 *
 * <p><strong>Não existe caso de uso de reativação</strong>, nem de exclusão: o RF05 é soft delete,
 * e {@code DELETE} não aparece em lugar nenhum deste módulo.
 *
 * <p><strong>Quem pode chamar o quê (RF30).</strong> Cadastrar, editar e inativar são do
 * administrador, e perguntam isso na primeira linha. As consultas servem aos dois perfis, porque
 * o operador precisa achar o produto para vender. Os casos de uso de estoque não perguntam:
 * a baixa e o estorno são acionados por ouvinte de evento, sem pessoa por trás, e o ajuste, o
 * mínimo e a lista de estoque baixo são executados a pedido do módulo de estoque, que é quem
 * verifica o perfil antes de pedir, junto com a conta ter o controle ligado.
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
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     */
    @Transactional
    public UUID cadastrar(TipoProduto tipo, DadosDoProduto dados) {
        return cadastrar(UUID.randomUUID(), tipo, dados, Instant.now());
    }

    /**
     * O mesmo cadastro, com o id e o instante que o dispositivo gravou ao cadastrar o item sem
     * rede (RNF01). Um id que já existe é recusado pela chave primária, porque a linha nova é
     * inserida e nunca mesclada sobre outra: a entidade tem versão, e a versão vazia diz que ela é
     * nova.
     *
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     */
    @Transactional
    public UUID cadastrar(UUID id, TipoProduto tipo, DadosDoProduto dados, Instant criadoEm) {
        UsuarioContext.exigirAdmin();
        Objects.requireNonNull(dados, "dados do produto nao podem ser nulos");

        Produto produto = new Produto(id, criadoEm, dados.nome(), dados.preco(), tipo,
                dados.codigo(), dados.categoria(), dados.unidade(), dados.atributos());

        return produtos.save(ProdutoEntity.de(produto)).getId();
    }

    /**
     * Edição do cadastro (RF04). Substitui todos os campos editáveis pelo que veio em
     * {@code dados}, inclusive os atributos, que não são mesclados.
     *
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se o produto já foi inativado
     */
    @Transactional
    public void editar(UUID id, DadosDoProduto dados) {
        UsuarioContext.exigirAdmin();
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
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     */
    @Transactional
    public void inativar(UUID id) {
        UsuarioContext.exigirAdmin();
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

    /** A revisão vai junto da lista para o dispositivo guardar a versão que leu. */
    @Transactional(readOnly = true)
    public List<ProdutoComVersao> listarAtivosComVersao() {
        return produtos.findByAtivoTrue().stream()
                .map(linha -> new ProdutoComVersao(linha.paraDominio(), linha.getVersao()))
                .toList();
    }

    public record ProdutoComVersao(Produto produto, long versao) {
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
        return buscarPorNomeOuCodigoComVersao(termo).stream()
                .map(ProdutoComVersao::produto).toList();
    }

    @Transactional(readOnly = true)
    public List<ProdutoComVersao> buscarPorNomeOuCodigoComVersao(String termo) {
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

        return encontrados.stream()
                .map(linha -> new ProdutoComVersao(linha.paraDominio(), linha.getVersao()))
                .toList();
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
     * O que o comprovante de uma venda (RF11) precisa saber dos produtos das linhas dela: o nome
     * e a unidade, como estão hoje.
     *
     * <p>Em lote, porque um comprovante tem várias linhas e a pergunta é uma só. O item da venda
     * guarda o preço copiado, mas não o nome: o preço é cópia porque muda a conta; o nome não.
     * Custo aceito: um produto renomeado depois da venda sai com o nome novo numa reimpressão.
     *
     * <p>Produto inativo é devolvido como qualquer outro. O histórico de vendas continua apontando
     * para ele (RF05), e o comprovante de uma venda passada precisa do nome mesmo que o item já
     * tenha saído do catálogo.
     *
     * @param ids os produtos das linhas; repetido conta uma vez
     * @throws ProdutoNaoEncontradoException se algum id não existe nesta conta
     */
    @Transactional(readOnly = true)
    public List<ProdutoParaComprovante> consultarParaComprovante(Collection<UUID> ids) {
        Objects.requireNonNull(ids, "ids dos produtos nao podem ser nulos");

        List<ProdutoEntity> encontrados = produtos.findAllById(ids);

        // findAllById devolve só o que existe nesta conta, sem dizer o que faltou. Um id que não
        // voltou é um id que não existe ou é de outra conta, e a resposta é a mesma da consulta
        // unitária: falha alta, e não uma linha sem nome no comprovante.
        Set<UUID> devolvidos = encontrados.stream()
                .map(ProdutoEntity::getId)
                .collect(Collectors.toSet());
        for (UUID id : ids) {
            if (!devolvidos.contains(id)) {
                throw new ProdutoNaoEncontradoException(id);
            }
        }

        return encontrados.stream()
                .map(ProdutoEntity::paraDominio)
                .map(produto -> new ProdutoParaComprovante(produto.getId(), produto.getNome(),
                        produto.getUnidade()))
                .toList();
    }

    /**
     * Baixa de estoque de um item vendido (RF18). Grava o movimento de SAIDA e o saldo novo do
     * produto na mesma transação, pelo caminho único que escreve {@code estoque_atual}.
     *
     * <p><strong>Em SERVICO não faz nada</strong>, e não é erro: vender um serviço é legítimo, só
     * não há estoque a baixar. Quem chama recebe os itens da venda sem saber o tipo de cada um, e
     * é aqui, com o produto na mão, que a distinção se faz. A raiz recusa a baixa em serviço por
     * conta própria; este método pergunta antes para não chegar lá.
     *
     * <p><strong>A mesma venda não baixa duas vezes.</strong> A recusa aqui é a invariante em si,
     * para qualquer chamador: estoque baixado em dobro é uma falta que nunca existiu. A raiz não
     * carrega o histórico, então a pergunta vai ao repositório; a rede embaixo é o índice único da
     * migration V9.
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
        if (temMovimentoDaVenda(produtoId, vendaId, TipoMovimentoEstoque.SAIDA)) {
            throw new IllegalStateException(
                    "venda " + vendaId + " ja deu baixa no produto " + produtoId
                            + " e nao baixa de novo. O estoque de uma venda sai uma vez so.");
        }

        MovimentoEstoque movimento = produto.darBaixaPorVenda(quantidade, vendaId);

        linha.registrarMovimento(produto, movimento);
        produtos.save(linha);
    }

    /**
     * Se esta venda já deu baixa neste produto. É o que o ouvinte do cancelamento pergunta antes
     * de pedir o estorno: uma conta que ligou o controle de estoque depois da venda não tem baixa a
     * devolver.
     *
     * <p>Responde falso para um produto que não existe nesta conta, em vez de lançar: a pergunta
     * é sobre o movimento, e produto que não existe não teve baixa. Continua verdadeira depois do
     * estorno: a baixa aconteceu, e o estorno é outro movimento.
     */
    @Transactional(readOnly = true)
    public boolean jaDeuBaixaPorVenda(UUID produtoId, UUID vendaId) {
        return temMovimentoDaVenda(produtoId, vendaId, TipoMovimentoEstoque.SAIDA);
    }

    /**
     * Estorno de estoque de um item cuja venda foi cancelada (RF12): o oposto exato de
     * {@link #darBaixaPorVenda}. Grava o movimento de ENTRADA e o saldo novo do produto na mesma
     * transação, pelo mesmo caminho único.
     *
     * <p><strong>Em SERVICO não faz nada</strong>, como na baixa: não há estoque a devolver, e
     * quem chama recebe os itens da venda sem saber o tipo de cada um.
     *
     * <p><strong>Só se devolve o que saiu, e uma vez só.</strong> Uma venda que não deu baixa
     * neste produto, porque a conta ligou o controle de estoque depois dela, não tem o que
     * estornar, e a recusa é alta em vez de somar um estoque que nunca foi tirado. O estorno em
     * dobro também é recusado aqui, para qualquer chamador. A raiz não carrega o histórico, então
     * as duas perguntas vão ao repositório; a rede embaixo é o índice único da migration V9.
     *
     * <p>Não olha se o produto está ativo, pelo mesmo motivo da baixa: a venda aconteceu, e o
     * estoque que volta, volta.
     *
     * @param produtoId  o produto da venda cancelada, nesta conta
     * @param quantidade o que a venda tinha levado, e volta; positiva
     * @param vendaId    a venda cancelada
     * @throws ProdutoNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se esta venda não deu baixa neste produto, ou se já
     *                                       foi estornada nele
     * @throws IllegalArgumentException      se a quantidade não é positiva
     */
    @Transactional
    public void estornarPorCancelamento(UUID produtoId, BigDecimal quantidade, UUID vendaId) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");

        ProdutoEntity linha = buscar(produtoId);
        Produto produto = linha.paraDominio();

        if (!produto.controlaEstoque()) {
            log.debug("produto {} e servico; cancelamento da venda {} nao gera movimento de"
                    + " estoque", produtoId, vendaId);
            return;
        }
        if (!temMovimentoDaVenda(produtoId, vendaId, TipoMovimentoEstoque.SAIDA)) {
            throw new IllegalStateException(
                    "venda " + vendaId + " nao deu baixa no produto " + produtoId
                            + " e nao tem o que estornar. So se devolve o que saiu.");
        }
        if (temMovimentoDaVenda(produtoId, vendaId, TipoMovimentoEstoque.ENTRADA)) {
            throw new IllegalStateException(
                    "venda " + vendaId + " ja foi estornada no produto " + produtoId
                            + " e nao estorna de novo. O estoque de uma venda volta uma vez so.");
        }

        MovimentoEstoque movimento = produto.estornarPorCancelamento(quantidade, vendaId);

        linha.registrarMovimento(produto, movimento);
        produtos.save(linha);
    }

    /**
     * Se o cancelamento desta venda já devolveu o estoque deste produto: a mesma pergunta que
     * {@link #estornarPorCancelamento} faz antes de recusar o estorno em dobro, aberta a quem
     * precisa conferir o histórico. Falso para produto que não existe nesta conta, pelo mesmo
     * motivo de {@link #jaDeuBaixaPorVenda}.
     */
    @Transactional(readOnly = true)
    public boolean jaEstornouPorCancelamento(UUID produtoId, UUID vendaId) {
        return temMovimentoDaVenda(produtoId, vendaId, TipoMovimentoEstoque.ENTRADA);
    }

    private boolean temMovimentoDaVenda(UUID produtoId, UUID vendaId, TipoMovimentoEstoque tipo) {
        Objects.requireNonNull(produtoId, "id do produto nao pode ser nulo");
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        return produtos.existsByIdAndMovimentosVendaIdAndMovimentosTipo(produtoId, vendaId, tipo);
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

    /**
     * O saldo de cada produto pedido, ativo ou não, na mesma projeção do alerta. É a pergunta que
     * a Venda recebida sem rede faz depois da baixa, para saber se vendeu o que o estoque não
     * tinha. Um id que não existe nesta conta simplesmente não volta.
     */
    @Transactional(readOnly = true)
    public List<EstoqueDoProduto> saldosDe(Collection<UUID> ids) {
        Objects.requireNonNull(ids, "ids dos produtos nao podem ser nulos");
        return produtos.findAllById(ids).stream()
                .map(ProdutoEntity::paraDominio)
                .map(produto -> new EstoqueDoProduto(produto.getId(), produto.getNome(),
                        produto.getCodigo(), produto.getUnidade(), produto.getEstoqueAtual(),
                        produto.getEstoqueMinimo()))
                .toList();
    }

    /** A tela recebe uma projeção de saldo e mínimo, sem abrir a raiz do agregado ao estoque. */
    @Transactional(readOnly = true)
    public List<EstoqueDoProduto> listarEstoqueDosProdutos() {
        return produtos.findByAtivoTrueAndTipo(TipoProduto.PRODUTO).stream()
                .map(ProdutoEntity::paraDominio)
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
     * A resposta de {@link #consultarParaComprovante}, uma por produto pedido.
     *
     * <p>Record próprio pelo mesmo motivo de {@link ProdutoParaVenda}: atravessa a fronteira do
     * módulo, e o comprovante precisa do nome e da unidade, não da raiz do agregado.
     *
     * @param id      o mesmo id consultado, para quem chamou casar a resposta com a linha
     * @param nome    o nome de hoje, não o da época da venda
     * @param unidade nula quando o produto não tem unidade cadastrada
     */
    public record ProdutoParaComprovante(UUID id, String nome, String unidade) {
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
