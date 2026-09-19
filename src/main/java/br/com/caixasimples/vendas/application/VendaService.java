package br.com.caixasimples.vendas.application;

import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.ProdutoParaVenda;
import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.application.SessaoCaixaService.ResumoDeSessao;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da montagem da venda: iniciar a comanda, lançar e remover item (RF07) e aplicar
 * desconto sobre o total (RF08), com o preço unitário copiado do produto no ato (RF06).
 *
 * <p>Cada caso de uso é sempre a mesma sequência: carrega a linha, deixa a raiz do agregado
 * decidir, grava o que ela decidiu. Nenhuma regra de dinheiro mora aqui. É {@link Venda} que sabe
 * que desconto não passa do valor, que o total nunca fica negativo e que venda que não está ABERTA
 * não se monta.
 *
 * <p><strong>Duas regras moram neste arquivo, e as duas por dependerem de outro módulo.</strong>
 * A primeira é que venda só começa em sessão de caixa ABERTA desta conta, e quem sabe o estado da
 * sessão é o caixa. A segunda é que produto inativado não entra em venda nova, e quem sabe se o
 * produto está ativo é o cadastro. As duas são perguntas feitas à API pública do módulo dono;
 * nenhuma delas produz efeito colateral lá.
 *
 * <p><strong>O preço vem do cadastro, nunca de quem chama.</strong> {@link #adicionarItem} recebe
 * o id do produto e consulta o preço vigente na hora de lançar; um preço vindo do payload seria
 * uma porta para vender por qualquer valor. É a cópia que faz uma venda passada não mudar quando o
 * produto é reajustado.
 *
 * <p><strong>{@link #iniciar} não confere se o operador da venda é o operador da sessão.</strong>
 * O {@code @TenantId} já garante que a sessão é da própria conta (RNF05), e a autorização por
 * perfil ainda não existe no sistema. Até que exista, um operador pode vender no caixa do colega.
 * É custo aceito, o mesmo do fechamento de caixa, e está escrito aqui para não passar por
 * esquecimento.
 *
 * <p><strong>Não há cliente na venda ainda.</strong> Vincular cliente (RF03) é operação própria,
 * porque numa comanda ele costuma ser identificado depois do primeiro item, e chega junto de uma
 * consulta pública do cadastro que confirme que o cliente é desta conta.
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
    private final SessaoCaixaService caixas;

    VendaService(VendaRepository vendas, ProdutoService produtos, SessaoCaixaService caixas) {
        this.vendas = vendas;
        this.produtos = produtos;
        this.caixas = caixas;
    }

    /**
     * Abre uma comanda (RF07) numa sessão de caixa ABERTA desta conta.
     *
     * @return o id da venda criada, gerado na aplicação e nunca pelo banco (RNF01, RNF03)
     * @throws br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException se a
     *         sessão não existe nesta conta
     * @throws IllegalStateException se a sessão não está ABERTA
     */
    @Transactional
    public UUID iniciar(UUID sessaoCaixaId, UUID usuarioId) {
        Objects.requireNonNull(sessaoCaixaId, "id da sessao de caixa nao pode ser nulo");
        Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");

        ResumoDeSessao sessao = caixas.consultar(sessaoCaixaId);
        if (sessao.status() != StatusSessaoCaixa.ABERTA) {
            throw new IllegalStateException(
                    "sessao de caixa " + sessaoCaixaId + " esta " + sessao.status()
                            + " e nao aceita venda nova. Abra um caixa antes de vender.");
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

    private VendaEntity buscar(UUID vendaId) {
        Objects.requireNonNull(vendaId, "id da venda nao pode ser nulo");
        return vendas.findById(vendaId).orElseThrow(() -> new VendaNaoEncontradaException(vendaId));
    }
}
