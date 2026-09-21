package br.com.caixasimples.vendas;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Grava uma venda mínima para teste de outro módulo que precise apontar para uma venda real.
 *
 * <p>Existe porque, desde a V7, {@code movimento_caixa.venda_id} é chave estrangeira: um teste do
 * caixa não pode mais registrar uma venda com um id inventado. A fixture esconde de quem só
 * precisa de um id como se monta uma venda. É o mesmo papel de {@code CriadorDeContaDeTeste} para
 * conta e usuário.
 *
 * <p><strong>Grava pelo repositório, e não por {@code VendaService.iniciar}, de propósito.</strong>
 * O caso de uso exige sessão de caixa ABERTA, e um teste do caixa pode querer uma venda apontando
 * para uma sessão em qualquer estado; a fixture não deve carregar regra que o chamador não pediu.
 */
public class CriadorDeVendaDeTeste {

    private final VendaRepository vendas;

    public CriadorDeVendaDeTeste(VendaRepository vendas) {
        this.vendas = vendas;
    }

    /**
     * Uma venda ABERTA, sem item e sem pagamento, na sessão de caixa e do operador informados.
     *
     * <p>Vazia de propósito: quem chama quer um alvo para uma referência, não uma venda com
     * conteúdo. Item exigiria um produto cadastrado, e a fixture passaria a depender de mais um
     * módulo sem que nenhum chamador precisasse disso.
     */
    public UUID criarAbertaEm(ContaId contaId, UUID sessaoCaixaId, UUID usuarioId) {
        Venda venda = new Venda(sessaoCaixaId, usuarioId);

        return TenantContext.executarComo(contaId, () ->
                vendas.save(VendaEntity.de(venda)).getId());
    }

    /**
     * Uma venda CONCLUIDA num instante escolhido, com um item só, de valor igual ao total, pago
     * em dinheiro sem troco.
     *
     * <p>Existe para o teste de relatório fixar <strong>quando</strong> a venda concluiu, o que o
     * caminho pelo caso de uso não permite: {@code Venda.concluir} grava o instante corrente. A
     * venda é montada por {@code Venda.reconstituir}, como o teste de isolamento da venda faz.
     *
     * <p>O produto vem de quem chama, cadastrado pelo caso de uso do cadastro, porque
     * {@code item_venda.produto_id} é chave estrangeira; a fixture continua sem depender daquele
     * módulo.
     */
    public UUID criarConcluidaEm(ContaId contaId, UUID sessaoCaixaId, UUID usuarioId,
            UUID produtoId, Money valor, Instant concluidoEm) {
        return criarConcluidaComItensEm(contaId, sessaoCaixaId, usuarioId,
                List.of(ItemDeTeste.unitario(produtoId, valor)), concluidoEm);
    }

    /**
     * Uma venda CANCELADA que antes tinha concluído no instante escolhido: o instante da conclusão
     * fica gravado, como no cancelamento de verdade, e o status é o final.
     *
     * <p>É o caso que o relatório precisa excluir, e que só se distingue da concluída pelo status.
     */
    public UUID criarCanceladaQueConcluiuEm(ContaId contaId, UUID sessaoCaixaId, UUID usuarioId,
            UUID produtoId, Money valor, Instant concluidoEm) {
        return criarCanceladaComItensQueConcluiuEm(contaId, sessaoCaixaId, usuarioId,
                List.of(ItemDeTeste.unitario(produtoId, valor)), concluidoEm);
    }

    /**
     * Uma venda CONCLUIDA num instante escolhido, com os itens informados, paga em dinheiro sem
     * troco pelo total que eles somam.
     *
     * <p>Existe para o ranking dos mais vendidos, que precisa de vendas com mais de um produto e
     * com quantidades, preços e descontos escolhidos pelo teste.
     */
    public UUID criarConcluidaComItensEm(ContaId contaId, UUID sessaoCaixaId, UUID usuarioId,
            List<ItemDeTeste> itens, Instant concluidoEm) {
        return gravar(contaId, montar(sessaoCaixaId, usuarioId, itens, StatusVenda.CONCLUIDA,
                concluidoEm));
    }

    /**
     * Uma venda CANCELADA que antes tinha concluído no instante escolhido, com os itens
     * informados. Os itens e a parcela ficam, como no cancelamento de verdade.
     */
    public UUID criarCanceladaComItensQueConcluiuEm(ContaId contaId, UUID sessaoCaixaId,
            UUID usuarioId, List<ItemDeTeste> itens, Instant concluidoEm) {
        return gravar(contaId, montar(sessaoCaixaId, usuarioId, itens, StatusVenda.CANCELADA,
                concluidoEm));
    }

    /**
     * Uma venda CONCLUIDA num instante escolhido, com os itens e <strong>as parcelas</strong>
     * informados: forma, valor e status de cada uma.
     *
     * <p>Existe para o faturamento por forma de pagamento, que precisa de venda dividida entre
     * formas e de parcela RECUSADO ao lado das CONFIRMADO. A invariante da venda continua valendo:
     * as parcelas CONFIRMADO têm de somar exatamente o total dos itens, senão a montagem estoura.
     */
    public UUID criarConcluidaComParcelasEm(ContaId contaId, UUID sessaoCaixaId, UUID usuarioId,
            List<ItemDeTeste> itens, List<ParcelaDeTeste> parcelas, Instant concluidoEm) {
        List<Pagamento> pagamentos = parcelas.stream()
                .map(parcela -> new Pagamento(UUID.randomUUID(), parcela.forma(), parcela.valor(),
                        parcela.status(), Money.ZERO, concluidoEm))
                .toList();
        return gravar(contaId, montar(sessaoCaixaId, usuarioId, itens, StatusVenda.CONCLUIDA,
                concluidoEm, pagamentos));
    }

