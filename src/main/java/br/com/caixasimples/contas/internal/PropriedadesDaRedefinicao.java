package br.com.caixasimples.contas.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * O administrador e a senha provisória da {@link RedefinicaoDeSenhaDoMantenedor}, vindos do
 * ambiente.
 *
 * <p>Sem valor padrão: uma senha padrão em código seria a mesma em toda instalação.
 */
@ConfigurationProperties(prefix = "fechadia.redefinicao")
record PropriedadesDaRedefinicao(String email, String senha) {
}
