package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.ContaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuração da Conta autenticada, sem id de Conta na rota ou no pedido (RNF05). */
@RestController
@RequestMapping("/api/conta/configuracao")
class ConfiguracaoDaContaController {

    private final ContaService conta;

    ConfiguracaoDaContaController(ContaService conta) {
        this.conta = conta;
    }

    @GetMapping
    Configuracao consultar() {
        return new Configuracao(conta.configuracaoDeEstoque());
    }

    @PutMapping
    Configuracao definir(@Valid @RequestBody Pedido pedido) {
        return new Configuracao(conta.definirEstoqueHabilitado(pedido.estoqueHabilitado()));
    }

    record Pedido(@NotNull Boolean estoqueHabilitado) {
    }

    record Configuracao(boolean estoqueHabilitado) {
    }
}