    /**
     * Uma comanda ABERTA com os itens informados e nenhum pagamento: o que ainda não foi vendido,
     * e que nenhum relatório pode contar.
     */
    public UUID criarAbertaComItens(ContaId contaId, UUID sessaoCaixaId, UUID usuarioId,
            List<ItemDeTeste> itens) {
        return gravar(contaId, montar(sessaoCaixaId, usuarioId, itens, StatusVenda.ABERTA, null));
    }

    private UUID gravar(ContaId contaId, Venda venda) {
        return TenantContext.executarComo(contaId, () ->
                vendas.save(VendaEntity.de(venda)).getId());
    }

    /**
     * Monta a venda no estado pedido pelas duas invariantes da venda: o total é a soma dos
     * subtotais dos itens, e a venda que concluiu tem uma parcela em dinheiro desse total. A
     * comanda abre no mesmo instante em que conclui, para o teste não ter dois instantes para
     * pensar; a ABERTA, que não concluiu, abre agora.
     */
    private static Venda montar(UUID sessaoCaixaId, UUID usuarioId, List<ItemDeTeste> itensDeTeste,
            StatusVenda status, Instant concluidoEm) {
        Instant criadoEm = concluidoEm == null ? Instant.now() : concluidoEm;
        List<ItemVenda> itens = itensDe(itensDeTeste, criadoEm);
        Money total = totalDos(itens);

        List<Pagamento> parcelas = status == StatusVenda.ABERTA
                ? List.of()
                : List.of(new Pagamento(UUID.randomUUID(), FormaPagamento.DINHEIRO, total,
                        StatusPagamento.CONFIRMADO, Money.ZERO, criadoEm));

        return Venda.reconstituir(UUID.randomUUID(), sessaoCaixaId, usuarioId, null, status,
                total, Money.ZERO, criadoEm, concluidoEm, itens, parcelas);
    }

    /**
     * Monta a venda com as parcelas dadas, sem inventar nenhuma. É quem chama que responde pela
     * invariante da conclusão: {@code Venda.reconstituir} recusa CONCLUIDA cujas parcelas
     * CONFIRMADO não somem o total.
     */
    private static Venda montar(UUID sessaoCaixaId, UUID usuarioId, List<ItemDeTeste> itensDeTeste,
            StatusVenda status, Instant concluidoEm, List<Pagamento> parcelas) {
        Instant criadoEm = concluidoEm == null ? Instant.now() : concluidoEm;
        List<ItemVenda> itens = itensDe(itensDeTeste, criadoEm);

        return Venda.reconstituir(UUID.randomUUID(), sessaoCaixaId, usuarioId, null, status,
                totalDos(itens), Money.ZERO, criadoEm, concluidoEm, itens, parcelas);
    }

    private static List<ItemVenda> itensDe(List<ItemDeTeste> itensDeTeste, Instant criadoEm) {
        return itensDeTeste.stream()
                .map(item -> new ItemVenda(UUID.randomUUID(), item.produtoId(), item.quantidade(),
                        item.precoUnitario(), item.desconto(), criadoEm))
                .toList();
    }

    /** O total pela mesma conta do domínio: subtotal por item, arredondado, e depois a soma. */
    private static Money totalDos(List<ItemVenda> itens) {
        Money total = Money.ZERO;
        for (ItemVenda item : itens) {
            total = total.somar(item.subtotal());
        }
        return total;
    }

    /**
     * Um item como o teste o quer: produto, quantidade, preço e desconto, sem id nem instante,
     * que a fixture põe.
     *
     * @param produtoId     um produto cadastrado pelo caso de uso do cadastro
     * @param quantidade    positiva, com no máximo três casas
     * @param precoUnitario o preço copiado para o item, que não precisa ser o do cadastro
     * @param desconto      zero quando não há
     */
    public record ItemDeTeste(UUID produtoId, BigDecimal quantidade, Money precoUnitario,
            Money desconto) {

        /** Um item de quantidade um, ao preço informado e sem desconto. */
        public static ItemDeTeste unitario(UUID produtoId, Money precoUnitario) {
            return new ItemDeTeste(produtoId, BigDecimal.ONE, precoUnitario, Money.ZERO);
        }
    }

    /**
     * Uma parcela como o teste a quer: forma, valor e status, sem id, troco nem instante, que a
     * fixture põe. O troco é sempre zero, porque nenhum relatório o lê.
     *
     * @param forma  a forma de pagamento desta parcela
     * @param valor  o valor da parcela, não o total da venda
     * @param status CONFIRMADO conta para a conclusão; RECUSADO e PENDENTE ficam gravados sem contar
     */
    public record ParcelaDeTeste(FormaPagamento forma, Money valor, StatusPagamento status) {

        /** Uma parcela CONFIRMADO nesta forma e valor. */
        public static ParcelaDeTeste confirmada(FormaPagamento forma, Money valor) {
            return new ParcelaDeTeste(forma, valor, StatusPagamento.CONFIRMADO);
        }

        /** Uma parcela RECUSADO nesta forma e valor: gravada, mas dinheiro que nunca entrou. */
        public static ParcelaDeTeste recusada(FormaPagamento forma, Money valor) {
            return new ParcelaDeTeste(forma, valor, StatusPagamento.RECUSADO);
        }
    }
}
