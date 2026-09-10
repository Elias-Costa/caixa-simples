package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Uma venda do balcão. Raiz do agregado Venda, com {@link ItemVenda} e {@link Pagamento} como
 * membros.
 *
 * <p><strong>As invariantes que esta classe existe para guardar:</strong>
 * {@code valorTotal = soma dos itens menos o desconto da venda}, o tempo todo; e, numa venda
 * CONCLUIDA, a soma dos pagamentos CONFIRMADO é igual ao {@code valorTotal} (RF09). É por isso
 * que item e pagamento não têm repositório: uma linha alterada por fora deixaria o total mentindo.
 *
 * <p><strong>Ainda não há caminho de escrita.</strong> Esta classe remonta o que está gravado e
 * expõe o estado; a montagem da comanda, com a regra do total, e a conclusão, com a regra dos
 * pagamentos, chegam com os casos de uso. Até lá, ninguém cria uma venda nova pelo domínio, e
 * isso é proposital: um construtor público sem as regras seria uma porta lateral por onde se
 * gravaria um total que não bate com os itens.
 *
 * <p><strong>Não importa framework</strong>, nem {@code jakarta.persistence} nem
 * {@code org.springframework}. O mapeamento vive em {@code vendas.internal.VendaEntity}.
 *
 * <p><strong>Não carrega {@code contaId}</strong>, pelo mesmo motivo dos outros agregados: sem o
 * campo, não existe assinatura por onde um chamador pudesse informar a conta, que é exatamente o
 * que o isolamento entre contas proíbe (RNF05). O tenant é preenchido pelo Hibernate na entidade.
 *
 * <p>{@code sessaoCaixaId}, {@code usuarioId}, {@code clienteId} e o {@code produtoId} de cada
 * item são referências entre agregados: sempre por id, nunca objeto navegável. O cliente é
 * opcional (RF03), e por isso é o único que aceita nulo.
 */
public class Venda {

    private final UUID id;
    private final UUID sessaoCaixaId;
    private final UUID usuarioId;
    private final UUID clienteId;
    private final Instant criadoEm;

    /**
     * Os três mudam com a montagem e com a conclusão da venda, que ainda não existem em código.
     * Por isso não são {@code final}, mesmo sem nada que os altere hoje.
     */
    private StatusVenda status;
    private Money valorTotal;
    private Money valorDesconto;

    private final List<ItemVenda> itens;
    private final List<Pagamento> pagamentos;

    private Venda(UUID id, UUID sessaoCaixaId, UUID usuarioId, UUID clienteId, StatusVenda status,
            Money valorTotal, Money valorDesconto, Instant criadoEm, List<ItemVenda> itens,
            List<Pagamento> pagamentos) {
        this.id = id;
        this.sessaoCaixaId = sessaoCaixaId;
        this.usuarioId = usuarioId;
        this.clienteId = clienteId;
        this.status = status;
        this.valorTotal = valorTotal;
        this.valorDesconto = valorDesconto;
        this.criadoEm = criadoEm;
        this.itens = new ArrayList<>(itens);
        this.pagamentos = new ArrayList<>(pagamentos);
    }

    /**
     * Remonta uma venda que já existe no banco, preservando identidade e estado.
     *
     * <p>Existe para {@code VendaEntity}. Pelo mesmo motivo de {@code SessaoCaixa.reconstituir},
     * não recalcula o total a partir dos itens: o que está gravado já passou pelos CHECK da
     * migration, e recalcular aqui mascararia uma linha divergente em vez de deixar o defeito
     * aparecer.
     *
     * <p>As listas recebidas são copiadas, para que quem chamou não consiga alterar o agregado
     * por fora depois de montá-lo.
     *
     * @param clienteId nulo quando a venda não tem cliente identificado (RF03)
     */
    public static Venda reconstituir(UUID id, UUID sessaoCaixaId, UUID usuarioId, UUID clienteId,
            StatusVenda status, Money valorTotal, Money valorDesconto, Instant criadoEm,
            List<ItemVenda> itens, List<Pagamento> pagamentos) {
        return new Venda(id, sessaoCaixaId, usuarioId, clienteId, status, valorTotal,
                valorDesconto, criadoEm, itens, pagamentos);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessaoCaixaId() {
        return sessaoCaixaId;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    /** Nulo quando a venda não tem cliente identificado (RF03). */
    public UUID getClienteId() {
        return clienteId;
    }

    public StatusVenda getStatus() {
        return status;
    }

    public Money getValorTotal() {
        return valorTotal;
    }

    /** Desconto sobre o total da venda (RF08), além do desconto de cada item. Zero quando não há. */
    public Money getValorDesconto() {
        return valorDesconto;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    /** Cópia imutável: item só entra pela raiz, nunca por quem leu a lista. */
    public List<ItemVenda> getItens() {
        return Collections.unmodifiableList(itens);
    }

    /** Cópia imutável: pagamento só entra pela raiz, nunca por quem leu a lista. */
    public List<Pagamento> getPagamentos() {
        return Collections.unmodifiableList(pagamentos);
    }
}
