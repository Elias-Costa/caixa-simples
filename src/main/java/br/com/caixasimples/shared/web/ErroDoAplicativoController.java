package br.com.caixasimples.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recebe do aplicativo o erro que aconteceu nele, como a exceção de JavaScript que nenhuma tela
 * tratou, e o grava no log em ERROR, onde o alerta de erros da máquina o conta. Sem isso, um defeito
 * do aplicativo só chegaria ao mantenedor pelo relato do cliente.
 *
 * <p>Vem só o que localiza o defeito: o tipo do erro, o arquivo, a linha e a coluna do código do
 * aplicativo, a tela e a versão. A mensagem do erro não vem, porque pode trazer o que se digitou, e
 * a linha não leva Conta, usuário nem origem, como a do erro inesperado do servidor: nada nela é dado
 * pessoal.
 *
 * <p>Cada campo aceita só letras sem acento, dígitos e os sinais ._:/@%+-, o mesmo conjunto dos
 * eventos de segurança: um espaço ou uma quebra de linha bastariam para forjar outra linha no log, e
 * o pedido que os traz é recusado com 400. A rota exige o token, como toda a API, e um robô sem
 * sessão não alcança o log por ela.
 */
@RestController
@RequestMapping("/api/erros-do-aplicativo")
class ErroDoAplicativoController {

    private static final Logger log = LoggerFactory.getLogger(ErroDoAplicativoController.class);

    @PostMapping
    ResponseEntity<Void> registrar(@Valid @RequestBody ErroDoAplicativo erro) {
        log.error("Erro no aplicativo tipo={} local={} tela={} versao={}", erro.tipo(), erro.local(),
                erro.tela(), erro.versao());
        return ResponseEntity.noContent().build();
    }

    /**
     * O tipo é o nome do erro, como TypeError; o local, o arquivo do aplicativo com a linha e a
     * coluna; a tela, o caminho dela, que não tem id; a versão, o commit do build.
     */
    record ErroDoAplicativo(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._:/@%+-]{1,60}") String tipo,
            @NotNull @Pattern(regexp = "[A-Za-z0-9._:/@%+-]{1,200}") String local,
            @NotNull @Pattern(regexp = "[A-Za-z0-9._:/@%+-]{1,60}") String tela,
            @NotNull @Pattern(regexp = "[A-Za-z0-9._:/@%+-]{1,40}") String versao) {
    }
}
