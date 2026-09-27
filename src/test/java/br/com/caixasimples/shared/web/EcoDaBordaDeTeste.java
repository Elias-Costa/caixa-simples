package br.com.caixasimples.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota que só existe na suíte e devolve o que o servidor concluiu sobre a requisição depois de ler
 * os cabeçalhos dos proxies: o endereço de origem, o esquema e se ela conta como segura.
 *
 * <p>Fica fora de {@code /api} para responder sem token. A leitura dos cabeçalhos acontece antes
 * de qualquer rota e vale igual para todas, então um token só acrescentaria uma Conta ao teste sem
 * provar nada a mais.
 *
 * <p>Descoberta por varredura, como {@link ControllerDeErrosDeTeste} e pelo mesmo motivo.
 */
@RestController
class EcoDaBordaDeTeste {

    @GetMapping("/teste/borda")
    Map<String, Object> borda(HttpServletRequest requisicao) {
        return Map.of(
                "origem", requisicao.getRemoteAddr(),
                "esquema", requisicao.getScheme(),
                "segura", requisicao.isSecure());
    }
}
