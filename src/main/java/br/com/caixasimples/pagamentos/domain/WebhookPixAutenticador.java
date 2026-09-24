package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.shared.ContaId;

/** Resolve a configuração do webhook sem aceitar identidade da Conta no payload. */
public interface WebhookPixAutenticador {
    ContaId autenticar(String identificador, String segredo);
}
