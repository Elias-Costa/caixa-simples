package br.com.caixasimples.cadastro.web;

import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.ClienteComVersao;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
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

/** Cliente se cadastra no balcão: ADMIN e OPERADOR usam os mesmos casos de uso. */
@RestController
@RequestMapping("/api/clientes")
class ClienteController {

    private final ClienteService clientes;

    ClienteController(ClienteService clientes) {
        this.clientes = clientes;
    }

    @GetMapping
    List<ClienteComVersao> listar() {
        return clientes.listarAtivosComVersao();
    }

    @GetMapping("/inativos")
    List<ClienteComVersao> listarInativos() {
        return clientes.listarInativosComVersao();
    }

    @PostMapping
    ResponseEntity<Criado> cadastrar(@Valid @RequestBody PedidoDeCliente pedido) {
        UUID id = clientes.cadastrar(pedido.dados());
        return ResponseEntity.created(URI.create("/api/clientes/" + id)).body(new Criado(id));
    }

    @PutMapping("/{id}")
    ResponseEntity<Void> editar(@PathVariable UUID id, @Valid @RequestBody PedidoDeCliente pedido) {
        clientes.editar(id, pedido.dados());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/inativar")
    ResponseEntity<Void> inativar(@PathVariable UUID id) {
        clientes.inativar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reativar")
    ResponseEntity<Void> reativar(@PathVariable UUID id) {
        clientes.reativar(id);
        return ResponseEntity.noContent().build();
    }

    record PedidoDeCliente(@NotBlank String nome, String contato) {

        DadosDoCliente dados() {
            return new DadosDoCliente(nome, contato);
        }
    }

    record Criado(UUID id) {
    }
}
