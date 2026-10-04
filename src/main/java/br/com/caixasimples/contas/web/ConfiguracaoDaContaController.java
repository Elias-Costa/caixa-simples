package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.contas.application.ContaService.ConfiguracaoDaConta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuração da Conta autenticada, sem id de Conta na rota ou no pedido (RNF05).
 *
 * <p>Cada chave muda por uma rota própria, porque cada uma tem a sua regra: ligar o estoque pede o
 * plano e desligá-lo pede que não haja movimento, e a exigência do NSU não pede nada. As duas
 * respondem a configuração inteira, para a tela não precisar de uma segunda leitura.
 */
@RestController
@RequestMapping("/api/conta/configuracao")
class ConfiguracaoDaContaController {

    private final ContaService conta;

    ConfiguracaoDaContaController(ContaService conta) {
        this.conta = conta;
    }

    @GetMapping
    Configuracao consultar() {
        return Configuracao.de(conta.configuracao());
    }

    @PutMapping
    Configuracao definir(@Valid @RequestBody Pedido pedido) {
        return Configuracao.de(conta.definirEstoqueHabilitado(pedido.estoqueHabilitado()));
    }

    @PutMapping("/nsu")
    Configuracao definirNsu(@Valid @RequestBody PedidoDoNsu pedido) {
        return Configuracao.de(conta.definirNsuObrigatorio(pedido.nsuObrigatorio()));
    }

    record Pedido(@NotNull Boolean estoqueHabilitado) {
    }

    record PedidoDoNsu(@NotNull Boolean nsuObrigatorio) {
    }

    record Configuracao(boolean estoqueHabilitado, boolean nsuObrigatorio) {

        static Configuracao de(ConfiguracaoDaConta configuracao) {
            return new Configuracao(configuracao.estoqueHabilitado(),
                    configuracao.nsuObrigatorio());
        }
    }
}
