package br.com.caixasimples.contas.internal;

/**
 * Porta para a checagem de senha em lista de vazamentos publicos (decisao A5).
 *
 * <p>Existe como interface por um motivo concreto, nao por precaucao: o adapter real faz chamada
 * HTTP externa, e a suite de testes nao pode depender de rede.
 */
public interface VerificadorDeSenhaVazada {

    /**
     * Falha se a senha aparece em vazamento conhecido — e tambem se nao foi possivel verificar.
     *
     * <p>Falhar quando a verificacao esta indisponivel e deliberado (A5): sem conseguir conferir,
     * nenhuma senha entra. Hoje o custo disso e baixo porque quem cria conta e o mantenedor, online
     * (A3). <strong>No dia em que troca de senha virar autoatendimento, essa decisao tem de ser
     * reaberta</strong> — recusar passa a ser fricção real no balcão.
     *
     * @throws SenhaRecusadaException se a senha esta vazada ou se a verificacao falhou
     */
    void exigirNaoVazada(String senha);
}
