package br.com.caixasimples.caixa.application;

import java.util.UUID;

/**
 * Não existe sessão de caixa com esse id <strong>nesta conta</strong>.
 *
 * <p>A distinção entre um id que nunca existiu e um id que existe em outra conta não chega até
 * aqui, e isso é o desenho funcionando: toda consulta de {@code SessaoCaixaRepository} passa pelo
 * filtro de {@code @TenantId}, então a sessão de outra conta simplesmente não volta do banco
 * (RNF05). Nem o caso de uso nem quem o chama tem como diferenciar os dois casos, e nem deve ter.
 *
 * <p>Sem aspas duplas neste javadoc, de propósito: o {@code Documenter} do Modulith lê os
 * comentários de um JSON gerado pelo processador de anotações com um parser simples, que trata
 * aspa dentro de string como delimitador e faz o teste de fronteiras estourar com um erro que não
 * menciona javadoc.
 */
public class SessaoCaixaNaoEncontradaException extends RuntimeException {

    public SessaoCaixaNaoEncontradaException(UUID id) {
        super("sessao de caixa nao encontrada nesta conta: " + id);
    }
}
