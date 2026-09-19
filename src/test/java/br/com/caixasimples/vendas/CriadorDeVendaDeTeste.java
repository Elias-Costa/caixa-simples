package br.com.caixasimples.vendas;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
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
}
