package br.com.caixasimples.vendas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NsuTest {

    private static final String QUARENTA = "A".repeat(40);

    @Test
    @DisplayName("o NSU perde os espaços das pontas, e o vazio vale como não informado")
    void normalizaOTexto() {
        assertThat(Nsu.normalizar(null)).isNull();
        assertThat(Nsu.normalizar("")).isNull();
        assertThat(Nsu.normalizar("   ")).isNull();
        assertThat(Nsu.normalizar("\t\n")).isNull();
        assertThat(Nsu.normalizar("  004512 ")).isEqualTo("004512");
        // Texto livre: há operadora que imprime letras e separador no identificador.
        assertThat(Nsu.normalizar("Ab-12 cd")).isEqualTo("Ab-12 cd");
    }

    @Test
    @DisplayName("o NSU tem até 40 caracteres, contados depois do corte")
    void limitaOTamanho() {
        assertThat(Nsu.normalizar(QUARENTA)).isEqualTo(QUARENTA);
        assertThat(Nsu.normalizar("  " + QUARENTA + "  ")).isEqualTo(QUARENTA);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Nsu.normalizar(QUARENTA + "B"))
                .withMessageContaining("41 caracteres")
                .withMessageContaining("limite e 40");
    }

    @Test
    @DisplayName("a parcela em cartão guarda o NSU normalizado; as outras formas o recusam")
    void parcelaSoTemNsuNoCartao() {
        Instant agora = Instant.now();

        Pagamento comNsu = parcela(FormaPagamento.CARTAO, " 004512 ", agora);
        assertThat(comNsu.nsu()).isEqualTo("004512");
        assertThat(parcela(FormaPagamento.CARTAO, "  ", agora).nsu()).isNull();
        assertThat(parcela(FormaPagamento.CARTAO, null, agora).nsu()).isNull();

        for (FormaPagamento forma : List.of(FormaPagamento.DINHEIRO, FormaPagamento.PIX,
                FormaPagamento.FIADO)) {
            assertThatIllegalArgumentException()
                    .as("NSU em " + forma)
                    .isThrownBy(() -> parcela(forma, "004512", agora))
                    .withMessageContaining("NSU so existe em cartao");
            // Em branco é não informado, e não um NSU fora do lugar.
            assertThat(parcela(forma, " ", agora).nsu()).as("branco em " + forma).isNull();
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> parcela(FormaPagamento.CARTAO, QUARENTA + "B", agora))
                .withMessageContaining("limite e 40");
    }

    @Test
    @DisplayName("o recebimento de fiado em cartão guarda o NSU; dinheiro e Pix o recusam")
    void recebimentoSoTemNsuNoCartao() {
        Recebimento comNsu = recebimento(FormaPagamento.CARTAO, " 778899 ");
        assertThat(comNsu.nsu()).isEqualTo("778899");
        assertThat(recebimento(FormaPagamento.CARTAO, "").nsu()).isNull();
        assertThat(new Recebimento(UUID.randomUUID(), UUID.randomUUID(), Money.de("5.00"),
                FormaPagamento.CARTAO, Instant.now()).nsu()).isNull();

        for (FormaPagamento forma : List.of(FormaPagamento.DINHEIRO, FormaPagamento.PIX)) {
            assertThatIllegalArgumentException()
                    .as("NSU em " + forma)
                    .isThrownBy(() -> recebimento(forma, "778899"))
                    .withMessageContaining("NSU so existe em cartao");
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> recebimento(FormaPagamento.CARTAO, QUARENTA + "B"))
                .withMessageContaining("limite e 40");
    }

    @Test
    @DisplayName("a raiz anexa a parcela com o NSU, aceita o repetido e não deixa rastro da recusada")
    void raizRegistraAParcelaComONsu() {
        Venda venda = new Venda(UUID.randomUUID(), UUID.randomUUID());
        venda.adicionarItem(UUID.randomUUID(), BigDecimal.ONE, Money.de("20.00"), Money.ZERO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.registrarPagamento(UUID.randomUUID(),
                        FormaPagamento.DINHEIRO, Money.de("5.00"), StatusPagamento.CONFIRMADO,
                        Money.ZERO, Instant.now(), "004512"));
        assertThat(venda.getPagamentos()).isEmpty();

        // O número só é único dentro de cada operadora: dois pagamentos legítimos podem repeti-lo.
        venda.registrarPagamento(UUID.randomUUID(), FormaPagamento.CARTAO, Money.de("10.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO, Instant.now(), " 004512");
        venda.registrarPagamento(UUID.randomUUID(), FormaPagamento.CARTAO, Money.de("10.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO, Instant.now(), "004512");

        assertThat(venda.getPagamentos()).extracting(Pagamento::nsu)
                .containsExactly("004512", "004512");
        venda.concluir();
        assertThat(venda.getPagamentos()).extracting(Pagamento::nsu)
                .containsExactly("004512", "004512");
    }

    @Test
    @DisplayName("a raiz anexa o recebimento de fiado com o NSU")
    void raizRegistraORecebimentoComONsu() {
        Venda venda = new Venda(UUID.randomUUID(), UUID.randomUUID());
        venda.adicionarItem(UUID.randomUUID(), BigDecimal.ONE, Money.de("20.00"), Money.ZERO);
        venda.vincularCliente(UUID.randomUUID());
        venda.registrarPagamento(FormaPagamento.FIADO, Money.de("20.00"),
                StatusPagamento.PENDENTE, Money.ZERO);
        venda.concluir();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.receber(UUID.randomUUID(), UUID.randomUUID(),
                        Money.de("5.00"), FormaPagamento.PIX, "778899"));
        assertThat(venda.getRecebimentos()).isEmpty();

        Recebimento recebido = venda.receber(UUID.randomUUID(), UUID.randomUUID(),
                Money.de("20.00"), FormaPagamento.CARTAO, "778899 ");

        assertThat(recebido.nsu()).isEqualTo("778899");
        assertThat(venda.getRecebimentos()).extracting(Recebimento::nsu).containsExactly("778899");
        assertThat(venda.getPagamentos().getFirst().status()).isEqualTo(StatusPagamento.CONFIRMADO);
    }

    private static Pagamento parcela(FormaPagamento forma, String nsu, Instant agora) {
        StatusPagamento status = forma == FormaPagamento.FIADO
                ? StatusPagamento.PENDENTE : StatusPagamento.CONFIRMADO;
        return new Pagamento(UUID.randomUUID(), forma, Money.de("9.00"), status, Money.ZERO, agora,
                null, nsu);
    }

    private static Recebimento recebimento(FormaPagamento forma, String nsu) {
        return new Recebimento(UUID.randomUUID(), UUID.randomUUID(), Money.de("5.00"), forma,
                Instant.now(), nsu);
    }
}
