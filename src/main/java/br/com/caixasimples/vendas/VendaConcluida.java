package br.com.caixasimples.vendas;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de domínio: uma venda foi concluída (RF09). É o fato que este módulo anuncia para que o
 * caixa lance o dinheiro na gaveta e o estoque dê baixa nos itens, sem que a venda chame nenhum
 * dos dois.
 *
 * <p><strong>Carrega o fato inteiro</strong>, e não só o que o primeiro ouvinte precisa: quem
 * decide o que entra na gaveta é o caixa, lendo as parcelas; quem decide o que baixa é o estoque,
 * lendo os itens. A venda não faz conta em nome de ninguém.
 *
 * <p><strong>É ouvido dentro da transação que concluiu a venda.</strong> Os dois ouvintes rodam na
 * thread e na transação de quem publicou, então a venda concluída, o dinheiro na gaveta e a baixa
 * confirmam juntos ou não confirmam. É o que deixa a sangria registrada depois da venda, no mesmo
 * lote vindo do dispositivo sem rede, encontrar o dinheiro dela no caixa.
 *
 * <p><strong>Carrega a conta</strong>, ao contrário dos agregados, que nunca a expõem. O valor vem
 * do contexto autenticado no momento da publicação, nunca de quem chamou o caso de uso (RNF05), e
 * os ouvintes conferem que rodam nessa mesma conta.
 *
 * @param contaId       a conta a que a venda pertence
 * @param vendaId       a venda concluída
 * @param sessaoCaixaId a sessão de caixa em que ela nasceu, e onde o dinheiro entra
 * @param usuarioId     o operador que vendeu
 * @param itens         o que foi vendido, na ordem em que entrou na comanda
 * @param parcelas      como foi pago, na ordem em que as parcelas foram lançadas, inclusive as
 *                      recusadas: o status diz o que cada uma vale
 * @param concluidoEm   quando a venda foi concluída, o instante do balcão quando ela foi registrada
 *                      sem rede; é quando o dinheiro entrou na gaveta
 */
public record VendaConcluida(ContaId contaId, UUID vendaId, UUID sessaoCaixaId, UUID usuarioId,
        List<Item> itens, List<Parcela> parcelas, Instant concluidoEm) {

    public VendaConcluida {
        Objects.requireNonNull(contaId, "contaId nao pode ser nulo");
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        Objects.requireNonNull(sessaoCaixaId, "sessaoCaixaId nao pode ser nulo");
        Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        itens = List.copyOf(Objects.requireNonNull(itens, "itens nao pode ser nulo"));
        parcelas = List.copyOf(Objects.requireNonNull(parcelas, "parcelas nao pode ser nulo"));
        Objects.requireNonNull(concluidoEm, "concluidoEm nao pode ser nulo");
    }

    /**
     * Um item vendido, reduzido ao que outro módulo pode precisar: qual produto e quanto. Preço e
     * desconto ficam na venda, que é quem responde por dinheiro.
     */
    public record Item(UUID produtoId, BigDecimal quantidade) {

        public Item {
            Objects.requireNonNull(produtoId, "produtoId nao pode ser nulo");
            Objects.requireNonNull(quantidade, "quantidade nao pode ser nula");
        }
    }

    /**
     * Uma parcela do pagamento, como ficou registrada na venda.
     *
     * @param status o que a parcela vale: só CONFIRMADO é dinheiro que entrou de fato
     */
    public record Parcela(FormaPagamento forma, Money valor, StatusPagamento status) {

        public Parcela {
            Objects.requireNonNull(forma, "forma nao pode ser nula");
            Objects.requireNonNull(valor, "valor nao pode ser nulo");
            Objects.requireNonNull(status, "status nao pode ser nulo");
        }
    }
}
