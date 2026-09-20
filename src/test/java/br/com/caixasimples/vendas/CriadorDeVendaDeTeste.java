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
        return gravar(contaId, deUmItem(sessaoCaixaId, usuarioId, produtoId, valor,
                StatusVenda.CONCLUIDA, concluidoEm));
    }

    /**
     * Uma venda CANCELADA que antes tinha concluído no instante escolhido: o instante da conclusão
     * fica gravado, como no cancelamento de verdade, e o status é o final.
     *
     * <p>É o caso que o relatório precisa excluir, e que só se distingue da concluída pelo status.
     */
    public UUID criarCanceladaQueConcluiuEm(ContaId contaId, UUID sessaoCaixaId, UUID usuarioId,
            UUID produtoId, Money valor, Instant concluidoEm) {
        return gravar(contaId, deUmItem(sessaoCaixaId, usuarioId, produtoId, valor,
                StatusVenda.CANCELADA, concluidoEm));
    }

    private UUID gravar(ContaId contaId, Venda venda) {
        return TenantContext.executarComo(contaId, () ->
                vendas.save(VendaEntity.de(venda)).getId());
    }

    /**
     * Um item de quantidade um ao preço do total e uma parcela em dinheiro do mesmo valor: o
     * estado mais simples que passa pelas duas invariantes da venda concluída. A comanda abre no
     * mesmo instante em que conclui, para o teste não ter dois instantes para pensar.
     */
    private static Venda deUmItem(UUID sessaoCaixaId, UUID usuarioId, UUID produtoId, Money valor,
            StatusVenda status, Instant concluidoEm) {
        ItemVenda item = new ItemVenda(UUID.randomUUID(), produtoId, BigDecimal.ONE, valor,
                Money.ZERO, concluidoEm);
        Pagamento parcela = new Pagamento(UUID.randomUUID(), FormaPagamento.DINHEIRO, valor,
                StatusPagamento.CONFIRMADO, Money.ZERO, concluidoEm);

        return Venda.reconstituir(UUID.randomUUID(), sessaoCaixaId, usuarioId, null, status,
                valor, Money.ZERO, concluidoEm, concluidoEm, List.of(item), List.of(parcela));
    }
}
