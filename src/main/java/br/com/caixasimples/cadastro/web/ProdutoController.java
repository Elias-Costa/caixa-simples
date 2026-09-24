package br.com.caixasimples.cadastro.web;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Entrada HTTP do catálogo da Conta: o corpo nunca aceita contaId nem saldo de estoque. */
@RestController
@RequestMapping("/api/produtos")
class ProdutoController {

    private final ProdutoService produtos;

    ProdutoController(ProdutoService produtos) {
        this.produtos = produtos;
    }

    @GetMapping
    List<ProdutoNaLista> listar() {
        return produtos.listarAtivosComVersao().stream()
                .map(item -> ProdutoNaLista.de(item.produto(), item.versao())).toList();
    }

    /** RF06: código exato primeiro; depois nomes que contêm o termo. */
    @GetMapping("/busca")
    List<ProdutoNaLista> buscar(@RequestParam String termo) {
        return produtos.buscarPorNomeOuCodigoComVersao(termo).stream()
                .map(item -> ProdutoNaLista.de(item.produto(), item.versao())).toList();
    }

    @PostMapping
    ResponseEntity<Criado> cadastrar(@Valid @RequestBody PedidoDeCadastro pedido) {
        UUID id = produtos.cadastrar(pedido.tipo(), pedido.dados());
        return ResponseEntity.created(URI.create("/api/produtos/" + id)).body(new Criado(id));
    }

    @PutMapping("/{id}")
    ResponseEntity<Void> editar(@PathVariable UUID id, @Valid @RequestBody PedidoDeEdicao pedido) {
        produtos.editar(id, pedido.dados());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/inativar")
    ResponseEntity<Void> inativar(@PathVariable UUID id) {
        produtos.inativar(id);
        return ResponseEntity.noContent().build();
    }

    record PedidoDeCadastro(@NotNull TipoProduto tipo, @NotBlank String nome,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal preco,
            String codigo, String categoria, String unidade, Map<String, Object> atributos) {

        DadosDoProduto dados() {
            return new DadosDoProduto(nome, Money.de(preco), codigo, categoria, unidade, atributos);
        }
    }

    /** Tipo não entra na edição: o agregado mantém PRODUTO ou SERVICO até ser inativado. */
    record PedidoDeEdicao(@NotBlank String nome,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal preco,
            String codigo, String categoria, String unidade, Map<String, Object> atributos) {

        DadosDoProduto dados() {
            return new DadosDoProduto(nome, Money.de(preco), codigo, categoria, unidade, atributos);
        }
    }

    record ProdutoNaLista(UUID id, TipoProduto tipo, String nome, BigDecimal preco, String codigo,
            String categoria, String unidade, Map<String, Object> atributos, long versao) {

        static ProdutoNaLista de(Produto produto, long versao) {
            return new ProdutoNaLista(produto.getId(), produto.getTipo(), produto.getNome(),
                    produto.getPreco().valor(), produto.getCodigo(), produto.getCategoria(),
                    produto.getUnidade(), produto.getAtributos(), versao);
        }
    }

    record Criado(UUID id) {
    }
}
