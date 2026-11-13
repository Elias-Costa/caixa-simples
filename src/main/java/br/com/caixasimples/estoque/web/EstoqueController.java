package br.com.caixasimples.estoque.web;

import br.com.caixasimples.cadastro.application.ProdutoService.EstoqueDoProduto;
import br.com.caixasimples.estoque.application.EstoqueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A Conta e o perfil vêm dos contextos autenticados, nunca do corpo dos pedidos (RNF05). */
@RestController
@RequestMapping("/api/estoque")
class EstoqueController {

    private final EstoqueService estoque;

    EstoqueController(EstoqueService estoque) {
        this.estoque = estoque;
    }

    @GetMapping("/produtos")
    List<EstoqueDoProduto> produtos() {
        return estoque.produtos();
    }

    @GetMapping("/baixo")
    List<EstoqueDoProduto> baixo() {
        return estoque.produtosComEstoqueBaixo();
    }

    /**
     * A resposta é a mesma no primeiro pedido e no reenvio igual, porque não devolve nada: o
     * movimento repetido com o id do aparelho só deixa de ser gravado. Sem o id, o servidor gera um,
     * e o reenvio move o saldo de novo.
     */
    @PostMapping("/produtos/{id}/ajustes")
    ResponseEntity<Void> ajustar(@PathVariable UUID id, @Valid @RequestBody PedidoDeAjuste pedido) {
        estoque.ajustar(id, idDoMovimento(pedido.movimentoId()), pedido.diferenca(),
                pedido.motivo());
        return ResponseEntity.noContent().build();
    }

    /** A mercadoria que chegou; o motivo é opcional, e a quantidade, sempre positiva. */
    @PostMapping("/produtos/{id}/entradas")
    ResponseEntity<Void> registrarEntrada(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeEntrada pedido) {
        estoque.registrarEntrada(id, idDoMovimento(pedido.movimentoId()), pedido.quantidade(),
                pedido.motivo());
        return ResponseEntity.noContent().build();
    }

    private static UUID idDoMovimento(UUID doAparelho) {
        return doAparelho != null ? doAparelho : UUID.randomUUID();
    }

    @PutMapping("/produtos/{id}/minimo")
    ResponseEntity<Void> definirMinimo(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeMinimo pedido) {
        estoque.definirEstoqueMinimo(id, pedido.minimo());
        return ResponseEntity.noContent().build();
    }

    /**
     * O movimentoId é opcional porque o contrato com o aparelho só cresce: o aplicativo instalado
     * que não o manda continua ajustando.
     */
    record PedidoDeAjuste(UUID movimentoId,
            @NotNull @Digits(integer = 9, fraction = 3) BigDecimal diferenca,
            @NotBlank String motivo) {
    }

    /** O movimentoId é opcional pela mesma razão do ajuste. */
    record PedidoDeEntrada(UUID movimentoId, @NotNull @Positive @Digits(integer = 9, fraction = 3)
            BigDecimal quantidade, String motivo) {
    }

    record PedidoDeMinimo(@NotNull @DecimalMin("0.000") @Digits(integer = 9, fraction = 3)
            BigDecimal minimo) {
    }
}
