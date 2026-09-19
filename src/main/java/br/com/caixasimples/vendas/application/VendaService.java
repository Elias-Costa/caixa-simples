package br.com.caixasimples.vendas.application;

import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.ProdutoParaVenda;
import br.com.caixasimples.pagamentos.application.PaymentService;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CaixaParaVenda;
import br.com.caixasimples.vendas.VendaConcluida;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da venda: iniciar a comanda, lançar e remover item (RF07), aplicar desconto sobre
 * o total (RF08), com o preço unitário copiado do produto no ato (RF06), registrar as parcelas do
 * pagamento, divididas entre formas (RF09), e concluir.
 *
 * <p>Cada caso de uso é sempre a mesma sequência: carrega a linha, deixa a raiz do agregado
 * decidir, grava o que ela decidiu. Nenhuma regra de dinheiro mora aqui. É {@link Venda} que sabe
 * que desconto não passa do valor, que o total nunca fica negativo, que parcela não passa do que
 * falta pagar, que a venda só conclui com os pagamentos confirmados iguais ao total, e que venda
 * que não está ABERTA não se monta nem se paga.
 *
 * <p><strong>Duas regras moram neste arquivo, e as duas por dependerem de outro módulo.</strong>
 * A primeira é que venda só começa, e só conclui, em sessão de caixa ABERTA desta conta, e quem
 * sabe o estado da sessão é o caixa. A segunda é que produto inativado não entra em venda nova, e
 * quem sabe se o produto está ativo é o cadastro. As duas são perguntas, e nenhuma produz efeito
 * colateral no módulo que responde. A do cadastro é chamada direta à API pública dele. A do caixa
 * passa por {@link CaixaParaVenda}, interface que <em>este</em> módulo declara e o caixa
 * implementa: o caixa já depende de vendas para ouvir o evento de venda concluída, e a verificação
 * de fronteiras recusa dois módulos dependendo um do outro.
 *
 * <p><strong>A terceira pergunta é ao módulo de pagamentos</strong>, e ela não é regra daqui: ao
 * registrar uma parcela, {@link #registrarPagamento} entrega o pedido ao {@code PaymentService},
 * que encontra a estratégia da forma pedida, aplica a regra dela (o troco do dinheiro, a recusa
 * de valor recebido no Pix e no cartão) e responde o que fica registrado. É pergunta, e não efeito
 * colateral, porque o serviço de pagamentos não abre transação nem grava nada; quem grava a
 * parcela é o agregado Venda, dono dela. O troco não é persistido: volta a quem chamou, para a
 * tela mostrar.
 *
 * <p><strong>O preço vem do cadastro, nunca de quem chama.</strong> {@link #adicionarItem} recebe
 * o id do produto e consulta o preço vigente na hora de lançar; um preço vindo do payload seria
 * uma porta para vender por qualquer valor. É a cópia que faz uma venda passada não mudar quando o
 * produto é reajustado.
 *
 * <p><strong>{@link #concluir} é um passo à parte de registrar a última parcela</strong>, e é o
 * único ponto de onde sai algo deste módulo: depois de gravar a venda CONCLUIDA, publica
 * {@link VendaConcluida}. Lançar o dinheiro no caixa e dar baixa no estoque são efeitos de quem
 * ouve o evento; este serviço não chama nenhum dos dois. O evento carrega a conta, lida do
 * contexto autenticado no ato, porque o listener roda em outra thread e uma reentrega pode vir do
 * registro de publicação horas depois; sem a conta dentro dele, o caixa não acharia a sessão.
 *
 * <p><strong>Concluir exige o caixa em que a venda nasceu ainda ABERTO</strong>, pela mesma
 * pergunta que {@link #iniciar} faz: é nesse caixa que o dinheiro entra, e uma sessão FECHADA já
 * conferiu a gaveta e não aceita movimento. Uma venda cuja sessão fechou antes da conclusão fica
 * ABERTA até ser cancelada, quando o cancelamento existir. Registrar parcela não faz essa
 * pergunta, de propósito: a parcela não mexe na gaveta; a conclusão mexe.
 *
 * <p><strong>{@link #iniciar} não confere se o operador da venda é o operador da sessão.</strong>
 * O {@code @TenantId} já garante que a sessão é da própria conta (RNF05), e a autorização por
 * perfil ainda não existe no sistema. Até que exista, um operador pode vender no caixa do colega.
 * É custo aceito, o mesmo do fechamento de caixa, e está escrito aqui para não passar por
 * esquecimento.
 *
 * <p><strong>Não há como desfazer uma parcela lançada.</strong> Uma parcela com o valor errado se
 * corrige cancelando a venda, quando o cancelamento existir. <strong>Não há cliente na venda
 * ainda.</strong> Vincular cliente (RF03) é operação própria, porque numa comanda ele costuma ser
 * identificado depois do primeiro item, e chega junto de uma consulta pública do cadastro que
 * confirme que o cliente é desta conta.
 *
 * <p>O {@code usuarioId} chega como parâmetro porque ainda não há camada {@code web/} neste
 * módulo. Quando ela nascer, o valor virá do claim do token autenticado e nunca do payload (RNF05),
 * que é o mesmo que já vale para o {@code contaId}, o qual não aparece em assinatura nenhuma deste
 * arquivo.
 */
@Service
public class VendaService {

    private final VendaRepository vendas;
    private final ProdutoService produtos;
    private final CaixaParaVenda caixa;
    private final PaymentService pagamentos;
    private final ApplicationEventPublisher eventos;

    VendaService(VendaRepository vendas, ProdutoService produtos, CaixaParaVenda caixa,
            PaymentService pagamentos, ApplicationEventPublisher eventos) {
        this.vendas = vendas;
        this.produtos = produtos;
        this.caixa = caixa;
        this.pagamentos = pagamentos;
        this.eventos = eventos;
    }

    /**
     * Abre uma comanda (RF07) numa sessão de caixa ABERTA desta conta.
     *
     * @return o id da venda criada, gerado na aplicação e nunca pelo banco (RNF01, RNF03)
     * @throws RuntimeException      se a sessão não existe nesta conta; a exceção é a do módulo do
     *                               caixa e atravessa {@link CaixaParaVenda} sem tradução
     * @throws IllegalStateException se a sessão não está ABERTA
     */
    @Transactional
    public UUID iniciar(UUID sessaoCaixaId, UUID usuarioId) {
        Objects.requireNonNull(sessaoCaixaId, "id da sessao de caixa nao pode ser nulo");
        Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");

        if (!caixa.estaAberto(sessaoCaixaId)) {
            throw new IllegalStateException(
                    "sessao de caixa " + sessaoCaixaId + " nao esta ABERTA e nao aceita venda"
                            + " nova. Abra um caixa antes de vender.");
        }

        Venda venda = new Venda(sessaoCaixaId, usuarioId);
        return vendas.save(VendaEntity.de(venda)).getId();
    }

    /**
     * Lança um item na comanda (RF07), copiando o preço vigente do produto (RF06).
     *
     * @param desconto desconto deste item (RF08); {@code Money.ZERO} quando não há
     * @return o id do item, para que quem lançou consiga removê-lo depois
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException se o
     *         produto não existe nesta conta
     * @throws IllegalStateException    se o produto está inativo, ou se a venda não está ABERTA
     * @throws IllegalArgumentException se quantidade ou desconto violam as regras da raiz
     */
    @Transactional
    public UUID adicionarItem(UUID vendaId, UUID produtoId, BigDecimal quantidade, Money desconto) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();

        ProdutoParaVenda produto = produtos.consultarParaVenda(produtoId);
        if (!produto.ativo()) {
            // O histórico continua apontando para o produto inativado (RF05); ele só não entra em
            // venda nova. A regra é da venda, e por isso é aqui que ela mora, não no cadastro.
            throw new IllegalStateException(
                    "produto " + produtoId + " esta inativo e nao entra em venda nova");
        }

        UUID itemId = venda.adicionarItem(produtoId, quantidade, produto.preco(), desconto);

        linha.atualizarCom(venda);
        vendas.save(linha);
        return itemId;
    }

    /**
     * Tira um item da comanda. Corrigir um item é isto seguido de {@link #adicionarItem}.
     *
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws IllegalArgumentException    se o item não está na venda, ou se removê-lo deixaria o
     *                                     desconto da venda maior que a soma restante
     * @throws IllegalStateException       se a venda não está ABERTA
     */
    @Transactional
    public void removerItem(UUID vendaId, UUID itemId) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();

        venda.removerItem(itemId);

        linha.atualizarCom(venda);
        vendas.save(linha);
    }

    /**
     * Desconto sobre o total da venda (RF08). Substitui o anterior; zero o remove.
     *
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws IllegalArgumentException    se o desconto é negativo ou passa da soma dos itens
     * @throws IllegalStateException       se a venda não está ABERTA
     */
    @Transactional
    public void aplicarDesconto(UUID vendaId, Money desconto) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();

        venda.aplicarDesconto(desconto);

        linha.atualizarCom(venda);
        vendas.save(linha);
    }

    /**
     * Lança uma parcela do pagamento (RF09), na forma que a solicitação indica, e devolve o troco.
     *
     * <p>A venda é carregada <em>antes</em> de a estratégia rodar, de propósito: uma venda de outra
     * conta falha como venda inexistente antes de qualquer regra de pagamento ser aplicada, e uma
     * parcela recusada pela raiz não deixa rastro, já que nada foi gravado.
     *
     * @param solicitacao forma, valor da parcela e, só em dinheiro, o valor recebido (RF10)
     * @return o troco a devolver; zero nas formas que não devolvem dinheiro. Não é persistido
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws br.com.caixasimples.pagamentos.application.FormaDePagamentoNaoSuportadaException se
     *         nenhuma estratégia atende a forma pedida
     * @throws IllegalArgumentException se a solicitação não serve para a forma, como dinheiro
     *                                  recebido a menos, ou se a parcela passa do que falta pagar
     * @throws IllegalStateException    se a venda não está ABERTA
     */
    @Transactional
    public Money registrarPagamento(UUID vendaId, SolicitacaoPagamento solicitacao) {
        Objects.requireNonNull(solicitacao, "solicitacao de pagamento nao pode ser nula");
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();

        ResultadoPagamento resultado = pagamentos.pagar(solicitacao);
        venda.registrarPagamento(resultado.forma(), resultado.valor(), resultado.status());

        linha.atualizarCom(venda);
        vendas.save(linha);
        return resultado.troco();
    }

    /**
     * Fecha a venda (RF09): a raiz confere que os pagamentos confirmados cobrem o total e vira o
     * status para CONCLUIDA; depois de gravada, o evento {@link VendaConcluida} é publicado.
     *
     * <p>O evento sai na mesma transação que grava a venda: o registro de publicação anota a
     * publicação junto, e os listeners só rodam depois do commit. Se a transação não completar,
     * nem a venda nem o evento existem.
     *
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws IllegalStateException       se a sessão de caixa da venda não está ABERTA, se a
     *                                     venda não está ABERTA, não tem item, ou os pagamentos
     *                                     confirmados não cobrem exatamente o total
     */
    @Transactional
    public void concluir(UUID vendaId) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();

        if (!caixa.estaAberto(venda.getSessaoCaixaId())) {
            throw new IllegalStateException(
                    "sessao de caixa " + venda.getSessaoCaixaId() + " nao esta ABERTA e nao"
                            + " recebe o dinheiro da venda " + vendaId + ". A venda so conclui"
                            + " com o caixa em que nasceu ainda aberto.");
        }

        venda.concluir();

        linha.atualizarCom(venda);
        vendas.save(linha);

        eventos.publishEvent(eventoDe(venda));
    }

    /**
     * O fato inteiro, no vocabulário que os outros módulos enxergam. A conta vem do contexto
     * autenticado, nunca de parâmetro (RNF05).
     */
    private static VendaConcluida eventoDe(Venda venda) {
        ContaId contaId = TenantContext.exigirAtual();

        List<VendaConcluida.Item> itens = venda.getItens().stream()
                .map(item -> new VendaConcluida.Item(item.produtoId(), item.quantidade()))
                .toList();
        List<VendaConcluida.Parcela> parcelas = venda.getPagamentos().stream()
                .map(parcela -> new VendaConcluida.Parcela(parcela.forma(), parcela.valor(),
                        parcela.status()))
                .toList();

        return new VendaConcluida(contaId, venda.getId(), venda.getSessaoCaixaId(),
                venda.getUsuarioId(), itens, parcelas);
    }

    private VendaEntity buscar(UUID vendaId) {
        Objects.requireNonNull(vendaId, "id da venda nao pode ser nulo");
        return vendas.findById(vendaId).orElseThrow(() -> new VendaNaoEncontradaException(vendaId));
    }
}
