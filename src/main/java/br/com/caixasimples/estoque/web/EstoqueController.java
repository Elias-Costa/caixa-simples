package br.com.caixasimples.estoque.web;

import br.com.caixasimples.cadastro.application.ProdutoService.EstoqueDoProduto;
import br.com.caixasimples.estoque.application.EstoqueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @PostMapping("/produtos/{id}/ajustes")
    ResponseEntity<Void> ajustar(@PathVariable UUID id, @Valid @RequestBody PedidoDeAjuste pedido) {
        estoque.ajustar(id, pedido.diferenca(), pedido.motivo());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/produtos/{id}/minimo")
    ResponseEntity<Void> definirMinimo(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeMinimo pedido) {
        estoque.definirEstoqueMinimo(id, pedido.minimo());
        return ResponseEntity.noContent().build();
    }

    record PedidoDeAjuste(@NotNull @Digits(integer = 9, fraction = 3) BigDecimal diferenca,
            @NotBlank String motivo) {
    }

    record PedidoDeMinimo(@NotNull @DecimalMin("0.000") @Digits(integer = 9, fraction = 3)
            BigDecimal minimo) {
    }
}
