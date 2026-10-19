package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.contas.application.ContaService.ConfiguracaoDaConta;
import br.com.caixasimples.contas.internal.ChavePix;
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
 * plano e desligá-lo pede que não haja movimento, a exigência do NSU não pede nada, o nome do
 * negócio só pede texto, e a chave Pix pede a senha atual, porque decide para onde vai o dinheiro do
 * cliente. Todas respondem a configuração inteira, para a tela não precisar de uma segunda leitura.
 *
 * <p>Cada mudança vai ao log como evento de segurança, com o campo, quem mudou e a origem: é ação do
 * administrador sobre a Conta. As chaves levam o valor novo; o nome do negócio e a chave Pix não,
 * porque podem identificar o dono.
 */
@RestController
@RequestMapping("/api/conta/configuracao")
class ConfiguracaoDaContaController {

    private final ContaService conta;
    private final AutenticacaoService autenticacao;

    ConfiguracaoDaContaController(ContaService conta, AutenticacaoService autenticacao) {
        this.conta = conta;
        this.autenticacao = autenticacao;
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

    /**
     * Cadastra, troca ou tira a chave Pix e a cidade do recebedor do QR do Pix manual. Sem tipo e
     * sem chave, tira as duas. A senha atual errada responde 400 e vai ao log pelo tratador do
     * módulo.
     */
    @PutMapping("/chave-pix")
    Configuracao definirChavePix(@Valid @RequestBody PedidoDaChavePix pedido,
            HttpServletRequest requisicao) {
        ConfiguracaoDaConta configuracao = autenticacao.definirChavePix(pedido.senhaAtual(),
                pedido.tipo(), pedido.chave(), pedido.cidade(), requisicao.getRemoteAddr());
        EventoDeSeguranca.de("configuracao.alterada")
                .doContexto()
                .com("campo", "chave_pix")
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

    /**
     * @param tipo nulo, com a chave em branco, para tirar a chave
     */
    record PedidoDaChavePix(@NotBlank String senhaAtual, ChavePix.Tipo tipo, String chave,
            String cidade) {
    }

    /**
     * @param tipoDaChavePix omitido enquanto a Conta não tem chave
     * @param chavePix       omitida enquanto a Conta não tem chave
     * @param cidadePix      omitida junto com a chave
     */
    record Configuracao(String nomeNegocio, boolean estoqueHabilitado, boolean nsuObrigatorio,
            ChavePix.Tipo tipoDaChavePix, String chavePix, String cidadePix) {

        static Configuracao de(ConfiguracaoDaConta configuracao) {
            return new Configuracao(configuracao.nomeNegocio(), configuracao.estoqueHabilitado(),
                    configuracao.nsuObrigatorio(), configuracao.tipoDaChavePix(),
                    configuracao.chavePix(), configuracao.cidadePix());
        }
    }
}
