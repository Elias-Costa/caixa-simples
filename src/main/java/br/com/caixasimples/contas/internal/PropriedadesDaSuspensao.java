package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A Conta da {@link SuspensaoDeContaDoMantenedor} e da {@link ReativacaoDeContaDoMantenedor}, vinda
 * do ambiente de quem roda o comando na máquina.
 *
 * <p>É o id, e não o nome do negócio nem o e-mail de um administrador: o id é o que os eventos de
 * segurança do log levam, e não identifica ninguém fora do banco.
 */
@ConfigurationProperties(prefix = "caixa-simples.suspensao")
record PropriedadesDaSuspensao(String conta) {

    /**
     * O id já conferido, antes de qualquer leitura no banco.
     *
     * @throws IllegalStateException se a Conta não foi informada
     * @throws IllegalArgumentException se o valor não é um id
     */
    ContaId contaId() {
        if (conta == null || conta.isBlank()) {
            throw new IllegalStateException("Informe caixa-simples.suspensao.conta");
        }
        try {
            return ContaId.de(conta.trim());
        } catch (IllegalArgumentException malformado) {
            throw new IllegalArgumentException("O valor informado não é o id de uma Conta");
        }
    }
}
