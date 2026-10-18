package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.contas.application.ContaService.ConfiguracaoDaConta;
import br.com.caixasimples.shared.EventoDeSeguranca;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * plano e desligá-lo pede que não haja movimento, a exigência do NSU não pede nada, e o nome do
 * negócio só pede texto. Todas respondem a configuração inteira, para a tela não precisar de uma
 * segunda leitura.
 *
 * <p>Cada mudança vai ao log como evento de segurança, com o campo, quem mudou e a origem: é ação do
 * administrador sobre a Conta. As chaves levam o valor novo; o nome do negócio não, porque pode
 * identificar o dono.
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
    Configuracao definir(@Valid @RequestBody Pedido pedido, HttpServletRequest requisicao) {
        ConfiguracaoDaConta configuracao =
                conta.definirEstoqueHabilitado(pedido.estoqueHabilitado());
        registrar("estoque", pedido.estoqueHabilitado(), requisicao);
        return Configuracao.de(configuracao);
    }

    @PutMapping("/nsu")
    Configuracao definirNsu(@Valid @RequestBody PedidoDoNsu pedido,
            HttpServletRequest requisicao) {
        ConfiguracaoDaConta configuracao = conta.definirNsuObrigatorio(pedido.nsuObrigatorio());
        registrar("nsu", pedido.nsuObrigatorio(), requisicao);
        return Configuracao.de(configuracao);
    }

    @PutMapping("/nome-do-negocio")
    Configuracao corrigirNomeDoNegocio(@Valid @RequestBody PedidoDoNome pedido,
            HttpServletRequest requisicao) {
        ConfiguracaoDaConta configuracao = conta.corrigirNomeDoNegocio(pedido.nomeNegocio());
        EventoDeSeguranca.de("configuracao.alterada")
                .doContexto()
                .com("campo", "nome_negocio")
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
        return Configuracao.de(configuracao);
    }

    private static void registrar(String campo, boolean valor, HttpServletRequest requisicao) {
        EventoDeSeguranca.de("configuracao.alterada")
                .doContexto()
                .com("campo", campo)
                .com("valor", valor)
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
    }

    record Pedido(@NotNull Boolean estoqueHabilitado) {
    }

    record PedidoDoNsu(@NotNull Boolean nsuObrigatorio) {
    }

    record PedidoDoNome(@NotBlank String nomeNegocio) {
    }

    record Configuracao(String nomeNegocio, boolean estoqueHabilitado, boolean nsuObrigatorio) {

        static Configuracao de(ConfiguracaoDaConta configuracao) {
            return new Configuracao(configuracao.nomeNegocio(), configuracao.estoqueHabilitado(),
                    configuracao.nsuObrigatorio());
        }
    }
}
