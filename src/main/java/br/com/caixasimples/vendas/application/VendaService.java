package br.com.caixasimples.vendas.application;

import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.ProdutoParaComprovante;
import br.com.caixasimples.cadastro.application.ProdutoService.ProdutoParaVenda;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.application.PaymentService;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.vendas.CaixaParaVenda;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.VendaCancelada;
import br.com.caixasimples.vendas.VendaConcluida;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da venda: iniciar a comanda, lançar e remover item (RF07), aplicar desconto sobre
 * o total (RF08), com o preço unitário copiado do produto no ato (RF06), registrar as parcelas do
 * pagamento, divididas entre formas (RF09), concluir, cancelar (RF12) e montar o comprovante
 * não-fiscal de uma venda concluída (RF11).
 *
 * <p>Cada caso de uso é sempre a mesma sequência: carrega a linha, deixa a raiz do agregado
 * decidir, grava o que ela decidiu. Nenhuma regra de dinheiro mora aqui. É {@link Venda} que sabe
 * que desconto não passa do valor, que o total nunca fica negativo, que parcela não passa do que
 * falta pagar, que a venda só conclui com os pagamentos confirmados iguais ao total, que venda
 * que não está ABERTA não se monta nem se paga, e que CANCELADA é final.
 *
 * <p><strong>Duas regras moram neste arquivo, e as duas por dependerem de outro módulo.</strong>
 * A primeira é que venda só começa, só conclui e, quando CONCLUIDA, só cancela em sessão de caixa
 * ABERTA desta conta, e quem sabe o estado da sessão é o caixa. A segunda é que produto inativado
 * não entra em venda nova, e quem sabe se o produto está ativo é o cadastro. As duas são
 * perguntas, e nenhuma produz efeito colateral no módulo que responde. A do cadastro é chamada
 * direta à API pública dele, e o comprovante faz ao mesmo cadastro uma segunda pergunta, o nome
 * de cada produto vendido. A do caixa passa por {@link CaixaParaVenda}, interface que
 * <em>este</em> módulo declara e o caixa implementa: o caixa já depende de vendas para ouvir os
 * eventos de venda concluída e cancelada, e a verificação de fronteiras recusa dois módulos
 * dependendo um do outro.
 *
 * <p><strong>A terceira pergunta é ao módulo de pagamentos</strong>, e ela não é regra daqui: ao
 * registrar uma parcela, {@link #registrarPagamento} entrega o pedido ao {@code PaymentService},
 * que encontra a estratégia da forma pedida, aplica a regra dela (o troco do dinheiro, a recusa
 * de valor recebido no Pix e no cartão) e responde o que fica registrado. É pergunta, e não efeito
 * colateral, porque o serviço de pagamentos não abre transação nem grava nada; quem grava a
 * parcela é o agregado Venda, dono dela, e grava o troco junto, para o comprovante sair igual
 * numa reimpressão. O troco também volta a quem chamou, para a tela mostrar no ato.
 *
 * <p><strong>O preço vem do cadastro, nunca de quem chama.</strong> {@link #adicionarItem} recebe
 * o id do produto e consulta o preço vigente na hora de lançar; um preço vindo do payload seria
 * uma porta para vender por qualquer valor. É a cópia que faz uma venda passada não mudar quando o
 * produto é reajustado.
 *
 * <p><strong>{@link #concluir} é um passo à parte de registrar a última parcela</strong>, e é um
 * dos dois pontos de onde sai algo deste módulo: depois de gravar a venda CONCLUIDA, publica
 * {@link VendaConcluida}. O outro é {@link #cancelar}, que publica {@link VendaCancelada} depois
 * de gravar a venda CANCELADA, e só quando ela estava CONCLUIDA: uma comanda ABERTA abandonada
 * nunca produziu efeito fora do módulo, e não há o que desfazer. Lançar e devolver o dinheiro no
 * caixa, dar baixa e estornar o estoque são efeitos de quem ouve os eventos; este serviço não
 * chama nenhum dos dois módulos. Os eventos carregam a conta, lida do contexto autenticado no
 * ato, porque o listener roda em outra thread e uma reentrega pode vir do registro de publicação
 * horas depois; sem a conta dentro dele, o caixa não acharia a sessão.
 *
 * <p><strong>Concluir exige o caixa em que a venda nasceu ainda ABERTO</strong>, pela mesma
 * pergunta que {@link #iniciar} faz: é nesse caixa que o dinheiro entra, e uma sessão FECHADA já
 * conferiu a gaveta e não aceita movimento. Uma venda cuja sessão fechou antes da conclusão fica
 * ABERTA até ser cancelada. Registrar parcela não faz essa pergunta, de propósito: a parcela não
 * mexe na gaveta; a conclusão mexe.
 *
 * <p><strong>Cancelar uma venda CONCLUIDA exige o mesmo caixa ainda ABERTO</strong>, e pelo mesmo
 * motivo, lido ao contrário: o estorno é dinheiro saindo da gaveta, e a gaveta de uma sessão
 * FECHADA já foi conferida; um estorno nela reescreveria a diferença apurada. Venda de um
 * expediente encerrado não cancela pelo sistema; devolução depois do fechamento não está em
 * requisito nenhum. Cancelar uma venda ABERTA não faz a pergunta: ela nunca tocou a gaveta, e é
 * justamente a saída para a comanda que ficou presa quando o caixa fechou antes da conclusão.
 * Uma venda CONCLUIDA paga só em Pix ou cartão também exige o caixa aberto, mesmo sem ter
 * dinheiro na gaveta a devolver: a regra é uma só, e vale por ser uma só.
 *
 * <p><strong>Quem vende vem do contexto, nunca de parâmetro.</strong> O operador da venda é o
 * usuário autenticado, lido de {@link UsuarioContext} em {@link #iniciar}, e o {@code contaId}
 * não aparece em assinatura nenhuma deste arquivo (RNF05). A partir daí vale a regra do próprio
 * caixa: o perfil Operador só toca as próprias vendas, e cada caso de uso pergunta isso por
 * {@code exigirDonoOuAdmin} logo depois de carregar a venda; o administrador toca qualquer venda
 * da conta. Que a sessão em que a venda nasce seja a do operador é regra do caixa, e vale na
 * pergunta que este serviço lhe faz: o operador que tenta iniciar, concluir ou cancelar uma venda
 * no caixa do colega é recusado pelo caixa ao responder se a sessão está aberta.
 *
 * <p><strong>Não há como desfazer uma parcela lançada.</strong> Uma parcela com o valor errado se
 * corrige cancelando a venda e abrindo outra. <strong>O cancelamento não registra motivo, nem
 * quem cancelou, nem quando:</strong> nenhum requisito pede; quem pode cancelar é quem pode
 * tocar a venda, o operador dela ou o administrador. <strong>Não há cliente na venda
 * ainda.</strong> Vincular cliente (RF03) é operação própria, porque numa comanda ele costuma ser
 * identificado depois do primeiro item, e chega junto de uma consulta pública do cadastro que
 * confirme que o cliente é desta conta.
 *
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
     * @throws br.com.caixasimples.shared.UsuarioNaoResolvidoException se não há usuário no contexto
     * @throws RuntimeException      se a sessão não existe nesta conta, ou se é de outro operador
     *                               e quem chama não é ADMIN; a exceção é a do módulo do caixa e
     *                               atravessa {@link CaixaParaVenda} sem tradução
     * @throws IllegalStateException se a sessão não está ABERTA
     */
    @Transactional
    public UUID iniciar(UUID sessaoCaixaId) {
        Objects.requireNonNull(sessaoCaixaId, "id da sessao de caixa nao pode ser nulo");
        UUID usuarioId = UsuarioContext.exigirAtual().usuarioId();

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
     * @throws br.com.caixasimples.shared.AcessoNegadoException se a venda é de outro operador e
     *                                     quem chama não é ADMIN
     * @throws br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException se o
     *         produto não existe nesta conta
     * @throws IllegalStateException    se o produto está inativo, ou se a venda não está ABERTA
     * @throws IllegalArgumentException se quantidade ou desconto violam as regras da raiz
     */
    @Transactional
    public UUID adicionarItem(UUID vendaId, UUID produtoId, BigDecimal quantidade, Money desconto) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();
        UsuarioContext.exigirDonoOuAdmin(venda.getUsuarioId());

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
     * @throws br.com.caixasimples.shared.AcessoNegadoException se a venda é de outro operador e
     *                                     quem chama não é ADMIN
     * @throws IllegalArgumentException    se o item não está na venda, ou se removê-lo deixaria o
     *                                     desconto da venda maior que a soma restante
     * @throws IllegalStateException       se a venda não está ABERTA
     */
    @Transactional
    public void removerItem(UUID vendaId, UUID itemId) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();
        UsuarioContext.exigirDonoOuAdmin(venda.getUsuarioId());

        venda.removerItem(itemId);

        linha.atualizarCom(venda);
        vendas.save(linha);
    }

    /**
     * Desconto sobre o total da venda (RF08). Substitui o anterior; zero o remove.
     *
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws br.com.caixasimples.shared.AcessoNegadoException se a venda é de outro operador e
     *                                     quem chama não é ADMIN
     * @throws IllegalArgumentException    se o desconto é negativo ou passa da soma dos itens
     * @throws IllegalStateException       se a venda não está ABERTA
     */
    @Transactional
    public void aplicarDesconto(UUID vendaId, Money desconto) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();
        UsuarioContext.exigirDonoOuAdmin(venda.getUsuarioId());

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
     * @return o troco a devolver; zero nas formas que não devolvem dinheiro. Fica gravado na
     *         parcela e é devolvido aqui também, para a tela mostrar no ato
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws br.com.caixasimples.shared.AcessoNegadoException se a venda é de outro operador e
     *                                     quem chama não é ADMIN
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
        UsuarioContext.exigirDonoOuAdmin(venda.getUsuarioId());

        ResultadoPagamento resultado = pagamentos.pagar(solicitacao);
        venda.registrarPagamento(resultado.forma(), resultado.valor(), resultado.status(),
                resultado.troco());

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
     * @throws br.com.caixasimples.shared.AcessoNegadoException se a venda é de outro operador e
     *                                     quem chama não é ADMIN
     * @throws IllegalStateException       se a sessão de caixa da venda não está ABERTA, se a
     *                                     venda não está ABERTA, não tem item, ou os pagamentos
     *                                     confirmados não cobrem exatamente o total
     */
    @Transactional
    public void concluir(UUID vendaId) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();
        UsuarioContext.exigirDonoOuAdmin(venda.getUsuarioId());

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
     * Desfaz a venda (RF12): a raiz vira o status para CANCELADA e, se a venda estava CONCLUIDA,
     * o evento {@link VendaCancelada} é publicado depois de gravada, na mesma transação, para que
     * o caixa devolva o dinheiro e o estoque devolva os itens.
     *
     * <p>A pergunta ao caixa vem antes de tocar no agregado, como em {@link #concluir}, e só é
     * feita quando há gaveta envolvida, isto é, quando a venda estava CONCLUIDA. O status de antes
     * é lido antes de chamar a raiz, porque depois dela toda venda cancelada é só CANCELADA, e é
     * ele que decide se há fato a anunciar.
     *
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws br.com.caixasimples.shared.AcessoNegadoException se a venda é de outro operador e
     *                                     quem chama não é ADMIN
     * @throws IllegalStateException       se a venda está CONCLUIDA e a sessão de caixa em que
     *                                     nasceu não está mais ABERTA, ou se a venda já está
     *                                     CANCELADA
     */
    @Transactional
    public void cancelar(UUID vendaId) {
        VendaEntity linha = buscar(vendaId);
        Venda venda = linha.paraDominio();
        UsuarioContext.exigirDonoOuAdmin(venda.getUsuarioId());

        boolean estavaConcluida = venda.getStatus() == StatusVenda.CONCLUIDA;
        if (estavaConcluida && !caixa.estaAberto(venda.getSessaoCaixaId())) {
            throw new IllegalStateException(
                    "sessao de caixa " + venda.getSessaoCaixaId() + " nao esta ABERTA e nao"
                            + " devolve o dinheiro da venda " + vendaId + ". Uma venda concluida"
                            + " so cancela com o caixa em que nasceu ainda aberto.");
        }

        venda.cancelar();

        linha.atualizarCom(venda);
        vendas.save(linha);

        if (estavaConcluida) {
            eventos.publishEvent(eventoDeCancelamento(venda));
        }
    }

    /**
     * Monta o comprovante não-fiscal (RF11) de uma venda CONCLUIDA: itens com o nome de hoje,
     * descontos, parcelas confirmadas e troco. É dado; quem imprime e compartilha é a tela.
     *
     * <p>Só venda CONCLUIDA tem comprovante. Uma comanda ABERTA ainda muda, e uma venda CANCELADA
     * não vale: comprovante é prova de venda feita. Nenhum requisito pede comprovante de
     * cancelamento.
     *
     * <p>O nome e a unidade de cada produto são perguntados ao cadastro na hora, numa chamada só
     * para todas as linhas, porque o item guarda o preço copiado, mas não o nome. Um produto
     * renomeado depois da venda sai com o nome novo; um produto inativado continua saindo, porque
     * o histórico aponta para ele. Uma venda de outra conta falha como inexistente antes de
     * qualquer pergunta ao cadastro.
     *
     * @throws VendaNaoEncontradaException se a venda não existe nesta conta
     * @throws br.com.caixasimples.shared.AcessoNegadoException se a venda é de outro operador e
     *                                     quem chama não é ADMIN
     * @throws IllegalStateException       se a venda não está CONCLUIDA
     */
    @Transactional(readOnly = true)
    public Comprovante comprovante(UUID vendaId) {
        Venda venda = buscar(vendaId).paraDominio();
        UsuarioContext.exigirDonoOuAdmin(venda.getUsuarioId());

        if (venda.getStatus() != StatusVenda.CONCLUIDA) {
            throw new IllegalStateException(
                    "venda " + vendaId + " esta " + venda.getStatus() + " e nao tem comprovante."
                            + " So venda CONCLUIDA tem comprovante.");
        }

        Set<UUID> produtoIds = venda.getItens().stream()
                .map(ItemVenda::produtoId)
                .collect(Collectors.toSet());
        Map<UUID, ProdutoParaComprovante> produtosPorId = produtos
                .consultarParaComprovante(produtoIds).stream()
                .collect(Collectors.toMap(ProdutoParaComprovante::id, produto -> produto));

        List<Comprovante.Linha> linhas = venda.getItens().stream()
                .map(item -> {
                    ProdutoParaComprovante produto = produtosPorId.get(item.produtoId());
                    return new Comprovante.Linha(item.produtoId(), produto.nome(),
                            produto.unidade(), item.quantidade(), item.precoUnitario(),
                            item.valorBruto(), item.desconto(), item.subtotal());
                })
                .toList();
        Money somaDosItens = linhas.stream()
                .map(Comprovante.Linha::subtotal)
                .reduce(Money.ZERO, Money::somar);

        List<Comprovante.Parcela> parcelas = venda.getPagamentos().stream()
                .filter(parcela -> parcela.status() == StatusPagamento.CONFIRMADO)
                .map(parcela -> new Comprovante.Parcela(parcela.forma(), parcela.valor(),
                        parcela.troco()))
                .toList();
        Money troco = parcelas.stream()
                .map(Comprovante.Parcela::troco)
                .reduce(Money.ZERO, Money::somar);

        return new Comprovante(venda.getId(), venda.getUsuarioId(), venda.getConcluidoEm(),
                linhas, somaDosItens, venda.getValorDesconto(), venda.getValorTotal(), parcelas,
                troco);
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

    /**
     * O mesmo fato de {@link #eventoDe}, no evento oposto: o que a venda tinha vendido e como
     * tinha sido paga, para que cada ouvinte desfaça exatamente o que fez.
     */
    private static VendaCancelada eventoDeCancelamento(Venda venda) {
        ContaId contaId = TenantContext.exigirAtual();

        List<VendaCancelada.Item> itens = venda.getItens().stream()
                .map(item -> new VendaCancelada.Item(item.produtoId(), item.quantidade()))
                .toList();
        List<VendaCancelada.Parcela> parcelas = venda.getPagamentos().stream()
                .map(parcela -> new VendaCancelada.Parcela(parcela.forma(), parcela.valor(),
                        parcela.status()))
                .toList();

        return new VendaCancelada(contaId, venda.getId(), venda.getSessaoCaixaId(),
                venda.getUsuarioId(), itens, parcelas);
    }

    private VendaEntity buscar(UUID vendaId) {
        Objects.requireNonNull(vendaId, "id da venda nao pode ser nulo");
        return vendas.findById(vendaId).orElseThrow(() -> new VendaNaoEncontradaException(vendaId));
    }
}
