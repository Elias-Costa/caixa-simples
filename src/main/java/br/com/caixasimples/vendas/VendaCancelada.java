package br.com.caixasimples.vendas;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de domínio: uma venda concluída foi cancelada (RF12). É o fato que este módulo anuncia
 * para que o caixa devolva o dinheiro que a venda tinha trazido e o estoque devolva os itens que
 * ela tinha levado, sem que a venda chame nenhum dos dois.
 *
 * <p><strong>Só o cancelamento de uma venda CONCLUIDA publica este evento.</strong> Uma venda
 * ABERTA cancelada nunca produziu efeito fora do módulo, então não há o que desfazer, e nada é
 * anunciado. Quem ouve pode contar com isso: o fato descrito aqui é o oposto exato de
 * {@link VendaConcluida}.
 *
 * <p>Tem a mesma forma que {@link VendaConcluida}, e com os próprios {@link Item} e
 * {@link Parcela}, pelos mesmos motivos: carrega o fato inteiro, e não só o que o primeiro ouvinte
 * precisa; e carrega a conta, lida do contexto autenticado no momento da publicação, para os
 * ouvintes conferirem que rodam nessa mesma conta. Os records aninhados não são compartilhados com
 * o outro evento de propósito: cada evento é um contrato, e mudar um não pode mudar o outro.
 *
 * <p><strong>É ouvido dentro da transação que cancelou a venda</strong>, como a conclusão: a venda
 * cancelada, o dinheiro fora da gaveta e os itens de volta ao estoque confirmam juntos ou não
 * confirmam.
 *
 * @param contaId       a conta a que a venda pertence
 * @param vendaId       a venda cancelada
 * @param sessaoCaixaId a sessão de caixa em que ela nasceu, de onde o ESTORNO sai; recebimentos
 *                      podem ter entrado em outras sessões
 * @param usuarioId     o operador que vendeu
 * @param itens         o que tinha sido vendido, na ordem em que entrou na comanda
 * @param parcelas      como tinha sido pago, na ordem em que as parcelas foram lançadas, inclusive
 *                      as recusadas: o status diz o que cada uma valia
 * @param recebimentos  o que entrou de um FIADO antes do cancelamento, em qualquer sessão; vazio
 *                      quando a venda não teve fiado recebido
 */
public record VendaCancelada(ContaId contaId, UUID vendaId, UUID sessaoCaixaId, UUID usuarioId,
        List<Item> itens, List<Parcela> parcelas, List<Recebimento> recebimentos) {

    public VendaCancelada(ContaId contaId, UUID vendaId, UUID sessaoCaixaId, UUID usuarioId,
            List<Item> itens, List<Parcela> parcelas) {
        this(contaId, vendaId, sessaoCaixaId, usuarioId, itens, parcelas, List.of());
    }

    public VendaCancelada {
        Objects.requireNonNull(contaId, "contaId nao pode ser nulo");
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        Objects.requireNonNull(sessaoCaixaId, "sessaoCaixaId nao pode ser nulo");
        Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        itens = List.copyOf(Objects.requireNonNull(itens, "itens nao pode ser nulo"));
        parcelas = List.copyOf(Objects.requireNonNull(parcelas, "parcelas nao pode ser nulo"));
        recebimentos = List.copyOf(
                Objects.requireNonNull(recebimentos, "recebimentos nao pode ser nulo"));
    }

    /**
     * Um item que tinha sido vendido, reduzido ao que outro módulo pode precisar: qual produto e
     * quanto volta.
     */
    public record Item(UUID produtoId, BigDecimal quantidade) {

        public Item {
            Objects.requireNonNull(produtoId, "produtoId nao pode ser nulo");
            Objects.requireNonNull(quantidade, "quantidade nao pode ser nula");
        }
    }

    /**
     * Uma parcela do pagamento, como ficou registrada na venda. As parcelas não mudam com o
     * cancelamento: o que foi confirmado foi confirmado, e o status da venda é o fato novo.
     *
     * @param status o que a parcela valia: só CONFIRMADO é dinheiro que entrou de fato
     */
    public record Parcela(FormaPagamento forma, Money valor, StatusPagamento status) {

        public Parcela {
            Objects.requireNonNull(forma, "forma nao pode ser nula");
            Objects.requireNonNull(valor, "valor nao pode ser nulo");
            Objects.requireNonNull(status, "status nao pode ser nulo");
        }
    }

    public record Recebimento(UUID id, UUID sessaoCaixaId, FormaPagamento forma, Money valor) {
        public Recebimento {
            Objects.requireNonNull(id);
            Objects.requireNonNull(sessaoCaixaId);
            Objects.requireNonNull(forma);
            Objects.requireNonNull(valor);
        }
    }
}
