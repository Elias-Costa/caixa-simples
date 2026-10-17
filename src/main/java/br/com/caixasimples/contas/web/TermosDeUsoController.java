package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.TermosDeUsoService;
import br.com.caixasimples.shared.EventoDeSeguranca;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * O aceite dos termos de uso pela Conta autenticada, sem id de Conta na rota ou no pedido (RNF05).
 *
 * <p>Se a Conta já aceitou, o aplicativo sabe pela identidade, então a rota só grava. O aceite vai
 * ao log como evento de segurança, com a versão, quem aceitou e a origem: é ação do administrador
 * sobre a Conta.
 */
@RestController
@RequestMapping("/api/conta/termos")
class TermosDeUsoController {

    private final TermosDeUsoService termos;

    TermosDeUsoController(TermosDeUsoService termos) {
        this.termos = termos;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void aceitar(@Valid @RequestBody PedidoDeAceite pedido, HttpServletRequest requisicao) {
        termos.aceitar(pedido.versao());
        EventoDeSeguranca.de("termos.aceitos")
                .doContexto()
                .com("versao", pedido.versao())
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
    }

    /** @param versao a data da versão que o aplicativo mostrou, como ele a recebeu com o texto */
    record PedidoDeAceite(@NotBlank String versao) {
    }
}
